package kr.gachitan.ui.model

import kr.gachitan.core.SeatPosition
import kr.gachitan.session.SeatView

/**
 * 좌석 하나를 그리는 데 필요한 값. Android에 의존하지 않아 JVM 테스트로 검증합니다.
 *
 * 센서는 '누군가 앉아 있다'만 압니다. 이름은 사람이 붙인 것이고, 이름이 없으면 "이름 없음"으로 보여
 * 차가 사람을 알아본 것처럼 보이지 않게 합니다.
 */
data class SeatCell(
    val seat: SeatPosition,
    val state: SeatState,
    /** 좌석 안에 보일 글자. 빈 좌석은 "—". */
    val text: String,
    /** 화면 읽기용 전체 설명. */
    val description: String,
    val km: Double = 0.0
)

enum class SeatState {
    /** 운전석. 항상 탑승. */
    DRIVER,
    /** 센서가 탑승을 감지했고 사람이 이름을 붙임. */
    NAMED,
    /** 센서가 탑승을 감지했지만 이름이 없음. */
    UNNAMED,
    /** 정차 뒤 다시 앉아 앞사람 이름을 제안 중. 사람이 확인해야 함. */
    REVIEW,
    /** 비어 있음. 다음에 앉을 사람 이름을 미리 정해 두었을 수 있음. */
    EMPTY,
    /** 좌석 신호를 받지 못해 알 수 없음. 비어 있다고 단정하지 않습니다. */
    UNKNOWN
}

val SeatState.occupied: Boolean get() = this == SeatState.DRIVER || this == SeatState.NAMED || this == SeatState.UNNAMED || this == SeatState.REVIEW

const val UNNAMED_TEXT = "이름 없음"
/** 좌석 칸 안의 표기. 목록과 같은 말을 쓰고, 좁은 뒷좌석에서는 두 줄로 내려 씁니다. */
const val UNNAMED_SEAT_TEXT = UNNAMED_TEXT

/**
 * @param seatsKnown 좌석 신호를 받고 있는지. false면 동승석은 모두 [SeatState.UNKNOWN]입니다.
 */
fun seatCells(driverName: String, seats: List<SeatView>, seatsKnown: Boolean): List<SeatCell> {
    val driver = driverName.ifBlank { "운전자" }
    val bySeat = seats.associateBy { it.seat }
    val out = mutableListOf(SeatCell(SeatPosition.DRIVER, SeatState.DRIVER, driver, "운전석 $driver"))
    SeatPosition.passengerSeats.forEach { seat ->
        val v = bySeat[seat]
        out += when {
            !seatsKnown -> SeatCell(seat, SeatState.UNKNOWN, "?", "${seat.label} 확인 불가")
            v == null || !v.occupied -> {
                val pending = v?.pendingName
                if (pending != null) SeatCell(seat, SeatState.EMPTY, "—", "${seat.label} 비어 있음, 앉으면 $pending")
                else SeatCell(seat, SeatState.EMPTY, "—", "${seat.label} 비어 있음")
            }
            v.needsReview -> SeatCell(seat, SeatState.REVIEW, (v.name ?: UNNAMED_SEAT_TEXT) + "?", "${seat.label} 다시 탄 분 확인 필요", v.km)
            v.name != null -> SeatCell(seat, SeatState.NAMED, v.name, "${seat.label} ${v.name} 탑승", v.km)
            else -> SeatCell(seat, SeatState.UNNAMED, UNNAMED_SEAT_TEXT, "${seat.label} 탑승, 이름 없음", v.km)
        }
    }
    return out
}

/** 오른쪽 탑승자 목록 한 줄. 운전자가 맨 위, 나머지는 좌석 순서. */
data class RiderLine(val seat: SeatPosition, val name: String, val seatLabel: String, val state: SeatState, val km: Double)

fun riderLines(cells: List<SeatCell>, seats: List<SeatView>): List<RiderLine> {
    val bySeat = seats.associateBy { it.seat }
    return cells.filter { it.state.occupied }.map { c ->
        val name = when (c.state) {
            SeatState.DRIVER -> c.text
            SeatState.REVIEW -> bySeat[c.seat]?.name ?: UNNAMED_TEXT
            SeatState.UNNAMED -> UNNAMED_TEXT
            else -> c.text
        }
        RiderLine(c.seat, name, c.seat.label, c.state, c.km)
    }
}

/** 탑승 인원 문구. 좌석 신호가 없으면 숫자를 지어내지 않습니다. */
fun occupancyText(cells: List<SeatCell>): String =
    if (cells.any { it.state == SeatState.UNKNOWN }) "좌석 정보 없음" else "${cells.count { it.state.occupied }}명 탑승"
