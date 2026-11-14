package kr.gachitan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 정산 엔진 검증. 숫자는 손으로 계산한 기대값과 비교합니다.
 * 기본 단가: 전비 5km/kWh, 300원/kWh → km당 60원.
 */
class SettlementTest {

    private val driver = Rider("driver", "나")
    private val minji = Rider("minji", "민지")
    private val junho = Rider("junho", "준호")
    private val seoyeon = Rider("seoyeon", "서연")

    private val ev = PricingConfig(
        energyKind = EnergyKind.ELECTRIC,
        unitPriceWon = 300.0,
        capacityUnits = 70.0,
        fallbackKmPerUnit = 5.0,
        driverPaysShare = true,
        roundingUnitWon = 10
    )

    private var t = 0L
    private fun TripRecorder.gear(g: Gear) = onEvent(VehicleEvent.GearChanged(g, ++t))
    private fun TripRecorder.km(v: Double) = onEvent(VehicleEvent.OdometerChanged(v, ++t))
    private fun TripRecorder.seat(s: SeatPosition, on: Boolean) =
        onEvent(VehicleEvent.SeatOccupancyChanged(s, on, ++t))
    private fun TripRecorder.soc(p: Double) =
        onEvent(VehicleEvent.EnergyLevelChanged(EnergyKind.ELECTRIC, p, ++t))

    private fun fourFriends(): TripRecorder = TripRecorder(driver).apply {
        assign(SeatPosition.FRONT_PASSENGER, junho)
        assign(SeatPosition.REAR_LEFT, minji)
        assign(SeatPosition.REAR_RIGHT, seoyeon)
        km(1000.0)
        seat(SeatPosition.FRONT_PASSENGER, true)
        seat(SeatPosition.REAR_LEFT, true)
        seat(SeatPosition.REAR_RIGHT, true)
        gear(Gear.DRIVE)
    }

    private fun Settlement.amountOf(r: Rider) = shares.first { it.rider.id == r.id }.amountWon
    private fun Settlement.kmOf(r: Rider) = shares.first { it.rider.id == r.id }.distanceKm

    @Test
    fun `중간에 내린 사람은 탄 구간만 낸다`() {
        val rec = fourFriends().apply {
            km(1030.0)
            gear(Gear.PARK)                       // 민지를 내려주려고 정차
            seat(SeatPosition.REAR_LEFT, false)
            gear(Gear.DRIVE)
            km(1080.0)
            gear(Gear.PARK)
        }
        val s = SettlementCalculator.settle(rec.finish(), ev)

        // 구간A 30km×60원=1,800원 ÷4 = 450원, 구간B 50km×60원=3,000원 ÷3 = 1,000원
        assertEquals(450L, s.amountOf(minji))
        assertEquals(1450L, s.amountOf(junho))
        assertEquals(1450L, s.amountOf(seoyeon))
        assertEquals(1450L, s.amountOf(driver))
        assertEquals(4800L, s.grandTotalWon)
        assertEquals(30.0, s.kmOf(minji), 1e-9)
        assertEquals(80.0, s.kmOf(junho), 1e-9)
        assertEquals("한 운행으로 유지", 2, s.record.segments.size)
    }

    @Test
    fun `운전자 제외 옵션이면 동승자끼리만 나눈다`() {
        val rec = fourFriends().apply { km(1060.0); gear(Gear.PARK) }
        val s = SettlementCalculator.settle(rec.finish(), ev.copy(driverPaysShare = false))
        // 60km×60원=3,600원 ÷3명 = 1,200원
        assertEquals(1200L, s.amountOf(minji))
        assertEquals(0L, s.amountOf(driver))
        assertEquals(3600L, s.grandTotalWon)
    }

