package kr.gachitan.ui.screens

import kr.gachitan.ui.model.monthRequestText
import kr.gachitan.core.PaymentRequest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import kr.gachitan.ui.model.monthTotals
import kr.gachitan.ui.model.MonthTotal
import kr.gachitan.ui.components.Avatar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.gachitan.core.Formatting
import kr.gachitan.core.TripLog
import kr.gachitan.session.SessionState.Phase
import kr.gachitan.ui.MainViewModel
import kr.gachitan.ui.Tab
import kr.gachitan.ui.UiState
import kr.gachitan.ui.components.HLine
import kr.gachitan.ui.components.TextLink
import kr.gachitan.ui.components.VLine
import kr.gachitan.ui.model.dateHeader
import kr.gachitan.ui.model.groupByDate
import kr.gachitan.ui.model.peopleChangeText
import kr.gachitan.ui.model.timeRange
import kr.gachitan.ui.theme.Palette
import kr.gachitan.ui.theme.Space
import kr.gachitan.ui.theme.Type
import java.time.LocalDate
import java.time.ZoneId

/** 기록 탭 왼쪽의 '진행 중' 요약. 화면 그리기와 분리해 미리보기에서도 씁니다. */
data class InProgress(
    /** 정차 중인(아직 정산 전) 운행의 거리. */
    val tripKm: Double?,
    /** 진행 중인 여행(묶음 정산). */
    val tripTitle: String?,
    val tripCount: Int = 0,
    val tripTotalKm: Double = 0.0,
    val tripTotalWon: Long = 0
)

@Composable
fun HistoryScreen(ui: UiState, vm: MainViewModel, layout: WindowLayout) {
    val s = ui.session
    val j = s.journeySummary
    val progress = InProgress(
        tripKm = s.liveKm.takeIf { s.phase == Phase.PARKED || s.phase == Phase.REVIEW },
        tripTitle = j?.journey?.title,
        tripCount = j?.journey?.trips?.size ?: 0,
        tripTotalKm = j?.totalKm ?: 0.0,
        tripTotalWon = (j?.totalFuelWon ?: 0L) + (j?.totalExpenseWon ?: 0L)
    )
    val clipboard = LocalClipboardManager.current
    val zone = ZoneId.systemDefault()
    HistoryContent(
        trips = s.trips, progress = progress, layout = layout, zone = zone,
        onTrip = vm::openTrip, onCurrent = { vm.selectTab(Tab.SETTLE) }, onBundle = vm::openBundle,
        onMonthPaid = { t, paid -> vm.setPaidByName(t.tripIds, t.name, paid) },
        onMonthCopy = { t ->
            val today = LocalDate.now(zone)
            val link = PaymentRequest.validTossId(ui.settings.tossId)?.let { "https://toss.me/$it/${t.unpaidWon}" }
            clipboard.setText(AnnotatedString(monthRequestText(t, today, link)))
            vm.showNotice("${t.name} 요청 문구를 복사했어요")
        }
    )
}

/**
 * 기록: 왼쪽은 '진행 중'(정산 전 운행, 진행 중인 여행), 오른쪽은 날짜별 운행 목록.
 * 카드를 늘어놓지 않고 정렬과 구분선으로 구조를 만듭니다.
 * 기록이 있다는 것은 정산 내역을 저장했다는 뜻일 뿐, 송금이 끝났다는 뜻이 아닙니다.
 */
@Composable
fun HistoryContent(
    trips: List<TripLog>,
    progress: InProgress,
    layout: WindowLayout,
    zone: ZoneId,
    onTrip: (String) -> Unit,
    onCurrent: () -> Unit,
    onBundle: () -> Unit,
    today: LocalDate = LocalDate.now(zone),
    onMonthPaid: (MonthTotal, Boolean) -> Unit = { _, _ -> },
    onMonthCopy: (MonthTotal) -> Unit = {}
) {
    val groups = groupByDate(trips, zone)
    if (layout.wide) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(0.36f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text("기록", style = Type.Hero, color = Palette.Text)
                ProgressBlock(progress, onCurrent, onBundle)
                MonthBlock(monthTotals(trips, zone, today), today, onMonthPaid, onMonthCopy)
            }
            Spacer(Modifier.width(Space.xl))
            VLine(Modifier.fillMaxHeight().padding(vertical = Space.m))
            Spacer(Modifier.width(Space.xl))
            LazyColumn(Modifier.weight(0.64f).fillMaxHeight()) {
                tripList(groups, trips.size, today, zone, onTrip)
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Text("기록", style = Type.Hero, color = Palette.Text) }
            item { Column(Modifier.padding(vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                ProgressBlock(progress, onCurrent, onBundle)
                MonthBlock(monthTotals(trips, zone, today), today, onMonthPaid, onMonthCopy)
            } }
            tripList(groups, trips.size, today, zone, onTrip)
        }
    }
}

/**
 * 이번 달 사람별 합계. 출퇴근 카풀처럼 매번 송금하지 않고 한 달에 한 번 몰아서 받을 때 씁니다.
 * '받음'은 운전자가 직접 표시합니다. 앱은 송금을 확인하지 않습니다.
 */
