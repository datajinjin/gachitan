package kr.gachitan.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.gachitan.core.Formatting
import kr.gachitan.core.JourneyShare
import kr.gachitan.core.JourneySummary
import kr.gachitan.core.PersonTotal
import kr.gachitan.session.SessionState.Phase
import kr.gachitan.session.TripSession
import kr.gachitan.ui.MainViewModel
import kr.gachitan.ui.UiState
import kr.gachitan.ui.components.Avatar
import kr.gachitan.ui.components.Notice
import kr.gachitan.ui.components.QrCode
import kr.gachitan.ui.components.QuietButton
import kr.gachitan.ui.components.SecondaryButton
import kr.gachitan.ui.components.SectionLabel
import kr.gachitan.ui.components.StateHeader
import kr.gachitan.ui.components.ValueRow
import kr.gachitan.ui.theme.Palette
import kr.gachitan.ui.theme.Space
import kr.gachitan.ui.theme.Type
import kotlin.math.abs

/**
 * 묶음 정산: 여러 번의 운행(며칠 여행, 한 주 카풀)을 모아 마지막에 한 번만 송금합니다.
 * 운행 기록이 주인공이고, 공동 경비는 필요할 때만 더하는 보조 기능입니다.
 */
@Composable
fun BundleScreen(ui: UiState, vm: MainViewModel, expanded: Boolean) {
    val ended = ui.session.endedJourney
    val active = ui.session.journeySummary
    when {
        ended != null -> BundleResult(ended, ui, vm, expanded)
        active != null -> BundleProgress(active, ui, vm, expanded)
        else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            StateHeader("여행", null)
            Text("진행 중인 여행이 없어요.", style = Type.Body, color = Palette.TextSecondary)
            SecondaryButton("돌아가기", vm::closeBundle)
        }
    }
}

// ---------------------------------------------------------------------- 진행 중

@Composable
private fun BundleProgress(s: JourneySummary, ui: UiState, vm: MainViewModel, expanded: Boolean) {
    var expenseSheet by rememberSaveable { mutableStateOf(false) }
    var memberSheet by rememberSaveable { mutableStateOf(false) }
    var endSheet by rememberSaveable { mutableStateOf(false) }
    var discardSheet by rememberSaveable { mutableStateOf(false) }
    var deleteExpense by rememberSaveable { mutableStateOf<String?>(null) }
    val names = s.people.associate { it.member.id to it.member.name }
    val pausedTrip = ui.session.phase == Phase.PARKED || ui.session.phase == Phase.REVIEW

    ScreenFrame(
        expanded = expanded,
        header = {
            StateHeader(s.journey.title, if (s.journey.trips.isEmpty()) "아직 정산한 운행 없음" else "${s.days.size}일 · 운행 ${s.journey.trips.size}회") {
                QuietButton("닫기", vm::closeBundle)
            }
        },
        actions = listOf(
            Action("경비 추가", { expenseSheet = true }),
            Action("사람 추가", { memberSheet = true }),
            Action("끝내고 정산", { endSheet = true }, primary = true)
        ),
        stageFirstWhenCompact = false,
        summary = {
            ValueRow("주행", Formatting.km(s.totalKm))
            ValueRow("차량 비용", Formatting.won(s.totalFuelWon), note = "에너지비 추정")
            ValueRow("공동 경비", Formatting.won(s.totalExpenseWon),
                note = s.expenseByCategory.takeIf { it.size > 1 }
                    ?.joinToString(" · ") { "${it.category.label} ${Formatting.won(it.amountWon)}" })
            s.journey.expenses.sortedByDescending { it.atMillis }.forEach { e ->
                ValueRow("  ${e.label}", Formatting.won(e.amountWon),
                    note = "${names[e.payerId] ?: "?"} 결제 · ${if (e.participantIds.isEmpty()) "모두" else "${e.participantIds.size}명"} 나눔 · 눌러서 지우기",
                    modifier = Modifier.clickableRow { deleteExpense = e.id })
            }
            if (pausedTrip) Notice("정차 중인 운행도 함께 정산됩니다")
        },
        stage = {
            SectionLabel("지금 끝내면")
            s.people.forEach { p -> BalanceRow(p, selected = false, onClick = null) }
            if (s.onlyOnePerson) {
                Spacer(Modifier.height(Space.s))
                Notice("나눌 사람이 없어요 · 동승자 운행을 정산하거나 사람을 추가하세요")
            }
            if (s.days.isNotEmpty()) {
                Spacer(Modifier.height(Space.l))
                SectionLabel("날짜별")
                s.days.forEach { d ->
                    ValueRow("${d.dayNumber}일차 · ${d.date.monthValue}/${d.date.dayOfMonth}", Formatting.won(d.costWon),
                        note = "${Formatting.km(d.distanceKm)} · ${d.route.joinToString(" → ")}")
                }
            }
            // 되돌릴 수 없는 행동은 주 행동과 떨어진 맨 아래에 두고, 누르면 한 번 더 묻습니다.
            Spacer(Modifier.height(Space.xl))
            QuietButton("여행 취소", { discardSheet = true }, color = Palette.Caution)
        }
    )

    if (expenseSheet) ExpenseSheet(s.people.map { it.member }, { l, a, p, ps -> vm.addExpense(l, a, p, ps); expenseSheet = false },
        { expenseSheet = false }, defaultPayerId = TripSession.DRIVER_ID)
    if (memberSheet) TextSheet("사람 추가", "이름", emptyList(), "추가", { vm.addMember(it); memberSheet = false }, { memberSheet = false },
        body = "차에 타지 않고 경비만 함께 낸 사람")
    if (endSheet) ConfirmSheet("여행을 끝낼까요?",
        "운행 비용과 공동 경비를 합쳐 송금 목록을 만들어요." + if (pausedTrip) " 정차 중인 운행도 지금 정산해 포함합니다." else "",
        "끝내고 정산", { vm.endJourney(); endSheet = false }, { endSheet = false })
    if (discardSheet) ConfirmSheet("여행을 취소할까요?", "운행 기록은 기록 탭에 남아요.", "여행 취소",
        { vm.discardJourney(); discardSheet = false }, { discardSheet = false }, danger = true)
    deleteExpense?.let { id ->
        val e = s.journey.expenses.firstOrNull { it.id == id }
        if (e != null) ConfirmSheet("${e.label} ${Formatting.won(e.amountWon)}", "이 경비를 지울까요?", "지우기",
            { vm.removeExpense(id); deleteExpense = null }, { deleteExpense = null }, danger = true)
    }
}

