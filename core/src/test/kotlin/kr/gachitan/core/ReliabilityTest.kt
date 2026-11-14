package kr.gachitan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

/**
 * 이전 검토에서 지적된 정산 신뢰성 문제의 회귀 테스트.
 * 기본 단가: 5km/kWh, 300원/kWh → km당 60원.
 */
class ReliabilityTest {
    private val driver = Rider("driver", "나")
    private val minji = Rider("minji", "민지")
    private val junho = Rider("junho", "준호")
    private val ev = PricingConfig(EnergyKind.ELECTRIC, 300.0, 70.0, 5.0, driverPaysShare = true, roundingUnitWon = 10)

    private var t = 0L
    private fun TripRecorder.gear(g: Gear) = onEvent(VehicleEvent.GearChanged(g, ++t))
    private fun TripRecorder.km(v: Double) = onEvent(VehicleEvent.OdometerChanged(v, ++t))
    private fun TripRecorder.seat(s: SeatPosition, on: Boolean) = onEvent(VehicleEvent.SeatOccupancyChanged(s, on, ++t))
    private fun TripRecorder.soc(p: Double) = onEvent(VehicleEvent.EnergyLevelChanged(EnergyKind.ELECTRIC, p, ++t))
    private fun TripRecorder.eff(v: Double) = onEvent(VehicleEvent.EfficiencyReported(EnergyKind.ELECTRIC, v, ++t))
    private fun Settlement.km(r: Rider) = shares.first { it.rider.id == r.id }.distanceKm
    private fun Settlement.won(r: Rider) = shares.first { it.rider.id == r.id }.amountWon

    @Test
    fun `같은 좌석에 다른 사람이 타도 앞사람의 기록은 바뀌지 않는다`() {
        val rec = TripRecorder(driver).apply {
            assign(SeatPosition.FRONT_PASSENGER, minji)
            km(0.0); seat(SeatPosition.FRONT_PASSENGER, true); gear(Gear.DRIVE)
            km(30.0); gear(Gear.PARK)
            seat(SeatPosition.FRONT_PASSENGER, false)       // 민지 하차
            seat(SeatPosition.FRONT_PASSENGER, true)        // 다른 사람 탑승
        }
        // 센서는 사람을 모르므로 앞사람 이름을 '확인 필요'로 제안만 합니다.
        val reboarded = rec.openSittings().getValue(SeatPosition.FRONT_PASSENGER)
        assertEquals(minji, reboarded.rider)
        assertTrue(reboarded.needsReview)

        rec.nameSitting(reboarded.id, junho)                 // 정차 중 확인: 준호였다
        rec.gear(Gear.DRIVE); rec.km(80.0); rec.gear(Gear.PARK)
        val s = SettlementCalculator.settle(rec.finish(), ev)
        assertEquals(30.0, s.km(minji), 1e-9)
        assertEquals(50.0, s.km(junho), 1e-9)
        assertEquals(900L, s.won(minji))     // 30km×60=1,800 ÷2
        assertEquals(1500L, s.won(junho))    // 50km×60=3,000 ÷2
    }

    @Test
    fun `비어 있는 좌석에 붙인 이름은 다음 한 번만 쓰이고 출발 후 새로 탄 사람은 이름 없이 시작한다`() {
        val rec = TripRecorder(driver).apply {
            assign(SeatPosition.REAR_LEFT, minji)
            km(0.0); seat(SeatPosition.REAR_LEFT, true); gear(Gear.DRIVE)
            km(20.0); gear(Gear.PARK); seat(SeatPosition.REAR_LEFT, false)
            gear(Gear.DRIVE); km(25.0); gear(Gear.PARK)
            seat(SeatPosition.REAR_RIGHT, true)              // 처음 보는 좌석에 새 사람
        }
        val newcomer = rec.openSittings().getValue(SeatPosition.REAR_RIGHT)
        assertNull(newcomer.rider)
        assertFalse(newcomer.needsReview)
        assertEquals("뒷좌석 오른쪽 동승자", newcomer.displayName)
    }

    @Test
    fun `한 사람 이름을 두 좌석에 동시에 붙이면 먼저 붙인 쪽은 확인 필요가 된다`() {
        val rec = TripRecorder(driver).apply {
            km(0.0); seat(SeatPosition.REAR_LEFT, true); seat(SeatPosition.REAR_RIGHT, true)
            assign(SeatPosition.REAR_LEFT, minji)
            assign(SeatPosition.REAR_RIGHT, minji)
        }
        val open = rec.openSittings()
        assertNull(open.getValue(SeatPosition.REAR_LEFT).rider)
        assertTrue(open.getValue(SeatPosition.REAR_LEFT).needsReview)
        assertEquals(minji, open.getValue(SeatPosition.REAR_RIGHT).rider)
    }

