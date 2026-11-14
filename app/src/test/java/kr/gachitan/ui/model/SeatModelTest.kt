package kr.gachitan.ui.model

import kr.gachitan.core.SeatPosition
import kr.gachitan.session.SeatView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatModelTest {
    private fun seat(p: SeatPosition, occupied: Boolean = false, name: String? = null, review: Boolean = false, pending: String? = null) =
        SeatView(p, occupied, if (occupied) "s-$p" else null, name, review, pending, 0.0)

    private val empty = SeatPosition.passengerSeats.map { seat(it) }

    @Test
    fun `운전자 혼자`() {
        val cells = seatCells("나", empty, seatsKnown = true)
        assertEquals("1명 탑승", occupancyText(cells))
        assertEquals(SeatState.DRIVER, cells.first().state)
        assertTrue(cells.drop(1).all { it.state == SeatState.EMPTY && it.text == "—" })
        assertEquals(listOf("나"), riderLines(cells, empty).map { it.name })
    }

    @Test
    fun `좌석 신호가 없으면 인원을 지어내지 않는다`() {
        val cells = seatCells("나", empty, seatsKnown = false)
        assertEquals("좌석 정보 없음", occupancyText(cells))
        assertTrue(cells.drop(1).all { it.state == SeatState.UNKNOWN })
    }

    @Test
    fun `이름 없음, 확인 필요, 미리 정한 이름`() {
        val seats = listOf(
            seat(SeatPosition.FRONT_PASSENGER, occupied = true, name = "준호"),
            seat(SeatPosition.REAR_LEFT, occupied = true),
            seat(SeatPosition.REAR_CENTER, occupied = true, name = "민지", review = true),
            seat(SeatPosition.REAR_RIGHT, pending = "서연")
        )
        val cells = seatCells("", seats, seatsKnown = true).associateBy { it.seat }
        assertEquals("운전자", cells.getValue(SeatPosition.DRIVER).text)
        assertEquals(SeatState.NAMED, cells.getValue(SeatPosition.FRONT_PASSENGER).state)
        assertEquals(SeatState.UNNAMED, cells.getValue(SeatPosition.REAR_LEFT).state)
        assertEquals(UNNAMED_SEAT_TEXT, cells.getValue(SeatPosition.REAR_LEFT).text)
        assertEquals("민지?", cells.getValue(SeatPosition.REAR_CENTER).text)
        assertEquals(SeatState.EMPTY, cells.getValue(SeatPosition.REAR_RIGHT).state)
        assertEquals("4명 탑승", occupancyText(cells.values.toList()))
        val lines = riderLines(cells.values.toList(), seats).associate { it.seat to it.name }
        assertEquals(UNNAMED_TEXT, lines[SeatPosition.REAR_LEFT])
        assertEquals("민지", lines[SeatPosition.REAR_CENTER])
    }
}
