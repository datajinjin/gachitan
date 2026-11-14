package kr.gachitan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 장소·정차 기록과 여러 날 여행 정산 검증. */
class JourneyTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val driver = Rider("driver", "나")
    private var t = 0L

    private fun TripRecorder.gear(g: Gear) = onEvent(VehicleEvent.GearChanged(g, ++t))
    private fun TripRecorder.km(v: Double) = onEvent(VehicleEvent.OdometerChanged(v, ++t))
    private fun TripRecorder.place(name: String?, lat: Double?, lon: Double?, src: PlaceSource) =
        onEvent(VehicleEvent.PlaceObserved(Place(name = name, latitude = lat, longitude = lon, source = src), ++t))

    private fun millis(y: Int, m: Int, d: Int, h: Int) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, seoul).toInstant().toEpochMilli()

    // ------------------------------------------------------------------ 정차 지점

    @Test
    fun `내비 도착지와 정차 중 받은 위치가 정차 지점에 기록된다`() {
        val rec = TripRecorder(driver).apply {
            km(0.0)
            place(null, 37.5000, 127.0000, PlaceSource.LOCATION)          // 출발 전 위치
            gear(Gear.DRIVE)
            place("행담도휴게소", 36.9600, 126.8000, PlaceSource.NAVI)      // 주행 중 내비 도착
            km(50.0); gear(Gear.PARK)                                       // 정차 1
            gear(Gear.DRIVE); km(80.0); gear(Gear.PARK)                      // 정차 2 (이번 구간엔 내비 정보 없음)
            place(null, 37.7700, 128.9000, PlaceSource.LOCATION)             // 정차 중 받은 위치
            place("강릉 숙소", 37.7701, 128.9001, PlaceSource.LOCATION)       // 늦게 도착한 주소 변환
            place("우리집", 37.5001, 127.0001, PlaceSource.LOCATION)          // 출발지 주소 변환(먼 곳)
        }
        val stops = rec.stops()
        assertEquals(2, stops.size)
        assertEquals("행담도휴게소", stops[0].place?.name)
        assertEquals("강릉 숙소", stops[1].place?.name)          // 오래된 휴게소 정보가 붙지 않음
        assertEquals("우리집", rec.startPlace()?.name)            // 먼 곳 정보는 정차 지점이 아니라 출발지를 채움
        assertEquals(80.0, stops[1].km, 1e-9)
    }

    @Test
    fun `사람이 붙인 이름이 가장 우선이다`() {
        val rec = TripRecorder(driver).apply {
            km(0.0); gear(Gear.DRIVE); km(10.0); gear(Gear.PARK)
            place("자동 주소", 37.0, 127.0, PlaceSource.LOCATION)
            labelStop(0, "민지네 집 앞")
            place("다른 주소", 37.0, 127.0, PlaceSource.NAVI)
        }
        assertEquals("민지네 집 앞", rec.stops()[0].place?.name)
    }

    @Test
    fun `출발 직후 받은 위치는 출발지가 된다`() {
        val rec = TripRecorder(driver).apply {
            km(0.0); gear(Gear.DRIVE)
            place(null, 35.1, 129.0, PlaceSource.LOCATION)
            km(5.0); gear(Gear.PARK)
        }
        assertEquals(35.1, rec.startPlace()?.latitude!!, 1e-9)
        assertEquals(null, rec.stops()[0].place)
    }

    @Test
    fun `장소와 이름 붙이기도 기록장 저장·복원에 포함된다`() {
        val j = TripJournal(driver)
        listOf<TripCommand>(
            TripCommand.Event(VehicleEvent.OdometerChanged(0.0, 1)),
            TripCommand.Event(VehicleEvent.GearChanged(Gear.DRIVE, 2)),
            TripCommand.Event(VehicleEvent.PlaceObserved(Place("휴게소|A", "주소, 1", 36.9, 126.8, PlaceSource.NAVI), 3)),
            TripCommand.Event(VehicleEvent.OdometerChanged(40.0, 4)),
            TripCommand.Event(VehicleEvent.GearChanged(Gear.PARK, 5)),
            TripCommand.LabelStop(0, "점심 먹은 곳")
        ).forEach(j::apply)
        val restored = TripJournal.decode(j.encode())
        assertEquals(j.recorder.stops(), restored.recorder.stops())
        assertEquals("점심 먹은 곳", restored.recorder.stops()[0].place?.name)
        assertEquals("주소, 1", restored.recorder.stops()[0].place?.address)
    }

    // ------------------------------------------------------------------ 여행 정산

    private fun trip(start: Long, km: Double, shares: List<Pair<Member, Long>>, route: List<String>) = TripEntry(
        startedAtMillis = start, endedAtMillis = start + 3_600_000, distanceKm = km,
        costWon = shares.sumOf { it.second }, basis = CostBasis.MEASURED_ENERGY_USE, driverId = "driver",
        startName = route.first(), stops = route.drop(1).mapIndexed { i, n -> StopEntry(n, km * (i + 1) / (route.size - 1), start) },
        shares = shares.map { (m, w) -> ShareEntry(m.id, m.name, km, w) }
    )

    private val me = Member("driver", "나")
    private val minji = Member("minji", "민지")
    private val junho = Member("junho", "준호")

    private fun gangneung(): Journey {
        val day1 = millis(2026, 11, 13, 9)
        val day2 = millis(2026, 11, 14, 10)
        return Journey("j1", "강릉 2박 3일", day1)
            .withTrip(trip(day1, 198.0, listOf(me to 4000L, minji to 4000L, junho to 4000L), listOf("서울", "휴게소", "강릉 숙소")))
            .withTrip(trip(day2, 38.0, listOf(me to 3000L, junho to 3000L), listOf("강릉 숙소", "안목해변", "강릉 숙소")))
            .withExpense(SharedExpense("x1", "숙박", 120_000, "minji", emptySet(), day1))
            .withExpense(SharedExpense("x2", "식비", 30_000, "junho", emptySet(), day2))
    }

    @Test
    fun `날짜별로 묶고 들른 곳을 순서대로 보여준다`() {
        val s = JourneyCalculator.summarize(gangneung(), seoul)
        assertEquals(2, s.days.size)
        assertEquals(1, s.days[0].dayNumber)
        assertEquals(2, s.days[1].dayNumber)
        assertEquals(listOf("서울", "휴게소", "강릉 숙소"), s.days[0].route)
        assertEquals(listOf("강릉 숙소", "안목해변", "강릉 숙소"), s.days[1].route)
        assertEquals(236.0, s.totalKm, 1e-9)
        assertEquals(18_000L, s.totalFuelWon)
        assertEquals(150_000L, s.totalExpenseWon)
    }

    @Test
    fun `연료비와 공동경비를 합쳐 n-1번 이하의 송금으로 정리한다`() {
        val s = JourneyCalculator.summarize(gangneung(), seoul)
        val bal = s.people.associate { it.member.id to it.balanceWon }
        // 나: 낸 돈 18,000 - 연료 몫 7,000 - 경비 몫 50,000 = -39,000
        assertEquals(-39_000L, bal["driver"])
        assertEquals(66_000L, bal["minji"])   // 120,000 - 4,000 - 50,000
        assertEquals(-27_000L, bal["junho"])  // 30,000 - 7,000 - 50,000
        assertEquals(2, s.transfers.size)     // 3명이면 최대 2번
        assertTrue(s.transfers.contains(Transfer(me, minji, 39_000)))
        assertTrue(s.transfers.contains(Transfer(junho, minji, 27_000)))
    }

    @Test
    fun `참여자를 지정한 경비는 그 사람들끼리만 나눈다`() {
        val j = gangneung().withExpense(SharedExpense("x3", "입장료", 20_000, "driver", setOf("driver", "junho"), 0))
        val s = JourneyCalculator.summarize(j, seoul)
        val p = s.people.associateBy { it.member.id }
        assertEquals(50_000L, p.getValue("minji").expenseShareWon)
        assertEquals(60_000L, p.getValue("junho").expenseShareWon)
        assertEquals(0L, s.people.sumOf { it.balanceWon })
    }

    @Test
    fun `송금 목록을 모두 실행하면 모두의 잔액이 정확히 0이 된다`() {
        val s = JourneyCalculator.summarize(gangneung(), seoul)
        val net = s.people.associate { it.member.id to it.balanceWon }.toMutableMap()
        s.transfers.forEach { tr ->
            net[tr.from.id] = net.getValue(tr.from.id) + tr.amountWon
            net[tr.to.id] = net.getValue(tr.to.id) - tr.amountWon
        }
        assertTrue(net.values.all { it == 0L })
    }

    @Test
    fun `여행 저장 형식은 그대로 복원된다`() {
        val j = gangneung().withMember(Member("guest|1", "손님, 이름")).copy(endedAtMillis = 123L)
        assertEquals(j, JourneyCodec.decode(JourneyCodec.encode(j)))
    }

    @Test
    fun `공유 링크는 압축되어 QR 한 장에 들어가고 풀면 원래 내용이 나온다`() {
        val s = JourneyCalculator.summarize(gangneung(), seoul)
        val url = JourneyShare.url("https://example.github.io/gachitan/", s, "@mytoss", meIndex = 1)!!
        assertTrue(url.length <= JourneyShare.MAX_URL_LENGTH)
        assertTrue(url.endsWith("&me=1"))
        val payload = url.substringAfter("#d=").substringBefore("&")
        val json = String(JourneyShare.inflate(JourneyShare.fromBase64Url(payload)), Charsets.UTF_8)
        assertTrue(json.contains("\"t\":\"강릉 2박 3일\""))
        assertTrue(json.contains("\"민지\""))
        assertTrue(json.contains("\"toss\":\"mytoss\""))
    }

    @Test
    fun `일정이 아주 길면 경로 상세를 줄여서라도 QR에 맞춘다`() {
        var j = Journey("big", "전국 일주", millis(2026, 11, 1, 9))
        for (d in 0 until 10) {
            val route = (0..12).map { "아주 긴 장소 이름 $d-$it 근처의 어딘가 주차장" }
            j = j.withTrip(trip(millis(2026, 11, 1 + d, 9), 300.0, listOf(me to 5000L, minji to 5000L), route))
        }
        val s = JourneyCalculator.summarize(j, seoul)
        val url = JourneyShare.url("https://example.github.io/gachitan/", s, null)
        assertTrue(url != null && url.length <= JourneyShare.MAX_URL_LENGTH)
        assertTrue(JourneyShare.text(s).contains("10일차"))
    }
}

