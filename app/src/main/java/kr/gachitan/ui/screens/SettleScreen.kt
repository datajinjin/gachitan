package kr.gachitan.ui.screens

import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.gachitan.core.Formatting
import kr.gachitan.core.LogShare
import kr.gachitan.core.TripLog
import kr.gachitan.data.AppSettings
import kr.gachitan.session.SessionState.Phase
import kr.gachitan.ui.MainViewModel
import kr.gachitan.ui.UiState
import androidx.compose.foundation.layout.widthIn
import kr.gachitan.ui.components.SecondaryButton
import kr.gachitan.ui.Tab
import kr.gachitan.ui.components.Avatar
import kr.gachitan.ui.components.HLine
import kr.gachitan.ui.components.Notice
import kr.gachitan.ui.components.PrimaryButton
import kr.gachitan.ui.components.SegmentBar
import kr.gachitan.ui.components.Sheet
import kr.gachitan.ui.components.TextLink
import kr.gachitan.ui.components.Timeline
import kr.gachitan.ui.components.ValueRow
import kr.gachitan.ui.model.bandCosts
import kr.gachitan.ui.model.costBreakdown
import kr.gachitan.ui.model.roundingText
import kr.gachitan.ui.model.basisText
import kr.gachitan.ui.model.ruleText
import kr.gachitan.ui.model.shareSubtitle
import kr.gachitan.ui.model.timeRange
import kr.gachitan.ui.model.dateHeader
import kr.gachitan.ui.model.dateOf
import kr.gachitan.ui.model.timelineOf
import kr.gachitan.ui.theme.Palette
import kr.gachitan.ui.theme.Space
import kr.gachitan.ui.theme.Type
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** 정산 탭이 무엇을 보여 주는지. */
sealed interface SettleSource {
    /** 정차 중 확인 단계: 아직 확정 전. */
    data class Review(val log: TripLog) : SettleSource
    /** 확정된 운행(방금 확정했거나 기록에서 연 것). */
    data class Saved(val log: TripLog, val justSettled: Boolean) : SettleSource
    data object Empty : SettleSource
}

/** 사람이 보는 행동. 화면 그리기와 분리해 미리보기·테스트에서 쓸 수 있습니다. */
data class SettleActions(
    val onPrimary: (() -> Unit)?,
    val primaryText: String?,
    val primaryEnabled: Boolean = true,
    val onShare: (String) -> Unit,
    val onBasis: () -> Unit,
    val onEdit: (() -> Unit)?,
    val onCopy: (() -> Unit)?,
    val onBundle: (() -> Unit)?,
    /** 저장된 개인 운행에서 '받음' 표시(동승자 id, 받음 여부). 운전자가 직접 표시하는 값입니다. */
    val onPaid: ((String, Boolean) -> Unit)? = null
)

