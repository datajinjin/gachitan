package kr.gachitan.ui.model

import kr.gachitan.core.TripLog
import java.time.LocalDate
import java.time.ZoneId

/**
 * 이번 달 한 사람의 운행 정산 합계(출퇴근 카풀처럼 한 달에 한 번 몰아서 받을 때).
 * [unpaidWon]은 운전자가 '받음'으로 표시하지 않은 금액입니다. 앱이 송금을 확인한 값이 아닙니다.
 */
data class MonthTotal(
    val name: String,
    val rides: Int,
    val km: Double,
    val amountWon: Long,
    val unpaidWon: Long = amountWon,
    val tripIds: Set<String> = emptySet()
) {
    val allPaid: Boolean get() = unpaidWon == 0L
}

/**
 * [today]가 속한 달에 시작한 운행의 동승자별 합계. 이름 없는 동승자와 여행(묶음) 운행은 뺍니다.
 * 여행 운행은 여행 정산에서 따로 주고받기 때문에 여기 더하면 두 번 청구됩니다.
 */
fun monthTotals(trips: List<TripLog>, zone: ZoneId, today: LocalDate): List<MonthTotal> {
    data class Row(val tripId: String, val name: String, val km: Double, val won: Long, val paid: Boolean)
    return trips.asSequence()
        .filter { !it.inBundle }
        .filter { dateOf(it.startedAtMillis, zone).let { d -> d.year == today.year && d.month == today.month } }
        .flatMap { log ->
            log.shares.asSequence().filter { !it.isDriver && !it.unnamed && it.amountWon > 0 }
                .map { Row(log.id, it.name, it.distanceKm, it.amountWon, it.riderId in log.paidIds) }
        }
        .groupBy { it.name }
        .map { (name, list) ->
            MonthTotal(name, list.size, list.sumOf { it.km }, list.sumOf { it.won },
                list.filter { !it.paid }.sumOf { it.won }, list.map { it.tripId }.toSet())
        }
        .sortedWith(compareByDescending<MonthTotal> { it.unpaidWon }.thenByDescending { it.amountWon }.thenBy { it.name })
}

/** 한 사람에게 보낼 이번 달 요청 문구(카카오톡 등에 붙여 넣기). 토스 링크가 있으면 금액을 담습니다. */
fun monthRequestText(t: MonthTotal, today: LocalDate, tossLink: String?): String = buildString {
    append("[같이 탄 만큼] ${today.monthValue}월 카풀 정산\n")
    append("${t.name}님 ${t.rides}회 · ${kr.gachitan.core.Formatting.km(t.km)} · ${kr.gachitan.core.Formatting.won(t.unpaidWon)}")
    if (tossLink != null) append("\n").append(tossLink)
}