    @Test
    fun `정차 후 입력한 통행료는 방금 달려온 구간의 탑승자가 나눈다`() {
        val rec = TripRecorder(driver).apply {
            assign(SeatPosition.FRONT_PASSENGER, minji)
            km(0.0); seat(SeatPosition.FRONT_PASSENGER, true); gear(Gear.DRIVE)
            km(50.0); gear(Gear.PARK)
            seat(SeatPosition.FRONT_PASSENGER, false)        // 톨게이트를 지나 도착, 민지가 먼저 내림
            addExtraCost("통행료", 3000)                      // 그다음에 입력
        }
        val s = SettlementCalculator.settle(rec.finish(), ev)
        assertEquals(1500L + 1500L, s.won(minji))           // 연료 3,000÷2 + 통행료 3,000÷2
        assertEquals(6000L, s.grandTotalWon)
        assertEquals(3000L, s.extrasWon)
    }

    @Test
    fun `통행료 분담자를 직접 고를 수 있고 나중에 붙인 이름도 따라간다`() {
        val rec = TripRecorder(driver).apply {
            km(0.0); seat(SeatPosition.REAR_LEFT, true); gear(Gear.DRIVE); km(10.0); gear(Gear.PARK)
        }
        val sittingId = rec.openSittings().getValue(SeatPosition.REAR_LEFT).id
        rec.addExtraCost("주차비", 2000, setOf(sittingId))   // 동승자만 부담
        rec.nameSitting(sittingId, junho)                    // 이름은 나중에 붙임
        val s = SettlementCalculator.settle(rec.finish(), ev.copy(driverPaysShare = true))
        assertEquals(300L + 2000L, s.won(junho))              // 연료 600÷2 + 주차비 전액
    }

    @Test
    fun `효율이 다시 보고되면 그 뒤 거리부터 새 값을 쓴다`() {
        val rec = TripRecorder(driver).apply {
            km(0.0); eff(5.0); gear(Gear.DRIVE)
            km(50.0); eff(10.0)
            km(100.0); gear(Gear.PARK)
        }
        val cost = CostCalculator.tripCost(rec.finish(), ev)
        assertEquals(CostBasis.VEHICLE_EFFICIENCY, cost.basis)
        assertEquals(15.0, cost.unitsUsed, 1e-9)             // 50÷5 + 50÷10
        assertEquals(4500.0, cost.totalWon, 1e-6)
    }

    @Test
    fun `중간 충전이 있어도 감소한 양만 더해 계산한다`() {
        val rec = TripRecorder(driver).apply {
            km(0.0); soc(80.0); gear(Gear.DRIVE)
            km(70.0); soc(60.0); gear(Gear.PARK)
            soc(90.0)                                        // 휴게소 충전
            gear(Gear.DRIVE); km(140.0); soc(70.0); gear(Gear.PARK)
        }
        val cost = CostCalculator.tripCost(rec.finish(), ev)
        assertEquals(CostBasis.MEASURED_ENERGY_USE, cost.basis)
        assertTrue(cost.rechargedDuringTrip)
        assertEquals(28.0, cost.unitsUsed, 1e-9)             // (20+20)% × 70kWh
    }

    @Test
    fun `앱이 꺼졌다 켜진 동안은 기록 공백으로 남긴다`() {
        val journal = TripJournal(driver)
        fun e(ev: VehicleEvent) = journal.apply(TripCommand.Event(ev))
        e(VehicleEvent.OdometerChanged(0.0, 1_000)); e(VehicleEvent.GearChanged(Gear.DRIVE, 2_000))
        e(VehicleEvent.OdometerChanged(10.0, 3_000))
        journal.apply(TripCommand.Resumed(600_000))
        val restored = TripJournal.decode(journal.encode())
        val gaps = restored.recorder.gaps()
        assertEquals(1, gaps.size)
        assertEquals(3_000L, gaps[0].fromMillis)
        assertEquals(600_000L, gaps[0].toMillis)
    }