class ExpenseCategoryTest {
    @Test
    fun `프리셋 이름과 직접 쓴 이름을 분류한다`() {
        assertEquals(ExpenseCategory.TOLL, ExpenseCategory.of("통행료"))
        assertEquals(ExpenseCategory.TOLL, ExpenseCategory.of("서울-강릉 톨비"))
        assertEquals(ExpenseCategory.FOOD, ExpenseCategory.of("점심 막국수"))
        assertEquals(ExpenseCategory.LODGING, ExpenseCategory.of("펜션 1박"))
        assertEquals(ExpenseCategory.OTHER, ExpenseCategory.of("우산"))
        assertTrue(ExpenseCategory.TOLL.rideBound)
        assertTrue(!ExpenseCategory.FOOD.rideBound)
    }

    @Test
    fun `분류별 합계와 한 사람 여부`() {
        val j = Journey("j", "주말", 0L).withMember(Member("driver", "나"))
            .withExpense(SharedExpense("1", "식비", 15_000, "driver", emptySet(), 1))
            .withExpense(SharedExpense("2", "저녁", 30_000, "driver", emptySet(), 2))
            .withExpense(SharedExpense("3", "입장료", 22_200, "driver", emptySet(), 3))
        val s = JourneyCalculator.summarize(j, java.time.ZoneId.of("Asia/Seoul"))
        assertEquals(listOf(ExpenseCategory.FOOD, ExpenseCategory.ADMISSION), s.expenseByCategory.map { it.category })
        assertEquals(45_000L, s.expenseByCategory.first().amountWon)
        assertEquals(2, s.expenseByCategory.first().count)
        assertTrue(s.onlyOnePerson)
        val two = JourneyCalculator.summarize(j.withMember(Member("m", "민지")), java.time.ZoneId.of("Asia/Seoul"))
        assertTrue(!two.onlyOnePerson)
    }
}

class ViewerUrlTest {
    @Test
    fun `뷰어 주소는 https만 받는다`() {
        assertEquals("https://example.github.io/gachitan/", JourneyShare.safeBase(" https://example.github.io/gachitan/#old "))
        assertEquals(null, JourneyShare.safeBase("http://example.com"))
        assertEquals(null, JourneyShare.safeBase("javascript:alert(1)"))
        assertEquals(null, JourneyShare.safeBase("https://"))
        assertEquals(null, JourneyShare.safeBase(""))
    }
}
