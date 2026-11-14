package kr.gachitan

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.gachitan.data.SecureStore
import kr.gachitan.data.SettingsRepository
import kr.gachitan.session.SessionState
import kr.gachitan.session.TripSession
import kr.gachitan.vehicle.CombinedVehicleSignals
import kr.gachitan.vehicle.DemoVehicleSignals
import kr.gachitan.vehicle.DrivingStateMonitor
import kr.gachitan.vehicle.PleosPlaceSignals
import kr.gachitan.vehicle.PleosVehicleSignals

/**
 * 운행 기록은 화면과 무관하게 앱 프로세스 수명 동안 이어집니다.
 * Pleos 멀티 윈도우에서 다른 앱이 앞에 와도 기록은 계속되고,
 * 프로세스가 다시 만들어지면 암호화 저장된 기록장에서 복원합니다(꺼져 있던 동안은 공백으로 표시).
 */
class GachitanApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

/**
 * 입력원 분리: 실차 세션(차량 신호)과 데모 세션(시뮬레이터)은 입력원도, 기록 파일도 따로입니다.
 * 한 번에 하나만 켜지고, 운행을 기록하는 동안에는 바꿀 수 없습니다.
 * 주행 상태 감시([monitor])는 두 모드 모두에서 항상 켜 둡니다.
 */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = SecureStore(app)
    val settings = SettingsRepository(store)
    val monitor = DrivingStateMonitor(app)
    val demo = DemoVehicleSignals()

    private val log: (String, Throwable?) -> Unit = { m, e -> Log.w(TAG, m, e) }
    private val vehicle = PleosVehicleSignals(
        app, monitor,
        occupancyMapping = { settings.settings.value.occupancyMapping },
        energyKind = { settings.settings.value.energyKind }
    )
    private val realSignals = CombinedVehicleSignals(vehicle, PleosPlaceSignals(app, scope, onStatus = vehicle::reportStatus))
    private val realSession = TripSession(scope, realSignals, store, settings, namespace = "", log = log)
    private val demoSession = TripSession(scope, demo, store, settings, namespace = DEMO_NAMESPACE, log = log)

    private val _session = MutableStateFlow(if (settings.current.demoMode) demoSession else realSession)
    val session: StateFlow<TripSession> = _session.asStateFlow()

    fun start() {
        monitor.start()
        val s = _session.value
        if (s.isDemo) demo.continueFrom(s.lastOdometerKm())
        s.start()
    }

    /** 데모 모드 전환. 운행을 기록하는 중이면 바꾸지 않고 false를 돌려줍니다. */
    fun setDemoMode(on: Boolean): Boolean {
        val current = _session.value
        if (current.isDemo == on) return true
        if (current.state.value.phase !in SWITCHABLE) return false
        current.stop()
        settings.update { it.copy(demoMode = on) }
        val next = if (on) demoSession else realSession
        if (on) demo.continueFrom(next.lastOdometerKm())
        next.start()
        _session.value = next
        return true
    }

    /** 내 데이터 모두 지우기: 두 세션의 기록과 설정, 암호화 키를 지웁니다. */
    fun wipeAll() {
        realSession.resetAll()
        demoSession.resetAll()
        store.wipeAll()
        settings.update { kr.gachitan.data.AppSettings(demoMode = _session.value.isDemo) }
    }

    private companion object {
        const val TAG = "Gachitan"
        const val DEMO_NAMESPACE = "demo-"
        val SWITCHABLE = setOf(SessionState.Phase.READY, SessionState.Phase.SETTLED)
    }
}