    @Test
    fun `앉은 기간 이름 붙이기와 통행료 분담자도 저장·복원된다`() {
        val journal = TripJournal(driver)
        fun e(ev: VehicleEvent) = journal.apply(TripCommand.Event(ev))
        e(VehicleEvent.OdometerChanged(0.0, 1)); e(VehicleEvent.SeatOccupancyChanged(SeatPosition.REAR_LEFT, true, 2))
        e(VehicleEvent.GearChanged(Gear.DRIVE, 3)); e(VehicleEvent.OdometerChanged(40.0, 4)); e(VehicleEvent.GearChanged(Gear.PARK, 5))
        val sid = journal.recorder.openSittings().getValue(SeatPosition.REAR_LEFT).id
        journal.apply(TripCommand.AddExtra("통행료, 왕복", 2000, setOf(sid)))
        journal.apply(TripCommand.NameSitting(sid, Rider("m,1", "민지|A")))
        val a = SettlementCalculator.settle(journal.recorder.preview()!!, ev)
        val b = SettlementCalculator.settle(TripJournal.decode(journal.encode()).recorder.finish(), ev)
        assertEquals(a.shares.map { it.rider to it.amountWon }, b.shares.map { it.rider to it.amountWon })
        assertEquals(2000.0, b.shares.first { it.rider.id == "m,1" }.extrasWon, 1e-9)
    }

    @Test
    fun `묶음의 송금 목록과 사람별 잔액은 반올림 후에도 정확히 일치한다`() {
        val rnd = Random(7)
        repeat(300) {
            val n = rnd.nextInt(2, 7)
            val ids = (0 until n).map { "p$it" }
            var j = Journey("j", "테스트", 0)
            repeat(rnd.nextInt(1, 5)) { k ->
                val shares = ids.shuffled(rnd).take(rnd.nextInt(1, n + 1))
                    .map { ShareEntry(it, it, 10.0, rnd.nextLong(1, 9_999)) }
                j = j.withTrip(TripEntry(k * 1000L, k * 1000L + 1, 10.0, shares.sumOf { it.amountWon },
                    CostBasis.USER_EFFICIENCY, ids[0], "출발", emptyList(), shares))
            }
            repeat(rnd.nextInt(0, 4)) { k ->
                j = j.withExpense(SharedExpense("x$k", "경비", rnd.nextLong(1_001, 300_001), ids.random(rnd),
                    ids.shuffled(rnd).take(rnd.nextInt(1, n + 1)).toSet(), 0))
            }
            val unit = listOf(1L, 10L, 100L).random(rnd)
            val s = JourneyCalculator.summarize(j, ZoneId.of("Asia/Seoul"), unit)
            assertEquals(0L, s.people.sumOf { it.balanceWon })
            s.people.forEach { p ->
                val net = s.transfers.filter { it.to.id == p.member.id }.sumOf { it.amountWon } -
                    s.transfers.filter { it.from.id == p.member.id }.sumOf { it.amountWon }
                assertEquals("잔액과 송금 합계 불일치: ${p.member.id}", p.balanceWon, net)
            }
            assertTrue(s.transfers.size <= s.people.count { it.balanceWon != 0L }.coerceAtLeast(1) - 1)
            assertTrue(s.transfers.all { it.amountWon % unit == 0L || it.to.id == s.people.first().member.id || it.from.id == s.people.first().member.id })
        }
    }

    @Test
    fun `개인용 공유에는 그 사람 몫과 관련 송금만 담긴다`() {
        val day = 1_800_000_000_000L
        val j = Journey("j", "강릉", day)
            .withTrip(TripEntry(day, day + 1, 100.0, 9000, CostBasis.USER_EFFICIENCY, "driver", "서울",
                emptyList(), listOf(ShareEntry("driver", "나", 100.0, 3000), ShareEntry("minji", "민지", 100.0, 3000),
                    ShareEntry("junho", "준호", 100.0, 3000))))
            .withExpense(SharedExpense("x", "숙박", 90_000, "junho", emptySet(), day))
        val s = JourneyCalculator.summarize(j, ZoneId.of("Asia/Seoul"))
        val json = JourneyShare.personalJson(s, "minji", "mytoss", "driver")!!
        assertTrue(json.contains("\"n\":\"민지\""))
        assertFalse("다른 사람의 이름은 송금 상대가 아니면 담기지 않아야 함", json.contains("\"나\""))
        assertFalse(json.contains("서울"))                    // 장소 이름 없음
        val text = JourneyShare.personalText(s, "minji")!!
        assertTrue(text.contains("준호님께"))
        assertFalse(text.contains("나님"))
    }
}

