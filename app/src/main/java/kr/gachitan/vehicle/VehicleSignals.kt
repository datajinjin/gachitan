package kr.gachitan.vehicle

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kr.gachitan.core.VehicleEvent

/** 차량 신호 하나의 연결 상태. 진단 화면에 그대로 보여 줍니다. */
data class SignalStatus(
    val name: String,
    val connected: Boolean,
    val lastRawValue: String? = null,
    val error: String? = null
)

/** 신호 묶음 하나의 상태. */
enum class Link {
    /** 아직 값을 받지 못함(연결 중). */
    WAITING,
    OK,
    /** 권한이 없어 거부됨. */
    DENIED,
    /** 이 차량·SDK 버전에 없는 기능. */
    UNSUPPORTED,
    /** 그 밖의 오류. */
    FAILED;

    val isProblem: Boolean get() = this == DENIED || this == UNSUPPORTED || this == FAILED
}

/** 정산에 필요한 신호들의 상태. 화면의 '연결 오류' 상태를 이것으로 정합니다. */
data class SignalHealth(
    val service: Link = Link.WAITING,
    val odometer: Link = Link.WAITING,
    val seats: Link = Link.WAITING
)

/**
 * 앱이 차량에서 받는 운행 사실의 입구.
 * 구현체: [CombinedVehicleSignals](실차·에뮬레이터 VHAL), [DemoVehicleSignals](화면 아래 시뮬레이터).
 * 두 입력원은 섞이지 않습니다. 앱은 한 번에 하나의 입력원과 그 입력원 전용 기록만 씁니다.
 */
interface VehicleSignals {
    val events: Flow<VehicleEvent>
    val statuses: StateFlow<List<SignalStatus>>
    val health: StateFlow<SignalHealth>

    fun start()
    fun stop()

    /** 출발·정차 순간에 잔량·효율·계기판을 다시 읽습니다(효율을 한 번만 읽고 끝까지 쓰지 않도록). */
    fun refresh() {}

    /** 출발·정차 순간에 현재 위치를 한 번 요청합니다(장소 이름용). */
    fun requestLocationFix() {}
}