@Composable
private fun MonthBlock(totals: List<MonthTotal>, today: LocalDate, onPaid: (MonthTotal, Boolean) -> Unit, onCopy: (MonthTotal) -> Unit) {
    if (totals.isEmpty()) return
    Spacer(Modifier.height(Space.m))
    val left = totals.sumOf { it.unpaidWon }
    Text("${today.monthValue}월 사람별", style = Type.Label, color = Palette.TextSecondary)
    Text(if (left > 0) "남은 금액 ${Formatting.won(left)}" else "모두 받았어요", style = Type.Title, color = Palette.Text)
    totals.forEach { t ->
        HLine()
        Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Avatar(44.dp, name = t.name)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = Type.Heading, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${t.rides}회 · ${Formatting.km(t.km)} · 합계 ${Formatting.won(t.amountWon)}",
                    style = Type.Caption, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (t.allPaid) "받음" else Formatting.won(t.unpaidWon), style = Type.Heading,
                color = if (t.allPaid) Palette.TextSecondary else Palette.Text)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
            if (!t.allPaid) TextLink("요청 복사", { onCopy(t) })
            TextLink(if (t.allPaid) "받음 취소" else "받음 표시", { onPaid(t, !t.allPaid) })
        }
    }
}

@Composable
private fun ProgressBlock(p: InProgress, onCurrent: () -> Unit, onBundle: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(4.dp).background(Palette.Accent))
    if (p.tripKm == null && p.tripTitle == null) {
        Text("진행 중", style = Type.Label, color = Palette.TextSecondary)
        Text("진행 중인 운행이나 여행이 없어요", style = Type.Heading, color = Palette.Text)
        Text("탑승 화면에서 ‘여행 시작’", style = Type.Caption, color = Palette.TextSecondary)
        return
    }
    p.tripKm?.let { km ->
        Text("정산 전 운행", style = Type.Label, color = Palette.TextSecondary)
        Text("지금까지 ${Formatting.km(km)}", style = Type.Title, color = Palette.Text)
        Row { TextLink("정산 확인", onCurrent) }
        HLine()
    }
    p.tripTitle?.let { title ->
        Text("진행 중인 여행", style = Type.Label, color = Palette.TextSecondary)
        Text(title, style = Type.Hero, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        HLine()
        StatRow("운행", "${p.tripCount}회")
        HLine()
        StatRow("거리", Formatting.km(p.tripTotalKm))
        HLine()
        StatRow("합계", Formatting.won(p.tripTotalWon))
        HLine()
        Row { TextLink("여행 내역", onBundle) }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Body, color = Palette.TextSecondary, modifier = Modifier.width(120.dp))
        Text(value, style = Type.Title, color = Palette.Text, maxLines = 1)
    }
}

private fun LazyListScope.tripList(
    groups: List<Pair<LocalDate, List<TripLog>>>,
    count: Int,
    today: LocalDate,
    zone: ZoneId,
    onTrip: (String) -> Unit
) {
    if (groups.isEmpty()) {
        item {
            Column(Modifier.padding(top = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text("아직 기록이 없어요", style = Type.Title, color = Palette.Text)
                Text("정산을 확정한 운행이 날짜별로 여기에 남아요.", style = Type.Body, color = Palette.TextSecondary)
            }
        }
        return
    }
    item {
        Text("정산 ${count}건", style = Type.Caption, color = Palette.TextSecondary,
            modifier = Modifier.padding(bottom = Space.s))
    }
    groups.forEach { (date, list) ->
        item(key = "d-$date") {
            Column(Modifier.padding(top = Space.l)) {
                Text(dateHeader(date, today), style = Type.Title, color = Palette.Text)
                Spacer(Modifier.height(Space.s))
                HLine()
            }
        }
        items(list, key = { it.id }) { log -> TripRow(log, zone, onClick = { onTrip(log.id) }) }
    }
}

/** 운행 한 줄: 시간 · 거리 · 인원 변화 · 합계. 누르면 정산 상세. */
@Composable
private fun TripRow(log: TripLog, zone: ZoneId, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(timeRange(log.startedAtMillis, log.endedAtMillis, zone), style = Type.Label, color = Palette.TextSecondary, maxLines = 1)
                    log.bundleTitle?.let {
                        Spacer(Modifier.width(Space.s))
                        Text("· $it", style = Type.Label, color = Palette.AccentText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Formatting.km(log.distanceKm), style = Type.Title, color = Palette.Text, maxLines = 1)
                    Spacer(Modifier.width(Space.m))
                    Text(peopleChangeText(log), style = Type.Heading, color = Palette.TextSecondary, maxLines = 1)
                }
            }
            Text(Formatting.won(log.totalWon), style = Type.Title, color = Palette.Text, maxLines = 1)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "정산 상세", tint = Palette.TextSecondary, modifier = Modifier.size(36.dp))
        }
        HLine()
    }
}
