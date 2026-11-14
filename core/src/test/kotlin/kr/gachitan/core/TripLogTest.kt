package kr.gachitan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기록 화면이 쓰는 TripLog: 정산 값을 그대로 옮기는지, 저장·복원해도 같은지. */
class TripLogTest {
    private val driver = Rider("driver", "나")
    private val junho = Rider("junho", "준호")
    private val minji = Rider("minji", "민지")
    private val ev = PricingConfig(EnergyKind.ELECTRIC, 300.0, 70.0, 5.0, driverPaysShare = true, roundingUnitWon = 10)
    private var t = 0L

    private fun sample(): Settlement {
        val r = TripRecorder(driver).apply {
            assign(SeatPosition.FRONT_PASSENGER, junho); assign(SeatPosition.REAR_LEFT, minji)
            onEvent(VehicleEvent.OdometerChanged(100.0, ++t))
            onEvent(VehicleEvent.SeatOccupancyChanged(SeatPosition.FRONT_PASSENGER, true, ++t))
            onEvent(VehicleEvent.SeatOccupancyChanged(SeatPosition.REAR_LEFT, true, ++t))
            onEvent(VehicleEvent.GearChanged(Gear.DRIVE, ++t))
            onEvent(VehicleEvent.OdometerChanged(130.0, ++t))
            onEvent(VehicleEvent.GearChanged(Gear.PARK, ++t))
            onEvent(VehicleEvent.SeatOccupancyChanged(SeatPosition.REAR_LEFT, false, ++t))
            onEvent(VehicleEvent.GearChanged(Gear.DRIVE, ++t))
            onEvent(VehicleEvent.OdometerChanged(180.0, ++t))
            onEvent(VehicleEvent.GearChanged(Gear.PARK, ++t))
            addExtraCost("통행료", 1_000)
        }
        return SettlementCalculator.settle(r.finish(), ev)
    }

    @Test
    fun `정산 값을 그대로 옮긴다`() {
        val s = sample()
        val log = TripLog.from(s, "a", null)
        assertEquals(s.grandTotalWon, log.totalWon)
        assertEquals(s.shares.map { it.amountWon }, log.shares.map { it.amountWon })
        assertEquals(80.0, log.distanceKm, 1e-9)
        assertEquals(60.0, log.wonPerKm, 1e-9)
        assertTrue(log.driverIncluded)
        assertEquals(listOf("준호", "민지").sorted(), log.payers.map { it.name }.sorted())
    }

    @Test
    fun `구간 막대는 거리 비율과 인원, 바뀐 사람을 보여 준다`() {
        val bands = TripLog.from(sample(), "a", null).bands()
        assertEquals(2, bands.size)
        assertEquals(30.0, bands[0].km, 1e-9); assertEquals(3, bands[0].people)
        assertEquals(50.0, bands[1].km, 1e-9); assertEquals(2, bands[1].people)
        assertNull(bands[0].changeBefore)
        assertEquals("민지 하차", bands[1].changeBefore)
        assertEquals(3 to 2, TripLog.from(sample(), "a", null).peopleChange())
    }

    @Test
    fun `저장했다 복원해도 같다`() {
        val a = TripLog.from(sample(), "a|b,c%", "강릉, 2박|3일")
        val b = TripLog.from(sample(), "second", null)
        val restored = TripLogCodec.decode(TripLogCodec.encode(listOf(a, b)))
        assertEquals(2, restored.size)
        assertEquals(a.id, restored[0].id)
        assertEquals(a.bundleTitle, restored[0].bundleTitle)
        assertEquals(a.shares, restored[0].shares)
        assertEquals(a.extras, restored[0].extras)
        assertEquals(a.bands(), restored[0].bands())
        assertEquals(a.totalWon, restored[0].totalWon)
        assertNull(restored[1].bundleTitle)
    }

    @Test
    fun `깨진 기록은 건너뛰고 나머지를 지킨다`() {
        val good = TripLogCodec.encode(listOf(TripLog.from(sample(), "ok", null)))
        val broken = good.replaceFirst("T|ok|", "T|broken|x") + good.lines().drop(1).joinToString("\n")
        assertEquals(listOf("ok"), TripLogCodec.decode(broken).map { it.id })
        assertTrue(TripLogCodec.decode("이전 형식의 문장 기록").isEmpty())
    }
}
