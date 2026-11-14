package kr.gachitan.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kr.gachitan.core.Gear
import kr.gachitan.core.SeatPosition.FRONT_PASSENGER
import kr.gachitan.core.SeatPosition.REAR_LEFT
import kr.gachitan.core.SeatPosition.REAR_RIGHT
import kr.gachitan.data.AppSettings
import kr.gachitan.data.InMemorySettings
import kr.gachitan.data.InMemoryStore
import kr.gachitan.session.SessionState.Phase
import kr.gachitan.vehicle.DemoVehicleSignals
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 대표 시나리오를 세션 단위로 끝까지 돌립니다(시뮬레이터 입력 → 기록 → 정차 확인 → 정산 → 묶음).
 * Dispatchers.Unconfined라서 명령과 이벤트가 호출한 자리에서 바로 처리됩니다.
 * 단가: 350원/kWh, 5km/kWh → km당 70원.
 */
class TripSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var now = 1_800_000_000_000L
    private val clock = { now }
    private val store = InMemoryStore()
    private val settings = InMemorySettings(AppSettings(driverName = "지훈"))

    private fun session(demo: DemoVehicleSignals, ns: String = "demo-") =
        TripSession(scope, demo, store, settings, ns, ZoneId.of("Asia/Seoul"), clock)

    @After fun tearDown() = scope.cancel()

    private val TripSession.s get() = state.value

    @Test
    fun `연결 직후 받은 현재 값을 버리지 않는다`() {
        val demo = DemoVehicleSignals(clock)
        val session = session(demo)
        session.start()
        assertTrue("시뮬레이터가 start에서 보낸 계기판 값을 받아야 함", session.s.odometerKnown)
        assertEquals(Phase.READY, session.s.phase)
    }

    @Test
    fun `퇴근 카풀 - 중간 하차와 새 탑승, 정차 후 확인, 통행료까지 정산한다`() {
        val demo = DemoVehicleSignals(clock)
        val session = session(demo)
        session.start()

        demo.toggleSeat(FRONT_PASSENGER); demo.toggleSeat(REAR_LEFT)
        session.assign(FRONT_PASSENGER, "민지"); session.assign(REAR_LEFT, "준호")
        demo.setGear(Gear.DRIVE)
        assertEquals(Phase.DRIVING, session.s.phase)
        demo.drive(12.0)
        demo.setGear(Gear.PARK)                                  // 정자역
        assertEquals(Phase.PARKED, session.s.phase)
        demo.toggleSeat(REAR_LEFT)                               // 준호 하차
        demo.toggleSeat(REAR_RIGHT)                              // 처음 보는 사람 탑승
        val newcomer = session.s.seats.first { it.seat == REAR_RIGHT }
        assertTrue(newcomer.occupied); assertNull(newcomer.name)

        demo.setGear(Gear.DRIVE); demo.drive(18.0); demo.setGear(Gear.PARK)
        session.addExtra("통행료", 1_200, emptySet())            // 기본값: 방금 달려온 구간의 탑승자
        session.beginReview()
        assertEquals(Phase.REVIEW, session.s.phase)
        val unnamed = session.s.sittings.first { it.seat == REAR_RIGHT }
        session.nameSitting(unnamed.id, "서연")

        val preview = session.s.preview!!
        assertEquals(30.0, preview.record.distanceKm, 1e-9)
        session.confirmSettlement()
        val settled = session.s.settled!!
        assertEquals(Phase.SETTLED, session.s.phase)
        assertFalse(settled.inBundle)
        val won = settled.settlement.shares.associate { it.rider.name to it.amountWon }
        // 구간1 12km×70=840원 ÷3=280 / 구간2 18km×70=1,260원 ÷3=420 / 통행료 1,200 ÷3=400
        assertEquals(280L + 420L + 400L, won["민지"])
        assertEquals(280L, won["준호"])
        assertEquals(420L + 400L, won["서연"])
        assertEquals(840L + 1_260L + 1_200L, settled.settlement.grandTotalWon)

        // 기록 탭: 확정한 운행이 구조화된 기록으로 남고, 화면 값과 정산 값이 같다
        val log = session.s.trips.single()
        assertEquals(settled.log, log)
        assertEquals(settled.settlement.grandTotalWon, log.totalWon)
        assertEquals(won, log.shares.associate { it.name to it.amountWon })
        assertEquals(listOf(12.0, 18.0), log.bands().map { it.km })
        assertEquals("준호 하차 · 서연 탑승", log.bands()[1].changeBefore)
        // 앱을 다시 켜도 기록이 그대로 있다
        val reopened = session(DemoVehicleSignals(clock)).also { it.start() }
        assertEquals(listOf(log.id), reopened.s.trips.map { it.id })
        assertEquals(log.totalWon, reopened.s.trips.single().totalWon)
        // 기록 지우기
        session.clearHistory()
        assertTrue(session.s.trips.isEmpty())
    }

    @Test
    fun `정산 확인 중 다시 출발하면 확인이 취소되고 같은 운행이 이어진다`() {
        val demo = DemoVehicleSignals(clock)
        val session = session(demo)
        session.start()
        demo.setGear(Gear.DRIVE); demo.drive(5.0); demo.setGear(Gear.PARK)
        session.beginReview()
        assertEquals(Phase.REVIEW, session.s.phase)
        demo.setGear(Gear.DRIVE)
        assertEquals(Phase.DRIVING, session.s.phase)
        demo.drive(5.0); demo.setGear(Gear.PARK)
        assertEquals(10.0, session.s.liveKm, 1e-9)
    }

    @Test
    fun `묶음 중 운행은 송금 요청 없이 장부에만 쌓이고 정차 중 운행도 빠짐없이 포함된다`() {
        val demo = DemoVehicleSignals(clock)
        val session = session(demo)
        session.start()
        session.startJourney("강릉 여행")
        demo.toggleSeat(FRONT_PASSENGER); session.assign(FRONT_PASSENGER, "민지")
        demo.setGear(Gear.DRIVE); demo.drive(100.0); demo.setGear(Gear.PARK)
        session.beginReview(); session.confirmSettlement()
        assertTrue("묶음에 들어간 운행은 운행별 QR을 만들지 않음", session.s.settled!!.inBundle)

        // 다음 날: 정차 상태에서 바로 묶음을 끝냄 → 이 운행도 정산되어 포함돼야 함
        demo.nextDay()
        demo.setGear(Gear.DRIVE); demo.drive(50.0); demo.setGear(Gear.PARK)
        assertEquals(2, session.s.currentDayNumber)
        session.endJourney()
        val ended = session.s.endedJourney!!
        assertEquals(2, ended.journey.trips.size)
        assertEquals(150.0, ended.totalKm, 1e-9)
        val minji = ended.people.first { it.member.name == "민지" }
        assertEquals(-ended.transfers.sumOf { it.amountWon }, minji.balanceWon)
        assertNull(session.s.journey)
    }

    @Test
    fun `데모 기록과 실차 기록은 서로 다른 파일에 저장된다`() {
        val real = session(DemoVehicleSignals(clock), ns = "")
        val demoSignals = DemoVehicleSignals(clock)
        val demo = session(demoSignals, ns = "demo-")
        real.start(); demo.start()
        demoSignals.setGear(Gear.DRIVE); demoSignals.drive(40.0)
        assertEquals(40.0, demo.s.liveKm, 1e-9)
        assertEquals(0.0, real.s.liveKm, 1e-9)
        assertNotNull(store.read("demo-journal.enc"))
        assertFalse(store.read("journal.enc")!!.contains("12040"))
    }

    @Test
    fun `앱이 다시 시작되면 운행을 이어 가고 꺼져 있던 동안을 공백으로 알린다`() {
        val demo1 = DemoVehicleSignals(clock)
        val first = session(demo1)
        first.start()
        demo1.setGear(Gear.DRIVE); demo1.drive(20.0)
        first.stop()

        // 시뮬레이터는 20km를 20분 주행으로 기록하므로 그보다 뒤에 다시 켭니다.
        now += 40 * 60_000L
        val demo2 = DemoVehicleSignals(clock)
        val second = session(demo2)
        demo2.continueFrom(second.lastOdometerKm())
        second.start()
        assertEquals(20.0, second.s.liveKm, 1e-9)
        assertEquals(1, second.s.gaps.size)
    }

    @Test
    fun `TESTING 시나리오 1의 기대 금액`() {
        val demo = DemoVehicleSignals(clock)
        val session = session(demo)
        session.start()
        demo.toggleSeat(FRONT_PASSENGER); demo.toggleSeat(REAR_LEFT)
        session.assign(FRONT_PASSENGER, "민지"); session.assign(REAR_LEFT, "준호")
        demo.setGear(Gear.DRIVE); demo.drive(20.0)
        demo.setGear(Gear.PARK); demo.toggleSeat(REAR_LEFT); demo.toggleSeat(REAR_RIGHT)
        demo.setGear(Gear.DRIVE); demo.drive(20.0); demo.setGear(Gear.PARK)
        session.addExtra("통행료", 1_200, emptySet())
        session.beginReview()
        session.nameSitting(session.s.sittings.first { it.seat == REAR_RIGHT }.id, "서연")
        val p = session.s.preview!!
        assertEquals(kr.gachitan.core.CostBasis.MEASURED_ENERGY_USE, p.tripCost.basis)
        val won = p.shares.associate { it.rider.name to it.amountWon }
        assertEquals(mapOf("지훈" to 1_330L, "민지" to 1_330L, "준호" to 470L, "서연" to 870L), won)
        assertEquals(4_000L, p.grandTotalWon)
    }

    @Test
    fun `움직이지 않은 빈 운행은 버릴 수 있고 기록에 남지 않는다`() {
        val demo = DemoVehicleSignals(clock)
        val session = session(demo)
        session.start()
        demo.toggleSeat(FRONT_PASSENGER); session.assign(FRONT_PASSENGER, "민지")
        demo.setGear(Gear.DRIVE); demo.setGear(Gear.PARK)
        assertEquals(Phase.PARKED, session.s.phase)
        session.discardTrip()
        assertEquals(Phase.READY, session.s.phase)
        assertTrue(session.s.trips.isEmpty())
        // 앉아 있던 사람 이름은 이어집니다.
        assertEquals("민지", session.s.seats.first { it.seat == FRONT_PASSENGER }.name)
    }

    @Test
    fun `받음 표시는 기록에 저장되고 다시 열어도 남는다`() {
        val demo = DemoVehicleSignals(clock)
        val session = session(demo)
        session.start()
        demo.toggleSeat(FRONT_PASSENGER); session.assign(FRONT_PASSENGER, "민지")
        demo.setGear(Gear.DRIVE); demo.drive(20.0); demo.setGear(Gear.PARK)
        session.beginReview(); session.confirmSettlement()
        val log = session.s.trips.single()
        val minji = log.payers.single()
        session.setPaid(log.id, minji.riderId, true)
        assertTrue(session.s.trips.single().allPaid)
        session.setPaidByName(setOf(log.id), "민지", false)
        assertEquals(minji.amountWon, session.s.trips.single().unpaidWon)
        session.setPaid(log.id, minji.riderId, true)
        val reopened = session(DemoVehicleSignals(clock)).also { it.start() }
        assertTrue(reopened.s.trips.single().allPaid)
    }

    @Test
    fun `세 시간 넘게 세워 둔 뒤 출발하면 앞 운행을 확정하고 새 운행을 시작한다`() {
        val demo = DemoVehicleSignals(clock)
        val session = session(demo)
        session.start()
        demo.toggleSeat(FRONT_PASSENGER); session.assign(FRONT_PASSENGER, "민지")
        demo.setGear(Gear.DRIVE); demo.drive(15.0); demo.setGear(Gear.PARK)   // 저녁 퇴근, 정산 안 누름
        demo.toggleSeat(FRONT_PASSENGER)                                          // 민지 내림
        now += 10 * 60 * 60 * 1000L                                               // 다음 날 아침
        demo.toggleSeat(REAR_LEFT); session.assign(REAR_LEFT, "준호")
        demo.setGear(Gear.DRIVE); demo.drive(10.0)
        val evening = session.s.trips.single()
        assertEquals(15.0, evening.distanceKm, 1e-9)
        assertEquals(listOf("민지"), evening.payers.map { it.name })
        assertEquals(10.0, session.s.liveKm, 1e-9)
        assertEquals(1, session.s.autoSettledCount)
    }

    @Test
    fun `잠깐 세웠다 출발하면 같은 운행이 이어진다`() {
        val demo = DemoVehicleSignals(clock)
        val session = session(demo)
        session.start()
        demo.setGear(Gear.DRIVE); demo.drive(5.0); demo.setGear(Gear.PARK)
        now += 30 * 60 * 1000L
        demo.setGear(Gear.DRIVE); demo.drive(5.0)
        assertTrue(session.s.trips.isEmpty())
        assertEquals(10.0, session.s.liveKm, 1e-9)
    }
}
