package kr.gachitan.vehicle

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.gachitan.core.EnergyKind
import kr.gachitan.core.Gear
import kr.gachitan.core.Place
import kr.gachitan.core.PlaceSource
import kr.gachitan.core.SeatPosition
import kr.gachitan.core.VehicleEvent

/**
 * 시연용 차량 시뮬레이터. 데모 모드에서는 이것만 입력원이 되고(실제 차량 신호는 받지 않음),
 * 기록도 데모 전용 파일에 따로 저장됩니다. 실제 운행 기록과 섞이지 않습니다.
 */
class DemoVehicleSignals(private val systemClock: () -> Long = System::currentTimeMillis) : VehicleSignals {
    /** 여러 날 시연용: 데모 신호의 시각을 하루씩 앞당깁니다. */
    private var dayOffset = 0
    /** 시뮬레이터 주행 시간: 시속 60km로 달린 것처럼 1km마다 1분씩 시계를 앞당겨 정산 화면의 시각이 실제처럼 보입니다. */
    private var driveOffsetMs = 0L
    private fun clock(): Long = systemClock() + dayOffset * 86_400_000L + driveOffsetMs

    private val _events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<VehicleEvent> = _events.asSharedFlow()
    override val statuses: StateFlow<List<SignalStatus>> =
        MutableStateFlow(listOf(SignalStatus("데모 시뮬레이터", true, "실제 차량 신호를 받지 않습니다")))
    override val health: StateFlow<SignalHealth> = MutableStateFlow(SignalHealth(Link.OK, Link.OK, Link.OK))

    private val _state = MutableStateFlow(DemoState())
    val state: StateFlow<DemoState> = _state.asStateFlow()

    data class DemoState(
        val day: Int = 1,
        val gear: Gear = Gear.PARK,
        val km: Double = 12_000.0,
        val soc: Double = 80.0,
        val occupied: Set<SeatPosition> = emptySet()
    )

    /** 실제 차처럼 연결 직후 현재 상태(계기판·잔량·좌석·기어)를 한 번 알려 줍니다. */
    override fun start() {
        val s = _state.value
        emit(VehicleEvent.OdometerChanged(s.km, clock()))
        emit(VehicleEvent.EnergyLevelChanged(EnergyKind.ELECTRIC, s.soc, clock()))
        SeatPosition.passengerSeats.forEach { seat -> emit(VehicleEvent.SeatOccupancyChanged(seat, seat in s.occupied, clock())) }
        emit(VehicleEvent.GearChanged(s.gear, clock()))
    }

    override fun stop() = Unit

    override fun refresh() {
        val s = _state.value
        emit(VehicleEvent.OdometerChanged(s.km, clock()))
        emit(VehicleEvent.EnergyLevelChanged(EnergyKind.ELECTRIC, s.soc, clock()))
    }

    /** 저장된 데모 기록의 계기판이 더 크면 거기서 이어 갑니다(계기판은 줄어들지 않습니다). */
    fun continueFrom(km: Double?) {
        if (km != null && km > _state.value.km) _state.value = _state.value.copy(km = km)
    }

    fun nextDay() {
        dayOffset++
        // 숙소에서 하룻밤 충전했다고 보고 배터리를 다시 채웁니다.
        val s = _state.value.copy(day = dayOffset + 1, soc = 80.0)
        _state.value = s
        emit(VehicleEvent.EnergyLevelChanged(EnergyKind.ELECTRIC, s.soc, clock()))
    }

    fun reset() {
        dayOffset = 0
        val old = _state.value
        val s = DemoState(km = old.km)
        _state.value = s
        old.occupied.forEach { seat -> emit(VehicleEvent.SeatOccupancyChanged(seat, false, clock())) }
        emit(VehicleEvent.OdometerChanged(s.km, clock()))
        emit(VehicleEvent.EnergyLevelChanged(EnergyKind.ELECTRIC, s.soc, clock()))
        emit(VehicleEvent.GearChanged(s.gear, clock()))
    }

    fun setGear(gear: Gear) {
        val s = _state.value
        _state.value = s.copy(gear = gear)
        emit(VehicleEvent.GearChanged(gear, clock()))
    }

    fun toggleSeat(seat: SeatPosition) {
        val s = _state.value
        val on = seat !in s.occupied
        _state.value = s.copy(occupied = if (on) s.occupied + seat else s.occupied - seat)
        emit(VehicleEvent.SeatOccupancyChanged(seat, on, clock()))
    }

    /** 내비 목적지 도착을 흉내 냅니다. */
    fun arriveAt(name: String, latitude: Double, longitude: Double) {
        emit(VehicleEvent.PlaceObserved(Place(name, null, latitude, longitude, PlaceSource.NAVI), clock()))
    }

    /** 주행: 거리와 함께 배터리도 약 5km/kWh, 70kWh 기준으로 줄입니다. */
    fun drive(km: Double) {
        val s = _state.value
        val soc = (s.soc - km / 5.0 / 70.0 * 100.0).coerceAtLeast(0.0)
        _state.value = s.copy(km = s.km + km, soc = soc)
        if (s.gear == Gear.DRIVE) driveOffsetMs += (km * 60_000).toLong()
        emit(VehicleEvent.OdometerChanged(s.km + km, clock()))
        emit(VehicleEvent.EnergyLevelChanged(EnergyKind.ELECTRIC, soc, clock()))
    }

    private fun emit(e: VehicleEvent) { _events.tryEmit(e) }
}

/** 시뮬레이터의 장소 버튼. */
object DemoPlaces {
    val presets = listOf(
        Triple("판교역", 37.3948, 127.1112),
        Triple("정자역", 37.3670, 127.1083),
        Triple("수원역", 37.2659, 127.0000),
        Triple("강릉 숙소", 37.7719, 128.9471),
        Triple("안목해변", 37.7724, 128.9669)
    )
}
