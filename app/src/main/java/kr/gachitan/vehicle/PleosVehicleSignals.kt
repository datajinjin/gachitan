package kr.gachitan.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kr.gachitan.core.EnergyKind
import kr.gachitan.core.SeatPosition
import kr.gachitan.core.VehicleEvent

/**
 * Pleos Vehicle SDK + AAOS 표준 API에서 운행 사실을 수집합니다.
 *
 * 사용하는 Vehicle SDK 기능 (모두 조회·구독, 차량 제어 없음)
 * | 도메인      | 메서드                                              | 권한        |
 * |------------|----------------------------------------------------|------------|
 * | Seat       | getCurrentSeatOccupancy / registerSeatOccupancy    | CAR_SEATS  |
 * | Odometer   | getCurrentDistanceDriven / registerDistanceDriven  | CAR_DRIVE  |
 * | EvBattery  | getEvBatteryStateOfCharge / register…, getCurrentEvEfficiency | CAR_ENERGY |
 * | Fuel       | getFuelPercent / register…, getCurrentFuelEconomy  | CAR_ENERGY |
 *
 * 성능: 폴링하지 않습니다. 값이 바뀔 때 오는 구독(register) 콜백과, 출발·정차 순간의 1회 조회만 씁니다.
 * 구독을 먼저 걸고 현재 값을 읽어, 연결 직후의 변화를 놓치지 않습니다.
 * 에너지 신호는 설정한 차종(전기차 / 내연기관·하이브리드)에 맞는 도메인만 호출합니다.
 * 전기차에서 연료 API를 부르면 차량이 '지원하지 않는 속성' 오류를 돌려주기 때문입니다.
 * 효율 조회가 지원되지 않는 차량(에뮬레이터 포함)에서는 잔량 감소량 → 설정한 기본 효율 순으로 계산합니다.
 */
