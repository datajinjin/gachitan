package kr.gachitan.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kr.gachitan.core.Formatting
import kr.gachitan.core.Gear
import kr.gachitan.core.SeatPosition
import kr.gachitan.session.SessionState.Phase
import kr.gachitan.ui.MainViewModel
import kr.gachitan.ui.Screen
import kr.gachitan.ui.Tab
import kr.gachitan.ui.UiState
import kr.gachitan.ui.components.AppTopBar
import kr.gachitan.ui.components.BottomNav
import kr.gachitan.ui.components.Notice
import kr.gachitan.ui.components.TextLink
import kr.gachitan.ui.components.VLine
import kr.gachitan.ui.model.LockReason
import kr.gachitan.ui.theme.Breakpoints
import kr.gachitan.ui.theme.Palette
import kr.gachitan.ui.theme.Space
import kr.gachitan.ui.theme.Type
import kr.gachitan.vehicle.DemoPlaces

/** 창 크기 판단 결과. 화면들은 이 값만 보고 배치를 고릅니다. */
data class WindowLayout(val wide: Boolean, val width: Dp, val height: Dp)

/** 공유 패널 대상: 어떤 운행의 누구. */
data class ShareTarget(val tripId: String, val riderId: String)

/**
 * 앱 영역 전체. Pleos의 왼쪽 Driving View, 상태바, 하단 공조 영역은 플랫폼 몫이라 여기서 다루지 않습니다.
 *
 * ┌ 상단바: 앱 이름 · 상태 · 설정(주행 중 숨김)
 * ├ 내용: 탭별 화면(주행 중에는 주행 화면 하나)
 * ├ 하단 메뉴: 탑승 · 정산 · 기록 (정차 중에만)
 * └ 시뮬레이터: 데모 모드에서만, 앱 화면과 구분된 회색 띠
 */
@Composable
fun GachitanRoot(ui: UiState, vm: MainViewModel) {
    var share by rememberSaveable(stateSaver = ShareTargetSaver) { mutableStateOf<ShareTarget?>(null) }
    // 주행이 시작되면 공유 패널을 닫습니다.
    LaunchedEffect(ui.inputLocked) { if (ui.inputLocked) share = null }
    // 오래 세워 둔 뒤 출발해 앞 운행을 자동으로 확정했으면 한 번 알려 줍니다(기록 탭에 남아 있음).
    LaunchedEffect(ui.session.autoSettledCount) {
        if (ui.session.autoSettledCount > 0) vm.showNotice("오래 세워 둔 앞 운행은 정산해 기록에 남겼어요")
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                val wide = maxWidth >= Breakpoints.expandedWidth && maxHeight >= Breakpoints.expandedHeight
                val layout = WindowLayout(wide, maxWidth, maxHeight)
                val edge = if (wide) Space.edgeExpanded else Space.edgeCompact
                Column(Modifier.widthIn(max = Breakpoints.maxContent).fillMaxSize().padding(horizontal = edge)) {
                    TopBar(ui, vm)
                    Box(Modifier.weight(1f).fillMaxWidth().padding(top = if (wide) Space.l else Space.m, bottom = Space.m)) {
                        Content(ui, vm, layout, onShare = { share = it })
                    }
                }
            }
            if (ui.showNav) {
                BottomNav(Tab.entries.map { it.label }, ui.tab.ordinal, onSelect = { vm.selectTab(Tab.entries[it]) })
            }
            if (ui.showSimulator) Simulator(ui, vm)
        }

        share?.let { target ->
            val wide = maxWidth >= Breakpoints.expandedWidth && maxHeight >= Breakpoints.expandedHeight
            val log = findLog(ui, target.tripId)
            if (log != null && !ui.inputLocked) SharePanel(log, target.riderId, ui.settings, wide, onSelect = { share = target.copy(riderId = it) }, onClose = { share = null })
        }

        ui.notice?.let { text ->
            LaunchedEffect(text) { delay(3_000); vm.clearNotice() }
            Box(Modifier.align(Alignment.BottomCenter).padding(Space.l).widthIn(max = 640.dp)) { Notice(text) }
        }
    }
}

@Composable
private fun TopBar(ui: UiState, vm: MainViewModel) {
    val s = ui.session
    val lock = ui.lock
    val status = when {
        ui.screen == Screen.SETTINGS -> "설정"
        lock == LockReason.UNKNOWN -> "주행 상태 확인 중"
        s.phase == Phase.DRIVING -> "기록 중"
        s.phase == Phase.WAITING_FOR_ODOMETER -> "주행거리 확인 중"
        lock != null -> "주행 중"
        ui.tab == Tab.SETTLE && ui.openTripId != null -> "지난 정산"
        else -> when (s.phase) {
            Phase.READY -> "출발 전"
            Phase.PARKED -> "정차 중"
            Phase.REVIEW -> "정산 확인"
            Phase.SETTLED -> if (ui.tab == Tab.SETTLE) "정산 완료" else "출발 전"
            else -> "출발 전"
        }
    }
    AppTopBar(
        status = status,
        live = s.phase == Phase.DRIVING,
        demo = s.demo,
        caution = lock == LockReason.UNKNOWN,
        onSettings = if (lock == null && ui.screen == Screen.MAIN) vm::openSettings else null
    ) {
        val j = ui.session.journey
        if (j != null && lock == null && ui.screen == Screen.MAIN) TextLink("${j.title} · ${s.currentDayNumber}일차", vm::openBundle)
    }
}

