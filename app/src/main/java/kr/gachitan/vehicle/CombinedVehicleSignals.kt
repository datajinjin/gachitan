package kr.gachitan.vehicle

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kr.gachitan.core.VehicleEvent

/** 실제 차량 입력원: 차량 신호(Vehicle SDK·AAOS) + 장소 신호(내비·위치). 데모 신호는 섞지 않습니다. */
class CombinedVehicleSignals(
    private val vehicle: PleosVehicleSignals,
    private val places: PleosPlaceSignals
) : VehicleSignals {
    override val events: Flow<VehicleEvent> = merge(vehicle.events, places.events)
    override val statuses: StateFlow<List<SignalStatus>> = vehicle.statuses
    override val health: StateFlow<SignalHealth> = vehicle.health
    override fun start() { vehicle.start(); places.start() }
    override fun stop() { places.stop(); vehicle.stop() }
    override fun refresh() = vehicle.refresh()
    override fun requestLocationFix() = places.requestFix()
}