@Composable
fun SettleScreen(ui: UiState, vm: MainViewModel, layout: WindowLayout, onShare: (ShareTarget) -> Unit) {
    val s = ui.session
    val preview = s.preview
    val reviewLog = remember(preview) { preview?.let { TripLog.from(it, PREVIEW_ID, s.journey?.title) } }
    val openId = ui.openTripId
    // 방금 확정한 운행도 기록 쪽 값을 써야 '받음' 표시가 바로 보입니다.
    val settledLog = s.settled?.log?.let { l -> s.trips.firstOrNull { it.id == l.id } ?: l }
    val source: SettleSource = when {
        openId != null -> s.trips.firstOrNull { it.id == openId }?.let { SettleSource.Saved(it, false) } ?: SettleSource.Empty
        (s.phase == Phase.REVIEW || s.phase == Phase.PARKED) && reviewLog != null -> SettleSource.Review(reviewLog)
        settledLog != null -> SettleSource.Saved(settledLog, justSettled = true)
        s.trips.isNotEmpty() -> SettleSource.Saved(s.trips.first(), false)
        else -> SettleSource.Empty
    }
    var basisSheet by rememberSaveable { mutableStateOf(false) }
    var editSheet by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    if (source is SettleSource.Empty) return EmptySettle(ui.openTripId != null)

    val log = when (source) { is SettleSource.Review -> source.log; is SettleSource.Saved -> source.log; else -> return }
    val review = source is SettleSource.Review
    val reviewIds = if (review && preview != null) timelineOf(preview, s.sittings).rows.filter { it.needsReview }.map { it.riderId }.toSet()
        else emptySet()

    // '확정하고 공유'를 누른 뒤 확정되면 첫 사람의 공유 패널을 엽니다.
    if (source is SettleSource.Saved && source.justSettled && ui.shareAfterConfirm) {
        LaunchedEffect(log.id) {
            vm.shareOpened()
            log.payers.firstOrNull()?.let { onShare(ShareTarget(log.id, it.riderId)) }
        }
    }

    val bundleActive = s.journey != null
    val actions = when {
        review && s.reviewCount > 0 -> SettleActions(null, "확인할 좌석 ${s.reviewCount}곳", primaryEnabled = false,
            onShare = {}, onBasis = { basisSheet = true }, onEdit = { editSheet = true }, onCopy = null, onBundle = null)
        review && bundleActive -> SettleActions(vm::confirmSettlement, "여행에 추가", onShare = {}, onBasis = { basisSheet = true },
            onEdit = { editSheet = true }, onCopy = null, onBundle = null)
        review && log.payers.isEmpty() -> SettleActions(vm::confirmSettlement, "정산 확정", onShare = {}, onBasis = { basisSheet = true },
            onEdit = { editSheet = true }, onCopy = null, onBundle = null)
        review -> SettleActions(vm::confirmAndShare, "확정하고 공유", onShare = {}, onBasis = { basisSheet = true },
            onEdit = { editSheet = true }, onCopy = null, onBundle = null)
        log.inBundle -> SettleActions(null, null, onShare = {}, onBasis = { basisSheet = true }, onEdit = null,
            onCopy = { clipboard.setText(AnnotatedString(summaryText(log))) }, onBundle = if (bundleActive) vm::openBundle else null)
        log.payers.isEmpty() -> SettleActions(null, null, onShare = {}, onBasis = { basisSheet = true }, onEdit = null,
            onCopy = { clipboard.setText(AnnotatedString(summaryText(log))) }, onBundle = null)
        else -> SettleActions({ log.payers.firstOrNull()?.let { onShare(ShareTarget(log.id, it.riderId)) } }, "공유",
            onShare = { onShare(ShareTarget(log.id, it)) }, onBasis = { basisSheet = true }, onEdit = null,
            onCopy = { clipboard.setText(AnnotatedString(summaryText(log))) }, onBundle = null,
            onPaid = { id, paid -> vm.setPaid(log.id, id, paid) })
    }

    // 움직이지 않고 D→P만 한 운행: 0원 정산을 만들지 않고 버리거나 이어 가게 합니다.
    if (review && log.distanceKm < 0.05) return EmptyTrip(onDiscard = vm::discardTrip, onContinue = { vm.selectTab(Tab.BOARD) })

    SettleContent(
        log = log, review = review, layout = layout, reviewIds = reviewIds,
        hasGaps = review && s.gaps.isNotEmpty(), zone = ZoneId.systemDefault(), actions = actions
    )

    if (basisSheet) BasisSheet(log, ui.settings) { basisSheet = false }
    if (editSheet && preview != null) EditSheet(ui, vm) { editSheet = false }
}

private const val PREVIEW_ID = "preview"

/**
 * 정산 화면 본문. 데이터([TripLog])와 행동만 받아 그립니다.
 * 왼쪽: 총액·거리·구간(중심 요소는 총액). 오른쪽: 사람별 부담액 → 보조 행동 → 주 행동.
 */
@Composable
fun SettleContent(
    log: TripLog,
    review: Boolean,
    layout: WindowLayout,
    reviewIds: Set<String>,
    hasGaps: Boolean,
    zone: ZoneId,
    actions: SettleActions,
    today: LocalDate = LocalDate.now(zone)
) {
    val displaySize = if (layout.wide) 104.sp else 80.sp
    PaneLayout(
        layout = layout,
        leftWeight = 0.42f,
        compactLeftHeight = 0.dp,
        showLeftWhenCompact = false,
        left = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(Space.l)) { TotalBlock(log, review, displaySize, zone, today) }
        },
        right = {
            if (!layout.wide) TotalBlock(log, review, displaySize, zone, today)
            Text("사람별 부담액", style = Type.Title, color = Palette.Text, modifier = Modifier.padding(top = if (layout.wide) 0.dp else Space.l))
            Column {
                log.shares.forEach { share ->
                    val canMark = actions.onPaid != null && !share.isDriver && share.amountWon > 0
                    ShareLine(share, log, review = share.riderId in reviewIds,
                        paid = share.riderId in log.paidIds,
                        onPaid = if (canMark) ({ actions.onPaid?.invoke(share.riderId, share.riderId !in log.paidIds) }) else null,
                        onClick = if (!review && !log.inBundle && !share.isDriver && share.amountWon > 0) ({ actions.onShare(share.riderId) }) else null)
                    HLine()
                }
            }
            if (review && reviewIds.isNotEmpty()) Notice("다시 탄 분 확인 필요 · 기록 수정에서 확인")
            if (hasGaps) Notice("앱이 꺼져 있던 동안의 승하차는 기록되지 않았어요")
            if (log.inBundle) Text("‘${log.bundleTitle}’에 합산 · 여행을 끝낼 때 한 번에 정산",
                style = Type.Body, color = Palette.TextSecondary)
            if (!review && !log.inBundle && log.payers.isEmpty()) Text("보낼 금액이 없어요.", style = Type.Body, color = Palette.TextSecondary)
        },
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                TextLink("계산 근거", actions.onBasis)
                actions.onEdit?.let { TextLink("기록 수정", it) }
                actions.onCopy?.let { TextLink("내용 복사", it) }
                actions.onBundle?.let { TextLink("여행 보기", it) }
            }
            if (actions.primaryText != null) {
                PrimaryButton(actions.primaryText, { actions.onPrimary?.invoke() }, Modifier.fillMaxWidth(),
                    enabled = actions.primaryEnabled && actions.onPrimary != null)
            }
        }
    )
}