// ---------------------------------------------------------------------- 최종 정산

@Composable
private fun BundleResult(s: JourneySummary, ui: UiState, vm: MainViewModel, expanded: Boolean) {
    val clipboard = LocalClipboardManager.current
    var selected by rememberSaveable { mutableStateOf(s.people.firstOrNull { it.balanceWon < 0 }?.member?.id ?: s.people.firstOrNull()?.member?.id) }
    var qrSheet by rememberSaveable { mutableStateOf<String?>(null) }
    var shareAll by rememberSaveable { mutableStateOf(0) }   // 0 없음, 1 확인, 2 QR

    ScreenFrame(
        expanded = expanded,
        header = { StateHeader("여행 정산", if (s.transfers.isEmpty()) s.journey.title else "${s.journey.title} · 송금 ${s.transfers.size}번") },
        actions = listOf(
            Action("전체 내역 공유", { shareAll = 1 }),
            Action("내용 복사", { clipboard.setText(AnnotatedString(JourneyShare.text(s))) }),
            Action("완료", vm::dismissEndedJourney, primary = true)
        ),
        summary = {
            val person = s.people.firstOrNull { it.member.id == selected }
            // 주고받을 돈이 없으면 QR을 띄우지 않습니다(0원짜리 송금 화면은 의미가 없음).
            if (s.transfers.isEmpty()) {
                Text("주고받을 돈이 없어요", style = Type.Title, color = Palette.Text)
                Text("모두 정산이 끝났어요.", style = Type.Body, color = Palette.TextSecondary)
            } else if (expanded && person != null) PersonalPanel(s, person, ui)
            else Text("이름을 누르면 그 사람 몫 QR이 열려요.", style = Type.Body, color = Palette.TextSecondary)
            s.people.firstOrNull { it.roundingAbsorbedWon != 0L }?.let { p ->
                Text("반올림 차이 ${Formatting.won(abs(p.roundingAbsorbedWon))}은 ${if (p.member.id == TripSession.DRIVER_ID) "운전자" else "${p.member.name}님"} 몫에서 맞췄어요.",
                    style = Type.Caption, color = Palette.TextSecondary)
            }
        },
        stage = {
            SectionLabel("사람별")
            s.people.forEach { p ->
                BalanceRow(p, selected = expanded && p.member.id == selected, onClick = {
                    selected = p.member.id
                    if (!expanded) qrSheet = p.member.id
                })
            }
            if (s.transfers.isNotEmpty()) {
                Spacer(Modifier.height(Space.l))
                SectionLabel("이렇게 보내면 끝")
                s.transfers.forEach { t ->
                    ValueRow("${t.from.name} → ${t.to.name}", Formatting.won(t.amountWon), strong = true)
                }
            }
        }
    )

    qrSheet?.let { id ->
        val person = s.people.firstOrNull { it.member.id == id }
        if (person != null) {
            val payload = personalPayload(s, id, ui)
            QrSheet("${person.member.name}님 정산", payload, personalCaption(person.member.name, payload), onDismiss = { qrSheet = null })
        }
    }
    if (shareAll == 1) ConfirmSheet("전체 내역을 공유할까요?",
        "모든 사람의 이름과 금액, 들른 곳이 담겨요.", "QR 보기",
        { shareAll = 2 }, { shareAll = 0 })
    if (shareAll == 2) {
        val url = JourneyShare.url(ui.settings.viewerBaseUrl, s, ui.settings.tossId)
        QrSheet("전체 내역", url ?: JourneyShare.text(s).take(900),
            if (url != null) "휴대폰 카메라로 찍으면 열려요. 서버에 저장되지 않아요."
            else "텍스트 요약이 담겨 있어요.",
            onDismiss = { shareAll = 0 })
    }
}

