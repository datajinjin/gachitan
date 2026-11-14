package kr.gachitan.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.car.VehiclePropertyIds
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.gachitan.core.Gear
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 주행 상태 감시(AAOS 표준 API). 데모 모드에서도 항상 켜 둡니다.
 * - 주행 규제: Pleos 주행 규제 개발 가이드대로 CarUxRestrictionsManager를 구독하고,
 *   isRequiresDistractionOptimization이 true면 화면 입력을 막습니다.
 * - 기어: Pleos SDK API 정책상 표준 데이터(기어 등)는 AAOS Car API로 읽습니다.
 * 두 신호 모두 받지 못하면 [restricted]·[gear]가 null로 남고, 화면은 안전 쪽(입력 잠금)으로 동작합니다.
 */
class DrivingStateMonitor(context: Context) {
    private val appContext = context.applicationContext
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private val gearListeners = CopyOnWriteArrayList<(Gear) -> Unit>()

    private val _restricted = MutableStateFlow<Boolean?>(null)
    /** 주행 규제 중인지. null = 규제 서비스에 연결하지 못함. */
    val restricted: StateFlow<Boolean?> = _restricted.asStateFlow()

    private val _gear = MutableStateFlow<Gear?>(null)
    /** 마지막 기어. null = 기어 신호를 받지 못함. */
    val gear: StateFlow<Gear?> = _gear.asStateFlow()

    private val _statuses = MutableStateFlow<List<SignalStatus>>(emptyList())
    val statuses: StateFlow<List<SignalStatus>> = _statuses.asStateFlow()

    fun addGearListener(listener: (Gear) -> Unit) { gearListeners += listener }
    fun removeGearListener(listener: (Gear) -> Unit) { gearListeners -= listener }

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val raw = (value.value as? Int) ?: return
            publishGear(raw)
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            status(SignalStatus(GEAR, false, error = "오류 이벤트"))
        }
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions ->
        _restricted.value = restrictions.isRequiresDistractionOptimization
    }

    fun start() {
        if (car != null) return
        val c = try {
            Car.createCar(appContext) ?: error("Car service unavailable")
        } catch (e: Exception) {
            Log.w(TAG, "AAOS car service unavailable", e)
            status(SignalStatus(UX, false, error = e.message ?: e.javaClass.simpleName))
            return
        }
        car = c
        // 규제와 기어는 따로 시도합니다. 한쪽이 실패해도 다른 쪽은 씁니다.
        try {
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = ux
            ux.registerListener(uxListener)
            _restricted.value = ux.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: true
            status(SignalStatus(UX, true, if (_restricted.value == true) "규제 중" else "규제 없음"))
        } catch (e: Exception) {
            Log.w(TAG, "UX restrictions unavailable", e)
            status(SignalStatus(UX, false, error = e.message ?: e.javaClass.simpleName))
        }
        try {
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            propertyManager = pm
            // 구독을 먼저 걸고 현재 값을 읽어, 그 사이의 변화를 놓치지 않습니다.
            pm.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            runCatching { pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) }.onSuccess(::publishGear)
        } catch (e: Exception) {
            Log.w(TAG, "gear unavailable", e)
            status(SignalStatus(GEAR, false, error = e.message ?: e.javaClass.simpleName))
        }
    }

    fun stop() {
        runCatching { propertyManager?.unregisterCallback(gearCallback) }
        runCatching { uxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        car = null; propertyManager = null; uxManager = null
    }

    private fun publishGear(raw: Int) {
        val g = decodeGear(raw)
        _gear.value = g
        status(SignalStatus(GEAR, true, "$raw (${g.name})"))
        gearListeners.forEach { it(g) }
    }

    private fun status(s: SignalStatus) {
        _statuses.value = (_statuses.value.filterNot { it.name == s.name } + s).sortedBy { it.name }
    }

    companion object {
        private const val TAG = "DrivingStateMonitor"
        const val GEAR = "기어 (AAOS GEAR_SELECTION)"
        const val UX = "주행 규제 (CarUxRestrictions)"

        /** android.car.VehicleGear 상수: NEUTRAL=1, REVERSE=2, PARK=4, DRIVE=8, 수동 1~9단=16 이상. */
        fun decodeGear(raw: Int): Gear = when {
            raw == 4 -> Gear.PARK
            raw == 2 -> Gear.REVERSE
            raw == 1 -> Gear.NEUTRAL
            raw >= 8 -> Gear.DRIVE
            else -> Gear.UNKNOWN
        }
    }
}