@Composable
private fun TotalBlock(log: TripLog, review: Boolean, displaySize: androidx.compose.ui.unit.TextUnit, zone: ZoneId, today: LocalDate) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                (log.extrasWon > 0 || log.wearWon > 0) && review -> "예상 총액"
                log.extrasWon > 0 || log.wearWon > 0 -> "총액"
                review -> "예상 차량 비용"
                else -> "차량 비용"
            }, style = Type.Heading, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
            Text(if (log.extrasWon > 0 || log.wearWon > 0) "추정 포함" else "추정액", style = Type.Label, color = Palette.AccentText,
                modifier = Modifier.border(1.5.dp, Palette.AccentText, CircleShape).padding(horizontal = Space.m, vertical = 4.dp))
        }
        Text(
            buildAnnotatedString {
                append("%,d".format(log.totalWon))
                withStyle(SpanStyle(fontSize = displaySize * 0.5f)) { append("원") }
            },
            style = Type.display(displaySize), color = Palette.Text, maxLines = 1
        )
        costBreakdown(log)?.let { Text(it, style = Type.Body, color = Palette.TextSecondary) }
        Text("총 ${Formatting.km(log.distanceKm)}", style = Type.Title, color = Palette.TextSecondary)
        if (!review) Text("${dateHeader(dateOf(log.startedAtMillis, zone), today)} · ${timeRange(log.startedAtMillis, log.endedAtMillis, zone)}",
            style = Type.Body, color = Palette.TextSecondary)
    }
    Spacer(Modifier.heightIn(min = Space.m))
    SegmentBar(log.bands())
    HLine()
    Text(ruleText(log), style = Type.Body, color = Palette.TextSecondary)
}

