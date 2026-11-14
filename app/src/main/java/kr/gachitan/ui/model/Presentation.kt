package kr.gachitan.ui.model

import kr.gachitan.core.Gear
import kr.gachitan.session.SessionState
import kr.gachitan.session.SessionState.Phase
import kr.gachitan.vehicle.Link
import kr.gachitan.vehicle.SignalHealth

/**
 * 화면 판단 규칙. Android에 의존하지 않아 JVM 테스트로 검증합니다.
 */

/** 입력을 잠그는 이유. null이면 잠그지 않습니다. */
enum class LockReason {
    /** 플랫폼 주행 규제(CarUxRestrictions)가 켜짐. */
    PLATFORM,
    /** 운행 기록 중 주행(계기판 값을 기다리는 동안 포함). */
    RECORDING,
    /** 기어가 D·R. */
    GEAR,
    /** 규제 신호와 기어 신호를 모두 받지 못해 주행 여부를 알 수 없음. 안전 쪽으로 잠급니다. */
    UNKNOWN
}

/**
 * 주행 잠금 규칙(우선순위 순).
 * 1) 플랫폼이 규제를 요구하면 잠급니다. 시뮬레이터 패널도 숨깁니다.
 * 2) 기록 중 주행이거나 계기판 값을 기다리는 중이면 잠급니다(거리 대기 중에 잠금이 풀리지 않음).
 * 3) 기어가 D·R이면 잠급니다.
 * 4) 실차 모드에서 규제·기어 어느 쪽도 받지 못하면 잠급니다.
 */
fun lockReason(restricted: Boolean?, gear: Gear?, phase: Phase, demo: Boolean): LockReason? = when {
    restricted == true -> LockReason.PLATFORM
    phase == Phase.DRIVING || phase == Phase.WAITING_FOR_ODOMETER -> LockReason.RECORDING
    gear == Gear.DRIVE || gear == Gear.REVERSE -> LockReason.GEAR
    !demo && restricted == null && (gear == null || gear == Gear.UNKNOWN) -> LockReason.UNKNOWN
    else -> null
}

/** 정산에 영향을 주는 연결 문제. */
enum class ConnectionIssue {
    NONE,
    /** 아직 첫 값을 기다리는 중(정상적인 시작 단계). */
    CONNECTING,
    /** 권한이 없어 차량 정보를 읽지 못함. */
    DENIED,
    /** 차량 정보 서비스에 연결하지 못함. */
    UNAVAILABLE,
    /** 주행거리를 받지 못해 기록할 수 없음. */
    NO_ODOMETER,
    /** 좌석 정보를 받지 못해 탑승 구간을 나눌 수 없음(운전자 혼자로 기록됨). */
    NO_SEATS
}

fun connectionIssue(health: SignalHealth, odometerKnown: Boolean, demo: Boolean): ConnectionIssue = when {
    demo -> ConnectionIssue.NONE
    health.service == Link.DENIED || health.odometer == Link.DENIED -> ConnectionIssue.DENIED
    health.service.isProblem -> ConnectionIssue.UNAVAILABLE
    health.odometer.isProblem && !odometerKnown -> ConnectionIssue.NO_ODOMETER
    health.seats.isProblem -> ConnectionIssue.NO_SEATS
    !odometerKnown -> ConnectionIssue.CONNECTING
    else -> ConnectionIssue.NONE
}

/** 연결 문제를 사용자가 할 수 있는 행동과 함께 짧게 설명합니다. 개발 용어를 쓰지 않습니다. */
data class IssueCopy(val title: String, val body: String, val blocking: Boolean)

fun issueCopy(issue: ConnectionIssue): IssueCopy? = when (issue) {
    ConnectionIssue.NONE -> null
    ConnectionIssue.CONNECTING -> IssueCopy("차량 정보 연결 중", "잠시 후 주행거리가 표시됩니다.", blocking = false)
    ConnectionIssue.DENIED -> IssueCopy("차량 정보 접근이 허용되지 않았어요",
        "차량 설정의 앱 권한에서 '같이 탄 만큼'의 차량 정보 접근을 허용한 뒤 다시 연결하세요.", blocking = true)
    ConnectionIssue.UNAVAILABLE -> IssueCopy("차량 정보에 연결하지 못했어요",
        "시동을 켠 상태에서 다시 연결하세요. 계속되면 차량을 껐다 켜 주세요.", blocking = true)
    ConnectionIssue.NO_ODOMETER -> IssueCopy("주행거리를 받지 못하고 있어요",
        "주행거리 없이는 기록할 수 없습니다. 다시 연결하거나 차량을 껐다 켜 주세요.", blocking = true)
    ConnectionIssue.NO_SEATS -> IssueCopy("좌석 정보를 받지 못하고 있어요",
        "이 상태로 출발하면 운전자 혼자 탄 것으로 기록됩니다.", blocking = false)
}

/** 화면 맨 위 상태 이름. 화면의 첫 단어로 쓰입니다. */
fun phaseTitle(s: SessionState): String = when (s.phase) {
    Phase.READY -> "출발 전"
    Phase.WAITING_FOR_ODOMETER -> "주행거리 확인 중"
    Phase.DRIVING -> "기록 중"
    Phase.PARKED -> "정차 중"
    Phase.REVIEW -> "정산 확인"
    Phase.SETTLED -> "정산 완료"
}

/** 잠금 화면의 한 줄 안내. */
fun lockCaption(reason: LockReason): String = when (reason) {
    LockReason.PLATFORM, LockReason.RECORDING, LockReason.GEAR -> "정차하면 이름과 정산을 확인할 수 있어요"
    LockReason.UNKNOWN -> "주행 상태를 확인하지 못해 조작을 잠갔어요. P단에서 다시 확인합니다"
}
