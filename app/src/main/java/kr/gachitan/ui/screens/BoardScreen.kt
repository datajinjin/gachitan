package kr.gachitan.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.gachitan.core.Formatting
import kr.gachitan.core.SeatPosition
import kr.gachitan.session.SeatView
import kr.gachitan.session.SessionState.Phase
import kr.gachitan.ui.MainViewModel
import kr.gachitan.ui.Tab
import kr.gachitan.ui.UiState
import kr.gachitan.ui.components.Avatar
import kr.gachitan.ui.components.HLine
import kr.gachitan.ui.components.Notice
import kr.gachitan.ui.components.PrimaryButton
import kr.gachitan.ui.components.SeatMap
import kr.gachitan.ui.components.SecondaryButton
import kr.gachitan.ui.components.Sheet
import kr.gachitan.ui.components.TextLink
import kr.gachitan.ui.components.ValueRow
import kr.gachitan.ui.model.ConnectionIssue
import kr.gachitan.ui.model.RiderLine
import kr.gachitan.ui.model.SeatState
import kr.gachitan.ui.model.UNNAMED_TEXT
import kr.gachitan.ui.model.issueCopy
import kr.gachitan.ui.model.occupancyText
import kr.gachitan.ui.model.riderLines
import kr.gachitan.ui.model.seatCells
import kr.gachitan.ui.theme.Palette
import kr.gachitan.ui.theme.Space
import kr.gachitan.ui.theme.Type
import kr.gachitan.vehicle.Link

/**
 * 탑승 탭. 출발 전과 정차 중을 같은 틀로 보여 줍니다.
 * 왼쪽: 큰 좌석 배치도(중심 요소). 오른쪽: 인원 → 탑승자 목록 → 행동.
 */