class PleosVehicleSignals(
    context: Context,
    private val monitor: DrivingStateMonitor,
    private val occupancyMapping: () -> OccupancyMapping = { OccupancyMapping.AAOS },
    private val energyKind: () -> EnergyKind = { EnergyKind.ELECTRIC },
    private val clock: () -> Long = System::currentTimeMillis
) : VehicleSignals {

    private val appContext = context.applicationContext
    private val _events = MutableSharedFlow<VehicleEvent>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<VehicleEvent> = _events.asSharedFlow()

    private val _statuses = MutableStateFlow<Map<String, SignalStatus>>(emptyMap())
    private val statusList = MutableStateFlow<List<SignalStatus>>(emptyList())
    override val statuses: StateFlow<List<SignalStatus>> = statusList.asStateFlow()

    private val _health = MutableStateFlow(SignalHealth())
    override val health: StateFlow<SignalHealth> = _health.asStateFlow()

    private var vehicle: Vehicle? = null
    private val bridge = SdkBridge { method, error ->
        Log.w(TAG, "$method failed", error)
        // 좌석은 좌석마다 따로 성공 줄이 있으므로 실패만 메서드 이름 그대로 둡니다.
        val label = if ("Seat" in method) method else SignalText.labelFor(method)
        status(label, connected = false, error = error.message ?: error.javaClass.simpleName)
        markFailure(method, error)
    }
    private val registrations = mutableListOf<SdkBridge.Registration>()
    private val seatLinks = java.util.concurrent.ConcurrentHashMap<SeatPosition, Link>()
    private val gearListener: (kr.gachitan.core.Gear) -> Unit = { g -> emit(VehicleEvent.GearChanged(g, clock())) }
    private var refreshers: List<() -> Unit> = emptyList()

    override fun start() {
        if (vehicle != null) return
        monitor.addGearListener(gearListener)
        // 이미 받은 기어가 있으면 바로 알려 줍니다(감시기는 앱 시작 때부터 켜져 있습니다).
        monitor.gear.value?.let(gearListener)
        _health.value = SignalHealth()
        _statuses.value = emptyMap()   // 다시 연결할 때 이전 차종의 오류 줄이 남지 않게 합니다.
        val v = try {
            Vehicle(appContext).also { it.initialize() }
        } catch (e: Exception) {
            Log.w(TAG, "Vehicle SDK initialize failed", e)
            status("Vehicle SDK", connected = false, error = e.message ?: e.javaClass.simpleName)
            val link = classify(e)
            _health.value = SignalHealth(service = link, odometer = link, seats = link)
            return
        }
        vehicle = v
        status("Vehicle SDK", connected = true, raw = "initialized")
        _health.value = _health.value.copy(service = Link.OK)

        subscribeSeats(v.getSeat())
        subscribeOdometer(v.getOdometer())
        subscribeEnergy(v.getEvBattery(), v.getFuel())
    }

    override fun stop() {
        monitor.removeGearListener(gearListener)
        registrations.forEach { runCatching { bridge.unregister(it) } }
        registrations.clear()
        refreshers = emptyList()
        seatLinks.clear()
        unsupported.clear()
        // 콜백이 남아 있는 상태에서 release하지 않도록 구독을 먼저 해제했습니다.
        runCatching { vehicle?.release() }
        vehicle = null
    }

    /** 출발·정차 때 효율·잔량·계기판을 다시 읽습니다. 폴링하지 않고 이 순간에만 1회 조회합니다. */
    override fun refresh() {
        refreshers.forEach { runCatching { it() } }
    }

    /** 차량이 지원하지 않는다고 답한 메서드. 출발·정차 때마다 같은 오류를 반복해 부르지 않습니다. */
    private val unsupported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun markFailure(method: String, error: Throwable) {
        val link = classify(error)
        if (link == Link.UNSUPPORTED) unsupported += method
        _health.value = when {
            "Seat" in method -> _health.value.copy(seats = combinedSeatLink(link))
            "DistanceDriven" in method -> _health.value.copy(odometer = link)
            else -> _health.value
        }
    }

    private fun combinedSeatLink(failure: Link): Link =
        if (seatLinks.values.any { it == Link.OK }) Link.OK else failure

    private fun classify(e: Throwable): Link = when {
        e is SecurityException || (e.message ?: "").contains("permission", ignoreCase = true) -> Link.DENIED
        e is NoSuchMethodException || e is UnsupportedOperationException -> Link.UNSUPPORTED
        (e.message ?: "").let { m ->
            m.contains("NOT_SUPPORTED", ignoreCase = true) || m.contains("carPropertyConfig", ignoreCase = true)
        } -> Link.UNSUPPORTED
        else -> Link.FAILED
    }

    // ------------------------------------------------------------------ 구독

    private fun subscribeSeats(seat: Any) {
        SeatPosition.passengerSeats.forEach { position ->
            val area = seatArea(position) ?: run {
                status(seatName(position), connected = false, error = "이 차량에 없는 좌석")
                return@forEach
            }
            val onValue: (Any?) -> Unit = { raw ->
                status(seatName(position), connected = true, raw = ValueDecoders.describe(raw))
                seatLinks[position] = Link.OK
                _health.value = _health.value.copy(seats = Link.OK)
                ValueDecoders.occupied(raw, occupancyMapping())?.let { occupied ->
                    emit(VehicleEvent.SeatOccupancyChanged(position, occupied, clock()))
                }
            }
            // 구독을 먼저 걸고 현재 값을 읽어, 그 사이의 변화를 놓치지 않습니다.
            bridge.register(seat, "registerSeatOccupancy", area, onValue)?.let(registrations::add)
            bridge.get(seat, "getCurrentSeatOccupancy", area, onValue)
        }
    }

    private fun subscribeOdometer(odometer: Any) {
        val onValue: (Any?) -> Unit = { raw ->
            status(ODOMETER, connected = true, raw = ValueDecoders.describe(raw))
            ValueDecoders.number(raw)?.let { km ->
                _health.value = _health.value.copy(odometer = Link.OK)
                emit(VehicleEvent.OdometerChanged(km, clock()))
            }
        }
        bridge.register(odometer, "registerDistanceDriven", onValue = onValue)?.let(registrations::add)
        bridge.get(odometer, "getCurrentDistanceDriven", onValue = onValue)
        refreshers = refreshers + { bridge.get(odometer, "getCurrentDistanceDriven", onValue = onValue); Unit }
    }

    private fun subscribeEnergy(ev: Any, fuel: Any) {
        fun level(kind: EnergyKind, label: String): (Any?) -> Unit = { raw ->
            status(label, connected = true, raw = ValueDecoders.describe(raw))
            ValueDecoders.number(raw)?.let { emit(VehicleEvent.EnergyLevelChanged(kind, it, clock())) }
        }
        fun efficiency(kind: EnergyKind, label: String): (Any?) -> Unit = { raw ->
            status(label, connected = true, raw = ValueDecoders.describe(raw))
            ValueDecoders.number(raw)?.takeIf { it > 0 }?.let {
                emit(VehicleEvent.EfficiencyReported(kind, it, clock()))
            }
        }
        // 효율은 구독 API가 없으므로 출발·정차 때마다 다시 읽습니다(한 번 받은 값을 끝까지 쓰지 않음).
        // 효율 조회를 차량이 지원하지 않으면(속성 없음) 그 뒤로는 다시 부르지 않습니다.
        when (energyKind()) {
            EnergyKind.ELECTRIC -> {
                val soc = level(EnergyKind.ELECTRIC, SOC)
                val evEff = efficiency(EnergyKind.ELECTRIC, EV_EFF)
                bridge.register(ev, "registerEvBatteryStateOfCharge", onValue = soc)?.let(registrations::add)
                bridge.get(ev, "getEvBatteryStateOfCharge", onValue = soc)
                bridge.get(ev, "getCurrentEvEfficiency", onValue = evEff)
                refreshers = refreshers + listOf(
                    { bridge.get(ev, "getEvBatteryStateOfCharge", onValue = soc); Unit },
                    { if (EV_EFF_METHOD !in unsupported) bridge.get(ev, EV_EFF_METHOD, onValue = evEff); Unit }
                )
            }
            EnergyKind.FUEL -> {
                val fuelLevel = level(EnergyKind.FUEL, FUEL)
                val fuelEff = efficiency(EnergyKind.FUEL, FUEL_EFF)
                bridge.register(fuel, "registerFuelPercent", onValue = fuelLevel)?.let(registrations::add)
                bridge.get(fuel, "getFuelPercent", onValue = fuelLevel)
                bridge.get(fuel, FUEL_EFF_METHOD, onValue = fuelEff)
                refreshers = refreshers + listOf(
                    { bridge.get(fuel, "getFuelPercent", onValue = fuelLevel); Unit },
                    { if (FUEL_EFF_METHOD !in unsupported) bridge.get(fuel, FUEL_EFF_METHOD, onValue = fuelEff); Unit }
                )
            }
        }
    }

    // ------------------------------------------------------------------ 도우미

    private fun seatArea(position: SeatPosition): SeatArea? =
        runCatching { SeatArea(SeatValue.valueOf(position.sdkCode)) }.getOrNull()

    private fun emit(event: VehicleEvent) {
        _events.tryEmit(event)
    }

    private fun status(name: String, connected: Boolean, raw: String? = null, error: String? = null) =
        putStatus(SignalStatus(name, connected, raw, error))

    /** 다른 신호원(위치·내비)도 진단 화면에 함께 보이도록 상태를 받습니다. */
    fun reportStatus(s: SignalStatus) = putStatus(s)

    private fun putStatus(s: SignalStatus) {
        _statuses.update { it + (s.name to s) }
        statusList.value = (_statuses.value.values + monitor.statuses.value).sortedBy { it.name }
    }

    private fun seatName(p: SeatPosition) = "좌석 탑승 · ${p.label}"

    private companion object {
        const val TAG = "PleosVehicleSignals"
        const val ODOMETER = "계기판 누적거리 (Odometer)"
        const val SOC = "배터리 잔량 (EvBattery SOC)"
        const val EV_EFF = "전비 (EvBattery)"
        const val FUEL = "연료 잔량 (Fuel)"
        const val FUEL_EFF = "연비 (Fuel)"
        const val EV_EFF_METHOD = "getCurrentEvEfficiency"
        const val FUEL_EFF_METHOD = "getCurrentFuelEconomy"
    }
}
