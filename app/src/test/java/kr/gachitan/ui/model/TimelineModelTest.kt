package kr.gachitan.ui.model

import kr.gachitan.core.EnergyKind
import kr.gachitan.core.Gear
import kr.gachitan.core.PricingConfig
import kr.gachitan.core.Rider
import kr.gachitan.core.SeatPosition
import kr.gachitan.core.SettlementCalculator
import kr.gachitan.core.TripRecorder
import kr.gachitan.core.VehicleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineModelTest {
    @Test
    fun `사람별 막대는 탄 구간 비율과 정산 금액을 그대로 보여 준다`() {
        var t = 0L
        val rec = TripRecorder(Rider("driver", "지훈"))
        fun e(ev: (Long) -> VehicleEvent) = rec.onEvent(ev(++t))
        rec.assign(SeatPosition.FRONT_PASSENGER, Rider("m", "민지"))
        e { VehicleEvent.OdometerChanged(100.0, it) }
        e { VehicleEvent.SeatOccupancyChanged(SeatPosition.FRONT_PASSENGER, true, it) }
        e { VehicleEvent.SeatOccupancyChanged(SeatPosition.REAR_LEFT, true, it) }
        e { VehicleEvent.GearChanged(Gear.DRIVE, it) }
        e { VehicleEvent.OdometerChanged(125.0, it) }
        e { VehicleEvent.GearChanged(Gear.PARK, it) }
        e { VehicleEvent.SeatOccupancyChanged(SeatPosition.REAR_LEFT, false, it) }
        e { VehicleEvent.GearChanged(Gear.DRIVE, it) }
        e { VehicleEvent.OdometerChanged(200.0, it) }
        e { VehicleEvent.GearChanged(Gear.PARK, it) }
        val views = rec.sittingViews()
        val s = SettlementCalculator.settle(rec.preview()!!, PricingConfig(EnergyKind.ELECTRIC, 300.0, 70.0, 5.0))
        val m = timelineOf(s, views)
        assertEquals(listOf("지훈", "민지", "뒷좌석 왼쪽 동승자"), m.rows.map { it.name })
        val guest = m.rows.last()
        assertTrue(guest.unnamed)
        assertEquals(0.0, guest.spans.single().start, 1e-9)
        assertEquals(0.25, guest.spans.single().endInclusive, 1e-9)
        assertEquals(1, m.ticks.size)
        assertEquals(0.25, m.ticks.single().fraction, 1e-9)
        assertEquals(s.grandTotalWon, m.rows.sumOf { it.amountWon })
    }
}
