package kr.gachitan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.gachitan.session.SessionState.Phase
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kr.gachitan.AppContainer
import kr.gachitan.core.Gear
import kr.gachitan.core.SeatPosition
import kr.gachitan.data.AppSettings
import kr.gachitan.session.SessionState
import kr.gachitan.ui.model.ConnectionIssue
import kr.gachitan.ui.model.LockReason
import kr.gachitan.ui.model.connectionIssue
import kr.gachitan.ui.model.lockReason
import kr.gachitan.vehicle.DemoPlaces
import kr.gachitan.vehicle.DemoVehicleSignals
import kr.gachitan.vehicle.Link
import kr.gachitan.vehicle.SignalHealth
import kr.gachitan.vehicle.SignalStatus

enum class Screen { MAIN, SETTINGS, BUNDLE }

/** 정차 중 하단 메뉴. 설정은 메뉴가 아니라 상단 아이콘입니다. */
enum class Tab(val label: String) { BOARD("탑승"), SETTLE("정산"), HISTORY("기록") }

/** 화면 위치(탭·열어 본 기록·공유 자동 열기). 차량 상태와 별개로 사람이 고른 것입니다. */
data class Nav(
    val screen: Screen = Screen.MAIN,
    val tab: Tab = Tab.BOARD,
    /** 기록에서 고른 운행. null이면 정산 탭은 지금 운행(또는 가장 최근 운행)을 보여 줍니다. */
    val openTripId: String? = null,
    /** '확정하고 공유'를 누른 직후: 정산이 확정되면 공유 패널을 바로 엽니다. */
    val shareAfterConfirm: Boolean = false
)