    @Test
    fun `모두 내린 뒤 운전자 혼자 달린 구간은 운전자 몫이다`() {
        val rec = TripRecorder(driver).apply {
            assign(SeatPosition.FRONT_PASSENGER, junho)
            km(0.0); seat(SeatPosition.FRONT_PASSENGER, true); gear(Gear.DRIVE)
            km(10.0); seat(SeatPosition.FRONT_PASSENGER, false)
            km(20.0); gear(Gear.PARK)
        }
        val s = SettlementCalculator.settle(rec.finish(), ev.copy(driverPaysShare = false))
        // 첫 10km(600원)는 준호 혼자, 뒤 10km(600원)는 운전자 본인
        assertEquals(600L, s.amountOf(junho))
        assertEquals(600L, s.amountOf(driver))
    }

    @Test
    fun `같은 km 안에서 깜빡인 좌석 신호는 정산에 영향이 없다`() {
        val rec = fourFriends().apply {
            km(1020.0)
            seat(SeatPosition.REAR_LEFT, false)   // 자세를 고쳐 앉느라 순간 비어 보임
            seat(SeatPosition.REAR_LEFT, true)
            km(1040.0); gear(Gear.PARK)
        }
        val s = SettlementCalculator.settle(rec.finish(), ev)
        assertEquals(40.0, s.kmOf(minji), 1e-9)
        assertEquals(600L, s.amountOf(minji)) // 40km×60원=2,400원 ÷4
        assertTrue(s.record.segments.all { it.distanceKm > 0 })
    }

    @Test
    fun `이름을 정하지 않은 좌석은 자동 동승자로 기록되고 나중에 이름을 붙일 수 있다`() {
        val rec = TripRecorder(driver).apply {
            km(0.0); seat(SeatPosition.REAR_RIGHT, true); gear(Gear.DRIVE)
            km(25.0); gear(Gear.PARK)
        }
        assertEquals("뒷좌석 오른쪽 동승자", rec.openSittings()[SeatPosition.REAR_RIGHT]?.displayName)
        rec.assign(SeatPosition.REAR_RIGHT, seoyeon)   // 도착 후 이름 지정
        val s = SettlementCalculator.settle(rec.finish(), ev)
        assertEquals(750L, s.amountOf(seoyeon))        // 25km×60원=1,500원 ÷2
        assertTrue(s.shares.none { it.rider.name.contains("동승자") })
    }

    @Test
    fun `반올림 차액은 운전자가 흡수해 총액이 정확히 보존된다`() {
        val rec = fourFriends().apply { km(1007.0); gear(Gear.PARK) }
        val s = SettlementCalculator.settle(rec.finish(), ev)
        // 7km×60원=420원 ÷4=105원 → 동승자 110원(10원 단위), 운전자 90원
        assertEquals(110L, s.amountOf(minji))
        assertEquals(90L, s.amountOf(driver))
        assertEquals(420L, s.grandTotalWon)
    }

    @Test
    fun `통행료는 그 순간 타고 있던 사람끼리 나눈다`() {
        val rec = fourFriends().apply {
            km(1030.0)
            gear(Gear.PARK); seat(SeatPosition.REAR_LEFT, false); gear(Gear.DRIVE)
            km(1050.0)
            addExtraCost("고속도로 통행료", 3000)   // 민지는 이미 내림
            km(1080.0); gear(Gear.PARK)
        }
        val s = SettlementCalculator.settle(rec.finish(), ev)
        assertEquals(450L, s.amountOf(minji))           // 통행료 없음
        assertEquals(1450L + 1000L, s.amountOf(junho))  // 3,000원 ÷3
        assertEquals(4800L + 3000L, s.grandTotalWon)
    }

    @Test
    fun `차량이 보고한 효율이 있으면 그것을 가장 먼저 쓴다`() {
        val rec = fourFriends().apply {
            onEvent(VehicleEvent.EfficiencyReported(EnergyKind.ELECTRIC, 6.0, ++t))
            soc(80.0); km(1060.0); soc(70.0); gear(Gear.PARK)
        }
        val cost = CostCalculator.tripCost(rec.finish(), ev)
        assertEquals(CostBasis.VEHICLE_EFFICIENCY, cost.basis)
        assertEquals(3000.0, cost.totalWon, 1e-6)       // 60km ÷ 6 = 10kWh × 300원
    }