@Composable
private fun Content(ui: UiState, vm: MainViewModel, layout: WindowLayout, onShare: (ShareTarget) -> Unit) {
    val lock = ui.lock
    when {
        lock != null -> DrivingScreen(ui, lock, layout, vm)
        ui.screen == Screen.SETTINGS -> SettingsScreen(ui, vm, layout.wide)
        ui.screen == Screen.BUNDLE || ui.session.endedJourney != null -> BundleScreen(ui, vm, layout.wide)
        else -> when (ui.tab) {
            Tab.BOARD -> BoardScreen(ui, vm, layout)
            Tab.SETTLE -> SettleScreen(ui, vm, layout, onShare)
            Tab.HISTORY -> HistoryScreen(ui, vm, layout)
        }
    }
}

internal fun findLog(ui: UiState, id: String) =
    ui.session.trips.firstOrNull { it.id == id } ?: ui.session.settled?.log?.takeIf { it.id == id }

private val ShareTargetSaver = androidx.compose.runtime.saveable.Saver<ShareTarget?, List<String>>(
    save = { it?.let { t -> listOf(t.tripId, t.riderId) } ?: emptyList() },
    restore = { if (it.size == 2) ShareTarget(it[0], it[1]) else null }
)

// ---------------------------------------------------------------------- 공통 배치

/**
 * 두 칸 배치.
 * - 넓은 창: 왼쪽(중심 요소) | 오른쪽(정보 위, 행동 아래). 두 칸 모두 높이를 꽉 채워 아래가 비지 않습니다.
 * - 좁은 창: 위에서 아래로 왼쪽 → 오른쪽 정보를 쌓아 스크롤하고, 행동은 아래에 고정합니다.
 *   글자를 줄이지 않습니다.
 */
@Composable
fun PaneLayout(
    layout: WindowLayout,
    left: @Composable (Modifier) -> Unit,
    right: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit,
    leftWeight: Float = 0.46f,
    /** 좁은 창에서 왼쪽 칸(좌석 배치도 등)에 줄 높이. */
    compactLeftHeight: Dp = 440.dp,
    showLeftWhenCompact: Boolean = true
) {
    if (layout.wide) {
        Row(Modifier.fillMaxSize()) {
            left(Modifier.weight(leftWeight).fillMaxHeight())
            Spacer(Modifier.width(Space.xl))
            VLine(Modifier.fillMaxHeight().padding(vertical = Space.m))
            Spacer(Modifier.width(Space.xl))
            FillColumn(Modifier.weight(1f - leftWeight).fillMaxHeight(), top = right, bottom = actions)
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                if (showLeftWhenCompact) left(Modifier.fillMaxWidth().height(compactLeftHeight))
                Column(verticalArrangement = Arrangement.spacedBy(Space.m), content = right)
            }
            Spacer(Modifier.height(Space.m))
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs), content = actions)
        }
    }
}

/** 위 내용은 위에, 행동은 맨 아래에 붙이고, 넘치면 전체가 스크롤됩니다. */
@Composable
fun FillColumn(modifier: Modifier, top: @Composable ColumnScope.() -> Unit, bottom: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier) {
        val h = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().heightIn(min = h), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.m), content = top)
                Column(Modifier.padding(top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs), content = bottom)
            }
        }
    }
}

// ---------------------------------------------------------------------- 시뮬레이터 (데모 모드)

/**
 * 시연용 차량 시뮬레이터. 앱 화면이 아니라 '가짜 차량'이라 앱의 주행 잠금과 별개로 조작합니다.
 * 일반 사용자 흐름과 섞이지 않도록 회색 띠로 구분하고, 실제 차량 모드에서는 나타나지 않습니다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Simulator(ui: UiState, vm: MainViewModel) {
    val d = ui.demo
    // 시연 영상에서 화면을 가리지 않게 접을 수 있습니다. 접으면 시나리오·기어 버튼만 남습니다.
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Palette.SurfaceAlt).padding(horizontal = Space.l, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text("시뮬레이터 · ${d.day}일째 · ${gearWord(d.gear)} · ${Formatting.km(d.km)} · 배터리 ${"%.0f".format(d.soc)}%",
            style = Type.Caption, color = Palette.TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            SimKey("▶ 시연 시나리오") { vm.playDemoScenario() }
            SimKey(if (d.gear == Gear.PARK) "D 출발" else "P 정차") { vm.demo.setGear(if (d.gear == Gear.PARK) Gear.DRIVE else Gear.PARK) }
            SimKey("+5km") { vm.demo.drive(5.0) }
            SimKey("+20km") { vm.demo.drive(20.0) }
            if (open) {
                SeatPosition.passengerSeats.forEach { seat ->
                    SimKey("${if (seat in d.occupied) "●" else "○"} ${seat.label}") { vm.demo.toggleSeat(seat) }
                }
                DemoPlaces.presets.forEach { (name, lat, lon) -> SimKey("도착 $name") { vm.demo.arriveAt(name, lat, lon) } }
                // 주행 중에 날짜를 넘기면 한 운행이 하루 가까이 이어진 것처럼 기록되므로 정차 중에만 보입니다.
                if (d.gear == Gear.PARK) SimKey("다음 날") { vm.demo.nextDay() }
                SimKey("초기화") { vm.demo.reset() }
            }
            SimKey(if (open) "접기" else "좌석·장소 ▾") { open = !open }
        }
    }
}

@Composable
private fun SimKey(text: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Surface)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.m),
        contentAlignment = Alignment.Center
    ) { Text(text, style = Type.Caption, color = Palette.Text, maxLines = 1) }
}

internal fun gearWord(g: Gear?): String = when (g) {
    Gear.PARK -> "P"; Gear.DRIVE -> "D"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; else -> "-"
}