@Composable
fun BoardScreen(ui: UiState, vm: MainViewModel, layout: WindowLayout) {
    val s = ui.session
    val parked = s.phase == Phase.PARKED || s.phase == Phase.REVIEW
    var seatSheet by rememberSaveable { mutableStateOf<SeatPosition?>(null) }
    var roster by rememberSaveable { mutableStateOf(false) }
    var tripSheet by rememberSaveable { mutableStateOf(false) }
    var extraSheet by rememberSaveable { mutableStateOf<Int?>(null) }   // -1 = 새로 추가
    var placeSheet by rememberSaveable { mutableStateOf(false) }

    val cells = seatCells(ui.settings.driverName, s.seats, ui.seatsKnown)
    val lines = riderLines(cells, s.seats)
    val issue = ui.issue
    val copy = issueCopy(issue)
    val blocking = copy?.blocking == true
    val seatWaiting = !ui.seatsKnown && ui.health.seats == Link.WAITING

    PaneLayout(
        layout = layout,
        left = { m -> SeatMap(cells, onSeat = { seatSheet = it }, modifier = m) },
        right = {
            // 1. 인원(또는 연결 문제)
            if (blocking && copy != null) {
                Text(copy.title, style = Type.Title, color = Palette.Caution)
                Text(copy.body, style = Type.Body, color = Palette.TextSecondary)
            } else {
                Text(if (seatWaiting) "좌석 확인 중" else occupancyText(cells), style = Type.Hero, color = Palette.Text)
                if (parked) {
                    Text(listOfNotNull("지금까지 ${Formatting.km(s.liveKm)}", s.currentStopName).joinToString(" · "),
                        style = Type.Heading, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // 2. 탑승자 목록
            Column {
                lines.forEach { line ->
                    RiderRow(line, parked, onClick = if (line.seat == SeatPosition.DRIVER) null else ({ seatSheet = line.seat }))
                    HLine()
                }
            }
            // 3. 꼭 알아야 할 때만 한 줄
            when {
                !ui.seatsKnown && !seatWaiting -> "좌석 정보 없음 · 이대로 출발하면 운전자만 기록돼요"
                lines.any { it.state == SeatState.UNNAMED } -> "이름 없는 좌석을 눌러 이름을 정하세요"
                else -> null
            }?.let { Text(it, style = Type.Body, color = Palette.TextSecondary) }
            Text(ruleLine(ui), style = Type.Caption, color = Palette.TextSecondary)
            s.seats.firstOrNull { it.needsReview }?.let { r -> Notice(reviewText(r), action = "확인", onAction = { seatSheet = r.seat }) }
            if (s.gaps.isNotEmpty()) Notice("앱이 꺼져 있던 동안의 승하차는 기록되지 않았어요")
            if (copy != null && !blocking && issue != ConnectionIssue.CONNECTING) Notice(copy.title)
            if (parked) s.extras.forEach { e ->
                ValueRow(e.label, Formatting.won(e.amountWon), note = "입력 · ${e.payerNames.joinToString(", ")} 나눔",
                    modifier = Modifier.clickableRow { extraSheet = e.index })
            }
        },
        actions = {
            when {
                blocking -> {
                    PrimaryButton("다시 연결", vm::reconnect, Modifier.fillMaxWidth())
                    Row { TextLink("진단 정보", vm::openSettings) }
                }
                parked -> {
                    PrimaryButton("운행 끝내기", { vm.selectTab(Tab.SETTLE) }, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        TextLink("통행료·주차비", { extraSheet = -1 })
                        TextLink("장소 이름", { placeSheet = true })
                    }
                }
                else -> {
                    PrimaryButton("탑승자 확인", { roster = true }, Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        if (s.journey == null) TextLink("여행 시작", { tripSheet = true })
                        else TextLink("여행 보기", vm::openBundle)
                        // 처음 켠 사람(심사·시연)이 차량 없이도 전체 흐름을 볼 수 있게, 기록이 없을 때만 보입니다.
                        if (!s.demo && s.trips.isEmpty()) {
                            Spacer(Modifier.width(Space.l))
                            TextLink("차량 없이 체험", { vm.setDemoMode(true) })
                        }
                    }
                }
            }
        }
    )

    seatSheet?.let { seat -> SeatNameSheet(ui, vm, seat) { seatSheet = null } }
    if (roster) RosterSheet(lines, s.seats, onSeat = { roster = false; seatSheet = it }, onConfirm = { id -> vm.confirmSitting(id) },
        onDismiss = { roster = false })
    if (tripSheet) {
        TextSheet(
            title = "여행 시작", placeholder = "이름 (예: 강릉 2박 3일)", presets = listOf("주말 여행", "이번 주 카풀"), confirm = "시작",
            body = "여러 운행을 모아 마지막에 한 번 정산해요.",
            onConfirm = { vm.startJourney(it); tripSheet = false }, onDismiss = { tripSheet = false }
        )
    }
    extraSheet?.let { idx -> ExtraEditor(ui, vm, idx) { extraSheet = null } }
    if (placeSheet) {
        TextSheet(
            title = "이 장소의 이름", placeholder = "예: 정자역 2번 출구", presets = listOf("집", "회사", "휴게소", "숙소", "식당"),
            confirm = "저장", onConfirm = { vm.labelCurrentStop(it); placeSheet = false }, onDismiss = { placeSheet = false }
        )
    }
}

internal fun ruleLine(ui: UiState): String =
    if (ui.settings.driverPaysShare) "운전자 포함 · 거리별 분담" else "동승자만 · 거리별 분담"

/** 탑승자 한 줄: 사람 아이콘 · 이름 · 좌석(정차 중이면 탄 거리). */
@Composable
private fun RiderRow(line: RiderLine, parked: Boolean, onClick: (() -> Unit)?) {
    val review = line.state == SeatState.REVIEW
    val unnamed = line.state == SeatState.UNNAMED
    Row(
        Modifier.fillMaxWidth().heightIn(min = 80.dp)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(52.dp, name = if (unnamed || review) null else line.name)
        Spacer(Modifier.width(Space.m))
        Text(
            if (review) "${line.name}?" else line.name,
            style = Type.Heading,
            color = when { review -> Palette.Caution; unnamed -> Palette.TextSecondary; else -> Palette.Text },
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(Space.m))
        Text(
            if (parked && line.km > 0) "${line.seatLabel} · ${Formatting.km(line.km)}" else line.seatLabel,
            style = Type.Body, color = Palette.TextSecondary, maxLines = 1, textAlign = TextAlign.End
        )
    }
}

/**
 * '탑승자 확인': 좌석마다 누가 앉았는지 한 번에 훑고 고칩니다.
 * 센서는 앉았다는 것만 알기 때문에, 이름은 여기서 사람이 정하거나 확인합니다.
 */
@Composable
private fun RosterSheet(
    lines: List<RiderLine>,
    seats: List<SeatView>,
    onSeat: (SeatPosition) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val bySeat = seats.associateBy { it.seat }
    Sheet(title = "탑승자 확인", onDismiss = onDismiss, actions = {
        Spacer(Modifier.weight(1f))
        PrimaryButton("확인", onDismiss, Modifier.width(200.dp))
    }) {
        lines.forEach { line ->
            val view = bySeat[line.seat]
            val suggestedName = view?.name
            val sittingId = view?.sittingId
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    .let { if (line.seat != SeatPosition.DRIVER) it.clickable(role = Role.Button) { onSeat(line.seat) } else it },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(line.seatLabel, style = Type.Body, color = Palette.TextSecondary, modifier = Modifier.width(180.dp), maxLines = 1)
                Text(line.name, style = Type.Heading,
                    color = if (line.name == UNNAMED_TEXT) Palette.TextSecondary else Palette.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                when {
                    line.state == SeatState.REVIEW && suggestedName != null && sittingId != null ->
                        SecondaryButton("$suggestedName 맞아요", { onConfirm(sittingId) })
                    line.seat != SeatPosition.DRIVER ->
                        Text(if (line.name == UNNAMED_TEXT) "이름 정하기" else "바꾸기", style = Type.Label, color = Palette.AccentText)
                }
            }
            HLine()
        }
        Text("이름 없는 동승자는 좌석 이름으로 정산돼요.", style = Type.Caption, color = Palette.TextSecondary)
    }
}

/** 좌석 하나의 이름 시트. 다시 탄 분 확인도 여기서 합니다. */
@Composable
internal fun SeatNameSheet(ui: UiState, vm: MainViewModel, seat: SeatPosition, onDone: () -> Unit) {
    val view: SeatView? = ui.session.seats.firstOrNull { it.seat == seat }
    val sittingId = view?.sittingId
    NameSheet(
        title = "${seat.label}에 앉은 분",
        current = view?.name ?: view?.pendingName,
        suggested = view?.takeIf { it.needsReview }?.name,
        recent = ui.settings.recentNames,
        onSave = { name -> vm.assign(seat, name); onDone() },
        onConfirmSuggested = sittingId?.let { id -> { vm.confirmSitting(id); onDone() } },
        onDismiss = onDone,
        reviewUnnamed = view != null && view.needsReview && view.name == null
    )
}

/** 통행료 추가(index = -1) 또는 기존 항목의 분담자 수정. */
@Composable
internal fun ExtraEditor(ui: UiState, vm: MainViewModel, index: Int, onDone: () -> Unit) {
    val s = ui.session
    val existing = s.extras.firstOrNull { it.index == index }
    if (existing == null) {
        ExtraSheet(
            participants = s.participants, defaultPayers = s.defaultExtraPayers,
            onSave = { label, won, payers -> vm.addExtra(label, won, payers); onDone() },
            onDismiss = onDone
        )
    } else {
        ExtraSheet(
            participants = s.participants, defaultPayers = s.defaultExtraPayers,
            initialLabel = existing.label, initialAmount = existing.amountWon, initialPayers = existing.payerKeys, editing = true,
            onSave = { _, _, payers -> vm.setExtraPayers(index, payers); onDone() },
            onDelete = { vm.removeExtra(index); onDone() },
            onDismiss = onDone
        )
    }
}

/** 다시 탄 분 확인 문구. 이름이 떨어진 경우(같은 이름이 다른 좌석에 붙음)도 다룹니다. */
internal fun reviewText(r: SeatView): String =
    if (r.name != null) "${r.seat.label}: ${r.name}님이 맞나요?" else "${r.seat.label}: 누가 앉았는지 다시 정해 주세요"

internal fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(role = Role.Button, onClick = onClick)
