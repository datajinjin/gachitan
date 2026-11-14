package kr.gachitan.ui.model

import kr.gachitan.core.Settlement
import kr.gachitan.core.TripRecorder

/** 한 사람의 탑승 구간 막대. 위치는 운행 전체 거리 대비 0..1입니다. */
data class TimelineRow(
    val riderId: String,
    val name: String,
    val isDriver: Boolean,
    val spans: List<ClosedFloatingPointRange<Double>>,
    val km: Double,
    val amountWon: Long,
    /** 이 사람이 앉았던 기간들(이름 고치기용). 운전자는 비어 있습니다. */
    val sittingIds: List<String>,
    val needsReview: Boolean,
    /** 이름을 붙이지 않은 동승자(좌석 이름으로 표시 중). */
    val unnamed: Boolean
)

data class TimelineTick(val fraction: Double, val label: String)

data class TimelineModel(val rows: List<TimelineRow>, val ticks: List<TimelineTick>, val totalKm: Double)

/**
 * 정산을 사람별 탑승 막대로 바꿉니다. 운전자가 맨 위, 나머지는 탄 순서대로.
 * 정차 지점은 세로 눈금으로 표시합니다.
 */
fun timelineOf(settlement: Settlement, views: List<TripRecorder.SittingView>): TimelineModel {
    val r = settlement.record
    val total = (r.endKm - r.startKm).takeIf { it > 0 } ?: r.distanceKm
    fun frac(km: Double) = if (total <= 0) 0.0 else ((km - r.startKm) / total).coerceIn(0.0, 1.0)
    val reviewIds = views.filter { it.needsReview }.map { it.id }.toSet()
    val firstStart = r.sittings.groupBy { it.riderId }.mapValues { (_, list) -> list.minOf { it.startKm } }

    val rows = settlement.shares.map { share ->
        val mine = r.sittings.filter { it.riderId == share.rider.id }.sortedBy { it.startKm }
        TimelineRow(
            riderId = share.rider.id,
            name = share.rider.name,
            isDriver = share.isDriver,
            spans = if (share.isDriver) listOf(0.0..1.0) else mine.map { frac(it.startKm)..frac(it.endKm) },
            km = share.distanceKm,
            amountWon = share.amountWon,
            sittingIds = mine.map { it.id },
            needsReview = mine.any { it.id in reviewIds },
            unnamed = share.rider.id.startsWith("guest-")
        )
    }.sortedWith(compareBy<TimelineRow> { !it.isDriver }.thenBy { firstStart[it.riderId] ?: 0.0 })

    val ticks = r.stops.mapIndexedNotNull { i, stop ->
        val f = frac(stop.km)
        if (f <= 0.0 || f >= 1.0) null else TimelineTick(f, stop.place?.displayName("")?.takeIf { it.isNotBlank() } ?: "정차 ${i + 1}")
    }.distinctBy { it.fraction }
    return TimelineModel(rows, ticks, total)
}
