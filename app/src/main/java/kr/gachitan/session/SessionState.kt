package kr.gachitan.session

import kr.gachitan.core.Gear
import kr.gachitan.core.Journey
import kr.gachitan.core.JourneySummary
import kr.gachitan.core.RecordGap
import kr.gachitan.core.SeatPosition
import kr.gachitan.core.Settlement
import kr.gachitan.core.TripLog
import kr.gachitan.core.TripRecorder

/** 좌석 하나의 화면 정보. */
data class SeatView(
    val seat: SeatPosition,
    /** 센서가 '누군가 앉아 있다'고 알려 온 상태. 사람을 알아보는 것은 아닙니다. */
    val occupied: Boolean,
    val sittingId: String?,
    /** 사람이 붙인 이름. 없으면 null. */
    val name: String?,
    /** 정차 뒤 다시 앉아 앞사람 이름을 제안한 상태. */
    val needsReview: Boolean,
    /** 빈 좌석에 미리 정해 둔 이름(다음에 앉는 사람). */
    val pendingName: String?,
    /** 이번 운행에서 이 좌석에 지금 앉은 사람이 탄 거리. */
    val km: Double
)

/** 통행료·주차비 한 건. */
data class ExtraView(
    val index: Int,
    val label: String,
    val amountWon: Long,
    val payerKeys: Set<String>,
    val payerNames: List<String>
)

/** 정산을 확정한 운행. 묶음에 들어갔으면 운행별 송금 요청을 하지 않습니다(중복 청구 방지). */
data class SettledTrip(
    val settlement: Settlement,
    val bundleTitle: String?,
    val dayNumber: Int?,
    val log: TripLog
) {
    val inBundle: Boolean get() = bundleTitle != null
}

/** 화면이 그리는 데 필요한 모든 것. */
data class SessionState(
    val phase: Phase = Phase.READY,
    /** 데모 시뮬레이터 기록인지. */
    val demo: Boolean = false,
    val seats: List<SeatView> = emptyList(),
    val occupantCount: Int = 1,
    val liveKm: Double = 0.0,
    val odometerKnown: Boolean = false,
    val lastGear: Gear? = null,
    val currentStopName: String? = null,
    /** 이번 운행에 탄 모든 앉은 기간(정산 확인 화면). */
    val sittings: List<TripRecorder.SittingView> = emptyList(),
    /** 사람이 확인해야 할 항목 수(재탑승 확인). */
    val reviewCount: Int = 0,
    /** 비용을 나눌 수 있는 사람(키 → 이름). */
    val participants: Map<String, String> = emptyMap(),
    val defaultExtraPayers: Set<String> = emptySet(),
    val extras: List<ExtraView> = emptyList(),
    /** 정차·정산 확인 중 지금 끝내면 나올 정산. */
    val preview: Settlement? = null,
    val gaps: List<RecordGap> = emptyList(),
    val settled: SettledTrip? = null,
    /** 오래 세워 둔 뒤 출발해 앞 운행을 자동으로 확정한 횟수(이 세션 동안). */
    val autoSettledCount: Int = 0,
    val journey: Journey? = null,
    val journeySummary: JourneySummary? = null,
    val currentDayNumber: Int = 1,
    val endedJourney: JourneySummary? = null,
    /** 정산을 확정한 운행(최신순). */
    val trips: List<TripLog> = emptyList()
) {
    enum class Phase {
        /** 출발 전: 기어 D를 기다림. */
        READY,
        /** 기어는 D인데 계기판 값을 아직 받지 못함. 이 동안도 주행으로 보고 입력을 잠급니다. */
        WAITING_FOR_ODOMETER,
        DRIVING,
        /** 운행 중 정차(P). 다시 출발하면 같은 운행이 이어집니다. */
        PARKED,
        /** 정차 중 '운행 끝내기'를 눌러 정산 내용을 확인하는 단계. 출발하면 취소되고 기록이 이어집니다. */
        REVIEW,
        SETTLED
    }

    val isMoving: Boolean get() = phase == Phase.DRIVING || phase == Phase.WAITING_FOR_ODOMETER
}