@Composable
private fun ShareLine(share: LogShare, log: TripLog, review: Boolean, paid: Boolean = false, onPaid: (() -> Unit)? = null, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 96.dp)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(56.dp, name = if (share.unnamed || review) null else share.name)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(if (review) "${share.name}?" else share.name, style = Type.Heading,
                color = if (review) Palette.Caution else Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(shareSubtitle(share, log), style = Type.Caption, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 전체 거리 중 이 사람이 탄 비율. 금액 차이의 이유가 한눈에 보입니다.
            val ratio = if (log.distanceKm > 0) (share.distanceKm / log.distanceKm).toFloat().coerceIn(0f, 1f) else 0f
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.9f).height(6.dp).clip(CircleShape).background(Palette.SurfaceAlt)) {
                Box(Modifier.fillMaxWidth(ratio).fillMaxHeight().clip(CircleShape)
                    .background(if (share.unnamed) Palette.Outline else Palette.personInk(share.name)))
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(horizontalAlignment = Alignment.End) {
            Text(Formatting.won(share.amountWon), style = Type.Figure, color = if (paid) Palette.TextSecondary else Palette.Text,
                maxLines = 1, textAlign = TextAlign.End)
            // 받음 표시: 운전자가 직접 체크합니다(앱은 송금을 확인하지 않음). 기록 탭의 이번 달 남은 금액에 반영됩니다.
            if (onPaid != null) {
                Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Checkbox, onClick = onPaid)
                    .padding(horizontal = Space.s), contentAlignment = Alignment.Center) {
                    Text(if (paid) "✓ 받음" else "받음 표시", style = Type.Caption,
                        color = if (paid) Palette.Accent else Palette.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun EmptyTrip(onDiscard: () -> Unit, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("주행 거리가 없어요", style = Type.Hero, color = Palette.Text)
        Spacer(Modifier.heightIn(min = Space.m))
        Text("정산할 내용이 없습니다.", style = Type.Body, color = Palette.TextSecondary)
        Spacer(Modifier.heightIn(min = Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            PrimaryButton("운행 버리기", onDiscard, Modifier.widthIn(min = 280.dp))
            SecondaryButton("계속 운행", onContinue, Modifier.widthIn(min = 200.dp))
        }
    }
}

@Composable
private fun EmptySettle(missing: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(if (missing) "이 기록을 찾을 수 없어요" else "아직 정산한 운행이 없어요", style = Type.Hero, color = Palette.Text)
        Spacer(Modifier.heightIn(min = Space.m))
        Text(if (missing) "기록을 지웠다면 다시 볼 수 없어요." else "운행을 마치면 여기에 표시돼요.",
            style = Type.Body, color = Palette.TextSecondary)
    }
}

/** 계산 근거: 단가·효율, 구간별 1인 부담, 입력 비용, 반올림. */
@Composable
private fun BasisSheet(log: TripLog, settings: AppSettings, onDismiss: () -> Unit) {
    Sheet(title = "계산 근거", onDismiss = onDismiss, actions = {
        Spacer(Modifier.weight(1f))
        PrimaryButton("닫기", onDismiss, Modifier.width(200.dp))
    }) {
        ValueRow("에너지비 (추정)", Formatting.won(log.fuelOnlyWon), note = basisText(log, settings.energyKind, settings.unitPriceWon))
        if (log.wearWon > 0) ValueRow("차량 유지비", Formatting.won(log.wearWon),
            note = "km당 ${Formatting.won(Math.round(log.wearWon / log.distanceKm.coerceAtLeast(0.001)))} · 타이어·소모품 등")
        ValueRow("거리당 비용", Formatting.wonPerKm(log.wonPerKm), note = if (log.driverIncluded) "운전자도 함께 나눠요" else "동승자끼리만 나눠요")
        HLine()
        bandCosts(log).forEachIndexed { i, b ->
            ValueRow("구간 ${i + 1} · ${Formatting.km(b.km)} · ${b.people}명", "1인 약 ${Formatting.won(Math.round(b.perPersonWon))}",
                note = "구간 비용 ${Formatting.won(Math.round(b.costWon))}")
        }
        if (log.extras.isNotEmpty()) {
            HLine()
            log.extras.forEach { x -> ValueRow(x.label, Formatting.won(x.amountWon), note = "입력한 금액 그대로") }
        }
        HLine()
        Text("에너지비는 추정값이라 실제 충전·주유 금액과 다를 수 있어요. 동승자 금액은 ${settings.roundingUnitWon}원 단위로 반올림하고, 차이는 운전자가 맞춰요." + (roundingText(log.roundingWon)?.let { " $it" } ?: ""),
            style = Type.Caption, color = Palette.TextSecondary)
    }
}

/** 기록 수정: 사람별 탑승 막대를 보고 이름을 붙이거나 다시 탄 분을 확인합니다. */
@Composable
private fun EditSheet(ui: UiState, vm: MainViewModel, onDismiss: () -> Unit) {
    val s = ui.session
    val p = s.preview ?: return
    val model = remember(p, s.sittings) { timelineOf(p, s.sittings) }
    var rowSheet by rememberSaveable { mutableStateOf<String?>(null) }
    Sheet(title = "기록 수정", onDismiss = onDismiss, maxWidth = 1040.dp, actions = {
        Spacer(Modifier.weight(1f))
        PrimaryButton("닫기", onDismiss, Modifier.width(200.dp))
    }) {
        Text("줄을 눌러 이름을 정하세요.", style = Type.Body, color = Palette.TextSecondary)
        Timeline(model, onRow = { rowSheet = it.riderId }, modifier = Modifier.fillMaxWidth())
    }
    rowSheet?.let { id ->
        val row = model.rows.firstOrNull { it.riderId == id }
        if (row != null && !row.isDriver) NameSheet(
            title = if (row.unnamed) "${row.name}의 이름" else "${row.name}님",
            current = if (row.unnamed) null else row.name,
            suggested = row.name.takeIf { row.needsReview && !row.unnamed },
            recent = ui.settings.recentNames,
            onSave = { name -> row.sittingIds.forEach { vm.nameSitting(it, name) }; rowSheet = null },
            onConfirmSuggested = { row.sittingIds.forEach { vm.confirmSitting(it) }; rowSheet = null },
            onDismiss = { rowSheet = null },
            reviewUnnamed = row.needsReview && row.unnamed
        )
    }
}

/** 복사용 요약. 송금 여부는 적지 않습니다. */
internal fun summaryText(log: TripLog): String = buildString {
    append("[같이 탄 만큼] 총 ${Formatting.km(log.distanceKm)} · ${Formatting.won(log.totalWon)} (에너지비 추정")
    if (log.wearWon > 0) append(", 유지비 ${Formatting.won(log.wearWon)} 포함")
    if (log.extrasWon > 0) append(", 입력 비용 ${Formatting.won(log.extrasWon)} 포함")
    append(")\n")
    log.shares.forEach { s -> append("· ${if (s.isDriver) "${s.name}(운전)" else s.name} ${Formatting.km(s.distanceKm)} ${Formatting.won(s.amountWon)}\n") }
}.trimEnd()