private fun personalPayload(s: JourneySummary, memberId: String, ui: UiState): String =
    JourneyShare.personalUrl(ui.settings.viewerBaseUrl, s, memberId, ui.settings.tossId, TripSession.DRIVER_ID)
        ?: JourneyShare.personalText(s, memberId).orEmpty()

private fun personalCaption(name: String, payload: String): String =
    (if (payload.startsWith("https://")) "휴대폰 카메라로 찍으면 정산 화면이 열립니다. " else "휴대폰 카메라로 찍으면 정산 내용이 열립니다. ") +
        "${name} 몫만 담겨 있어요."

@Composable
private fun PersonalPanel(s: JourneySummary, p: PersonTotal, ui: UiState) {
    val payload = personalPayload(s, p.member.id, ui)
    Row(verticalAlignment = Alignment.Top) {
        QrCode(payload, size = 220.dp, description = "${p.member.name}님 정산 QR")
        Spacer(Modifier.width(Space.l))
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(p.member.name, style = Type.Heading, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(balanceWord(p), style = Type.Label, color = Palette.TextSecondary)
            Text(Formatting.won(abs(p.balanceWon)), style = Type.Figure, color = Palette.Text)
            Text(personalCaption(p.member.name, payload), style = Type.Caption, color = Palette.TextSecondary)
        }
    }
}

private fun balanceWord(p: PersonTotal) = when {
    p.balanceWon > 0 -> "받을 돈"
    p.balanceWon < 0 -> "보낼 돈"
    else -> "정산 끝"
}

@Composable
private fun BalanceRow(p: PersonTotal, selected: Boolean, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(Space.radius))
            .background(if (selected) Palette.SurfaceAlt else Palette.Bg)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(48.dp, name = p.member.name)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(p.member.name, style = Type.Heading, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = buildList {
                if (p.distanceKm > 0) add(Formatting.km(p.distanceKm))
                if (p.fuelShareWon > 0) add("차량 ${Formatting.won(p.fuelShareWon)}")
                if (p.expenseShareWon > 0) add("경비 ${Formatting.won(p.expenseShareWon)}")
                if (p.paidWon > 0) add("낸 돈 ${Formatting.won(p.paidWon)}")
            }
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = Type.Caption, color = Palette.TextSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(balanceWord(p), style = Type.Caption, color = Palette.TextSecondary)
            Text(Formatting.won(abs(p.balanceWon)), style = Type.Figure, color = if (p.balanceWon == 0L) Palette.TextSecondary else Palette.Text,
                textAlign = TextAlign.End)
        }
    }
}