data class UiState(
    val session: SessionState = SessionState(),
    val settings: AppSettings = AppSettings(),
    /** 플랫폼 주행 규제. null = 규제 서비스에 연결하지 못함. */
    val restricted: Boolean? = null,
    val gear: Gear? = null,
    val statuses: List<SignalStatus> = emptyList(),
    val health: SignalHealth = SignalHealth(),
    val demo: DemoVehicleSignals.DemoState = DemoVehicleSignals.DemoState(),
    val screen: Screen = Screen.MAIN,
    val notice: String? = null,
    val tab: Tab = Tab.BOARD,
    val openTripId: String? = null,
    val shareAfterConfirm: Boolean = false
) {
    val lock: LockReason? get() = lockReason(restricted, gear, session.phase, session.demo)
    val inputLocked: Boolean get() = lock != null
    val issue: ConnectionIssue get() = connectionIssue(health, session.odometerKnown, session.demo)
    /** 시뮬레이터 패널: 데모 모드이고 플랫폼 규제가 없을 때만 보입니다(실제 주행 중에는 숨김). */
    val showSimulator: Boolean get() = session.demo && restricted != true
    /** 좌석 신호를 받고 있는지. 받지 못하면 좌석을 '비어 있음'이 아니라 '알 수 없음'으로 그립니다. */
    val seatsKnown: Boolean get() = session.demo || health.seats == Link.OK
    /** 정차 중 하단 메뉴를 보일지: 잠금이 없고 기본 화면일 때만. */
    val showNav: Boolean get() = !inputLocked && screen == Screen.MAIN && session.endedJourney == null
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val c: AppContainer) : ViewModel() {
    private val nav = MutableStateFlow(Nav())
    private val notice = MutableStateFlow<String?>(null)

    private val sessionState = c.session.flatMapLatest { it.state }
    private val health = c.session.flatMapLatest { it.signals.health }
    private val statuses = c.session.flatMapLatest { it.signals.statuses }
    private val vehicle = combine(c.monitor.restricted, c.monitor.gear, c.demo.state) { r, g, d -> Triple(r, g, d) }
    private val signal = combine(health, statuses) { h, s -> h to s }
    private val local = combine(nav, notice) { n, note -> n to note }

    val ui: StateFlow<UiState> = combine(sessionState, c.settings.settings, vehicle, signal, local) {
            session, settings, (restricted, monitorGear, demo), (h, st), (n, note) ->
        val gear = if (session.demo) demo.gear else monitorGear
        // 정산 확인 단계는 언제나 정산 탭에서 보입니다(Back으로 돌아가면 탑승 탭).
        val tab = if (session.phase == Phase.REVIEW && n.openTripId == null) Tab.SETTLE else n.tab
        val base = UiState(session, settings, restricted, gear, st, h, demo, n.screen, note, tab, n.openTripId, n.shareAfterConfirm)
        if (base.inputLocked) base.copy(screen = Screen.MAIN) else base
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        // 차량 상태가 바뀌는 순간에 맞는 탭으로 옮깁니다.
        // - 주행이 끝나 정차하면 탑승 탭(누가 탔는지 확인)
        // - 정산이 확정되면 정산 탭(방금 확정한 운행)
        viewModelScope.launch {
            sessionState.map { it.phase }.distinctUntilChanged().collect { phase ->
                when (phase) {
                    Phase.PARKED, Phase.READY -> nav.update { if (it.tab == Tab.SETTLE && it.openTripId == null) it.copy(tab = Tab.BOARD) else it }
                    Phase.DRIVING, Phase.WAITING_FOR_ODOMETER -> nav.update { it.copy(tab = Tab.BOARD, openTripId = null, shareAfterConfirm = false) }
                    Phase.SETTLED -> nav.update { it.copy(tab = Tab.SETTLE, openTripId = null) }
                    Phase.REVIEW -> Unit
                }
            }
        }
    }

    private val session get() = c.session.value

    // 조작 함수들은 화면이 잠겨 있으면 무시합니다(화면에서 버튼을 숨기는 것과 별개의 이중 안전장치).
    private inline fun unlocked(block: () -> Unit) { if (!ui.value.inputLocked) block() }

    // 운행
    fun assign(seat: SeatPosition, name: String) = unlocked { session.assign(seat, name) }
    fun nameSitting(id: String, name: String) = unlocked { session.nameSitting(id, name) }
    fun confirmSitting(id: String) = unlocked { session.confirmSitting(id) }
    fun addExtra(label: String, amount: Long, payers: Set<String>) = unlocked { session.addExtra(label, amount, payers) }
    fun removeExtra(index: Int) = unlocked { session.removeExtra(index) }
    fun setExtraPayers(index: Int, payers: Set<String>) = unlocked { session.setExtraPayers(index, payers) }
    fun labelCurrentStop(name: String) = unlocked { session.labelCurrentStop(name) }
    fun beginReview() = unlocked { session.beginReview() }
    fun cancelReview() = unlocked { session.cancelReview() }
    fun confirmSettlement() = unlocked { session.confirmSettlement() }
    fun discardTrip() = unlocked { session.discardTrip(); nav.update { it.copy(tab = Tab.BOARD, openTripId = null) } }
    /** 정산을 확정하고, 확정되면 공유 패널을 엽니다. */
    fun confirmAndShare() = unlocked { nav.update { it.copy(shareAfterConfirm = true) }; session.confirmSettlement() }
    fun shareOpened() { nav.update { it.copy(shareAfterConfirm = false) } }
    fun dismissSettlement() = unlocked { session.dismissSettlement() }
    /** 잠금(상태 불명) 화면에서도 누를 수 있는 유일한 조작입니다. */
    fun reconnect() = session.reconnect()

    // 화면 이동
    fun openSettings() = unlocked { nav.update { it.copy(screen = Screen.SETTINGS) } }
    fun closeSettings() { nav.update { it.copy(screen = Screen.MAIN) }; session.refreshDriver() }
    fun openBundle() = unlocked { nav.update { it.copy(screen = Screen.BUNDLE) } }
    fun closeBundle() { nav.update { it.copy(screen = Screen.MAIN) } }

    /** 하단 메뉴. 정차 중 정산 탭을 고르면 정산 확인 단계로 들어가고, 떠나면 정차 상태로 돌아옵니다. */
    fun selectTab(tab: Tab) = unlocked {
        val phase = ui.value.session.phase
        if (tab == Tab.SETTLE && phase == Phase.PARKED) session.beginReview()
        if (tab != Tab.SETTLE && phase == Phase.REVIEW) session.cancelReview()
        nav.update { it.copy(tab = tab, openTripId = null) }
    }

    /** 기록에서 운행 하나를 열어 정산 상세를 봅니다. */
    fun openTrip(id: String) = unlocked {
        if (ui.value.session.phase == Phase.REVIEW) session.cancelReview()
        nav.update { it.copy(tab = Tab.SETTLE, openTripId = id) }
    }
    fun clearNotice() { notice.value = null }
    fun showNotice(text: String) { notice.value = text }

    // 묶음 정산
    fun startJourney(title: String) = unlocked { session.startJourney(title) }
    fun addExpense(label: String, amount: Long, payerId: String, participants: Set<String>) =
        unlocked { session.addExpense(label, amount, payerId, participants) }
    fun removeExpense(id: String) = unlocked { session.removeExpense(id) }
    fun addMember(name: String) = unlocked { session.addMember(name) }
    fun endJourney() = unlocked { session.endJourney() }
    fun dismissEndedJourney() = unlocked { session.dismissEndedJourney(); nav.update { it.copy(screen = Screen.MAIN) } }
    fun discardJourney() = unlocked { session.discardJourney(); nav.update { it.copy(screen = Screen.MAIN) } }

    // 설정
    fun updateSettings(transform: (AppSettings) -> AppSettings) = unlocked { c.settings.update(transform) }
    /** 차종을 바꾸면 그 차종의 에너지 신호(전기: 배터리·전비 / 연료: 연료량·연비)로 다시 연결합니다. */
    fun setEnergyKind(kind: kr.gachitan.core.EnergyKind) = unlocked {
        if (c.settings.current.energyKind != kind) {
            c.settings.update { it.withEnergyKind(kind) }
            session.reconnect()
        }
    }
    fun setDemoMode(on: Boolean) = unlocked {
        if (!c.setDemoMode(on)) notice.value = "운행을 끝낸 뒤 바꿀 수 있어요"
    }
    fun clearHistory() = unlocked { session.clearHistory() }
    fun setPaid(tripId: String, riderId: String, paid: Boolean) = unlocked { session.setPaid(tripId, riderId, paid) }
    fun setPaidByName(tripIds: Set<String>, name: String, paid: Boolean) = unlocked { session.setPaidByName(tripIds, name, paid) }
    fun wipeAllData() = unlocked { c.wipeAll() }

    // 시뮬레이터 (데모 모드, 규제 없음일 때만 화면에 보임)
    val demo: DemoVehicleSignals get() = c.demo

    private var scenarioJob: kotlinx.coroutines.Job? = null

    /**
     * 시뮬레이터 '시연 시나리오': 90초 시연 흐름을 버튼 하나로 재생합니다(영상 녹화·심사용).
     * 2명 탑승 → 20km → 정자역 정차, 1명 하차·1명 탑승 → 25km → 판교역 정차 → 통행료 1,200원 → 정산 화면.
     * 사람이 누르는 것과 같은 경로(시뮬레이터 신호, 이름 붙이기, 통행료 입력)만 씁니다.
     */
    fun playDemoScenario() {
        val s = session
        if (!s.isDemo) return
        if (s.state.value.phase !in setOf(Phase.READY, Phase.SETTLED)) {
            notice.value = "지금 운행을 끝낸 뒤 시연을 시작할 수 있어요"
            return
        }
        scenarioJob?.cancel()
        scenarioJob = viewModelScope.launch {
            val d = c.demo
            suspend fun pause(ms: Long) = kotlinx.coroutines.delay(ms)
            notice.value = "시연 시나리오를 재생해요"
            nav.update { it.copy(screen = Screen.MAIN, tab = Tab.BOARD, openTripId = null) }
            if (d.state.value.gear != Gear.PARK) { d.setGear(Gear.PARK); pause(800) }
            d.state.value.occupied.forEach { d.toggleSeat(it) }
            pause(800)
            // 1) 두 사람이 탑니다. 좌석은 차가 감지하고 이름은 사람이 붙입니다.
            d.toggleSeat(SeatPosition.FRONT_PASSENGER); d.toggleSeat(SeatPosition.REAR_LEFT)
            pause(1_200)
            s.assign(SeatPosition.FRONT_PASSENGER, "민지"); s.assign(SeatPosition.REAR_LEFT, "준호")
            pause(1_800)
            // 2) 출발 → 20km → 정자역 정차
            d.setGear(Gear.DRIVE); pause(1_800)
            d.drive(20.0); pause(1_800)
            val (n1, la1, lo1) = DemoPlaces.presets[1]
            d.arriveAt(n1, la1, lo1); pause(300)
            d.setGear(Gear.PARK); pause(1_800)
            // 3) 준호 하차, 서연 탑승
            d.toggleSeat(SeatPosition.REAR_LEFT); pause(900)
            d.toggleSeat(SeatPosition.REAR_RIGHT); pause(900)
            s.assign(SeatPosition.REAR_RIGHT, "서연"); pause(1_800)
            // 4) 다시 출발 → 25km → 판교역 정차 → 통행료
            d.setGear(Gear.DRIVE); pause(1_500)
            d.drive(25.0); pause(1_500)
            val (n2, la2, lo2) = DemoPlaces.presets[0]
            d.arriveAt(n2, la2, lo2); pause(300)
            d.setGear(Gear.PARK); pause(1_500)
            s.addExtra("통행료", 1_200, s.state.value.defaultExtraPayers); pause(1_500)
            // 5) 정산 확인 화면
            selectTab(Tab.SETTLE)
        }
    }
}