    @Test
    fun `효율 값이 없으면 실제 배터리 감소량으로 계산한다`() {
        val rec = fourFriends().apply { soc(80.0); km(1060.0); soc(70.0); gear(Gear.PARK) }
        val cost = CostCalculator.tripCost(rec.finish(), ev)
        assertEquals(CostBasis.MEASURED_ENERGY_USE, cost.basis)
        assertEquals(2100.0, cost.totalWon, 1e-6)       // 10% × 70kWh = 7kWh × 300원
    }

    @Test
    fun `충전으로 늘기만 하고 감소량이 작으면 기본 효율로 계산한다`() {
        val rec = fourFriends().apply { soc(40.0); km(1060.0); soc(90.0); gear(Gear.PARK) }
        val cost = CostCalculator.tripCost(rec.finish(), ev)
        assertEquals(CostBasis.USER_EFFICIENCY, cost.basis)
        assertEquals(3600.0, cost.totalWon, 1e-6)
    }

    @Test
    fun `출발할 때 계기판 값을 아직 못 받았으면 첫 값부터 기록한다`() {
        val rec = TripRecorder(driver).apply {
            gear(Gear.DRIVE)
            assertEquals(TripRecorder.State.WAITING_FOR_ODOMETER, state)
            km(500.0)
            assertEquals(TripRecorder.State.DRIVING, state)
            km(510.0); gear(Gear.PARK)
        }
        assertEquals(10.0, rec.finish().distanceKm, 1e-9)
    }

    @Test
    fun `계기판 값이 줄어드는 오류 신호는 무시한다`() {
        val rec = fourFriends().apply { km(1050.0); km(3.0); km(1060.0); gear(Gear.PARK) }
        assertEquals(60.0, rec.finish().distanceKm, 1e-9)
    }

    @Test
    fun `기록장을 저장했다가 복원하면 정산 결과가 똑같다`() {
        val journal = TripJournal(driver)
        fun cmd(c: TripCommand) = journal.apply(c)
        fun event(e: VehicleEvent) = cmd(TripCommand.Event(e))
        cmd(TripCommand.Assign(SeatPosition.REAR_LEFT, Rider("m|1", "민지|이름\n특수")))
        event(VehicleEvent.OdometerChanged(100.0, 1))
        event(VehicleEvent.SeatOccupancyChanged(SeatPosition.REAR_LEFT, true, 2))
        event(VehicleEvent.GearChanged(Gear.DRIVE, 3))
        event(VehicleEvent.OdometerChanged(130.0, 4))
        cmd(TripCommand.AddExtra("주차비", 2000))
        event(VehicleEvent.GearChanged(Gear.PARK, 5))

        val restored = TripJournal.decode(journal.encode())
        val a = SettlementCalculator.settle(journal.recorder.finish(), ev)
        val b = SettlementCalculator.settle(restored.recorder.finish(), ev)
        assertEquals(a.shares.map { it.rider to it.amountWon }, b.shares.map { it.rider to it.amountWon })
        assertEquals("민지|이름\n특수", b.shares.first { !it.isDriver }.rider.name)
    }

    @Test
    fun `손상된 기록 줄은 건너뛰고 나머지를 살린다`() {
        val text = "V1|driver|나\nO|1|100.0\nG|2|DRIVE\n???broken\nO|3|110.0\nG|4|PARK\n"
        val rec = TripJournal.decode(text).recorder
        assertEquals(10.0, rec.finish().distanceKm, 1e-9)
    }

    @Test
    fun `토스 아이디가 있으면 금액이 담긴 송금 링크를 만든다`() {
        val share = RiderShare(minji, 30.0, 450.0, 450, false)
        assertEquals("https://toss.me/gachitan/450", PaymentRequest.payload(share, "나", "@gachitan", null))
        assertTrue(PaymentRequest.payload(share, "나", "잘못된 아이디!", "카카오 3333").contains("계좌: 카카오 3333"))
    }
}
