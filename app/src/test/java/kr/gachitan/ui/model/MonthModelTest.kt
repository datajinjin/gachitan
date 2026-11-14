package kr.gachitan.ui.model

import kr.gachitan.core.CostBasis
import kr.gachitan.core.LogShare
import kr.gachitan.core.TripLog
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class MonthModelTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(m: Int, d: Int) = ZonedDateTime.of(2026, m, d, 8, 0, 0, 0, seoul).toInstant().toEpochMilli()
    private fun log(ms: Long, bundle: String? = null, vararg p: Pair<String, Long>) = TripLog(
        "t$ms", ms, ms + 1, 20.0, p.sumOf { it.second } + 1000, p.sumOf { it.second } + 1000, 0, CostBasis.USER_EFFICIENCY,
        70.0, 5.0, 4.0, false, true, 0,
        listOf(LogShare("driver", "나", 20.0, 1000, true)) + p.map { (n, w) -> LogShare(n, n, 20.0, w, false, n.startsWith("뒷좌석")) },
        emptyList(), emptyList(), bundle
    )

    @Test
    fun `이번 달 개인 운행만 사람별로 더한다`() {
        val trips = listOf(
            log(at(11, 3), null, "민지" to 1_200L, "준호" to 900L),
            log(at(11, 10), null, "민지" to 1_500L, "뒷좌석 왼쪽 동승자" to 700L),
            log(at(11, 12), "주말 여행", "민지" to 9_000L),     // 여행 정산에서 따로
            log(at(10, 30), null, "민지" to 5_000L)             // 지난달
        )
        val t = monthTotals(trips, seoul, LocalDate.of(2026, 11, 15))
        assertEquals(listOf("민지", "준호"), t.map { it.name })
        assertEquals(2_700L, t[0].amountWon)
        assertEquals(2, t[0].rides)
        assertEquals(40.0, t[0].km, 1e-9)
    }

    @Test
    fun `받음 표시한 운행은 남은 금액에서 빠진다`() {
        val a = log(at(11, 3), null, "민지" to 1_200L)
        val b = log(at(11, 4), null, "민지" to 1_500L).let { it.withPaid("민지", true) }
        val t = monthTotals(listOf(a, b), seoul, LocalDate.of(2026, 11, 15)).single()
        assertEquals(2_700L, t.amountWon)
        assertEquals(1_200L, t.unpaidWon)
        assertEquals(setOf(a.id, b.id), t.tripIds)
        val text = monthRequestText(t, LocalDate.of(2026, 11, 15), "https://toss.me/driver/1200")
        assertEquals(true, text.contains("1,200원") && text.contains("toss.me"))
    }
}
