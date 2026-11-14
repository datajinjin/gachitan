package kr.gachitan.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kr.gachitan.core.SeatPosition
import kr.gachitan.data.AppSettings
import kr.gachitan.session.SeatView
import kr.gachitan.ui.components.SeatMap
import kr.gachitan.ui.model.seatCells
import kr.gachitan.ui.preview.SampleTrip
import kr.gachitan.ui.theme.GachitanTheme
import java.time.LocalDate
import java.time.ZoneId

/*
 * Android Studio 미리보기 전용. 앱 실행 중에는 쓰이지 않습니다.
 * 예시 금액은 SampleTrip(실제 정산 엔진으로 계산)에서 가져오고, 화면에 숫자를 직접 적지 않습니다.
 * 넓은 창(1280×720dp)과 좁은 창(640×720dp) 두 크기로 확인합니다.
 */

private val seoul = ZoneId.of("Asia/Seoul")
private val sampleDay = LocalDate.of(2026, 11, 11)

private fun sampleSeats() = listOf(
    SeatView(SeatPosition.FRONT_PASSENGER, true, "s1", "준호", false, null, 0.0),
    SeatView(SeatPosition.REAR_LEFT, true, "s2", "민지", false, null, 0.0),
    SeatView(SeatPosition.REAR_CENTER, false, null, null, false, null, 0.0),
    SeatView(SeatPosition.REAR_RIGHT, false, null, null, false, null, 0.0)
)

@Composable
private fun Frame(content: @Composable () -> Unit) = GachitanTheme { Box(Modifier.fillMaxSize().padding(40.dp)) { content() } }

private val noActions = SettleActions(onPrimary = {}, primaryText = "공유", onShare = {}, onBasis = {}, onEdit = null,
    onCopy = {}, onBundle = null)

@Preview(name = "좌석 배치도", widthDp = 560, heightDp = 640, showBackground = true)
@Composable
private fun SeatMapPreview() = Frame { SeatMap(seatCells("나", sampleSeats(), seatsKnown = true), onSeat = {}) }

@Preview(name = "좌석 신호 없음", widthDp = 560, heightDp = 640, showBackground = true)
@Composable
private fun SeatMapUnknownPreview() = Frame { SeatMap(seatCells("나", sampleSeats(), seatsKnown = false), onSeat = null) }

@Preview(name = "정산 · 넓은 창", widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun SettleWidePreview() = Frame {
    SettleContent(SampleTrip.log(), review = false, layout = WindowLayout(true, 1200.dp, 640.dp), reviewIds = emptySet(),
        hasGaps = false, zone = seoul, actions = noActions, today = sampleDay)
}

@Preview(name = "정산 · 좁은 창", widthDp = 640, heightDp = 720, showBackground = true)
@Composable
private fun SettleCompactPreview() = Frame {
    SettleContent(SampleTrip.log(), review = false, layout = WindowLayout(false, 560.dp, 640.dp), reviewIds = emptySet(),
        hasGaps = false, zone = seoul, actions = noActions, today = sampleDay)
}

@Preview(name = "공유 패널", widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun SharePreview() = GachitanTheme {
    val log = SampleTrip.log()
    SharePanel(log, log.payers.first { it.name == "민지" }.riderId, AppSettings(driverName = "나"), wide = true, onSelect = {}, onClose = {})
}

@Preview(name = "기록 · 넓은 창", widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun HistoryWidePreview() = Frame {
    val log = SampleTrip.log()
    HistoryContent(listOf(log), InProgress(tripKm = null, tripTitle = "동승 모임", tripCount = 1, tripTotalKm = log.distanceKm, tripTotalWon = log.totalWon),
        WindowLayout(true, 1200.dp, 640.dp), seoul, onTrip = {}, onCurrent = {}, onBundle = {}, today = sampleDay)
}

@Preview(name = "기록 없음", widthDp = 1280, heightDp = 720, showBackground = true)
@Composable
private fun HistoryEmptyPreview() = Frame {
    HistoryContent(emptyList(), InProgress(null, null), WindowLayout(true, 1200.dp, 640.dp), seoul, {}, {}, {}, sampleDay)
}
