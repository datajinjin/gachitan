package kr.gachitan.ui.model

import kr.gachitan.core.LogShare
import kr.gachitan.core.PaymentRequest
import kr.gachitan.ui.preview.SampleTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SettleModelTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val log = SampleTrip.log()

    @Test
    fun `규칙 한 줄과 사람별 둘째 줄`() {
        assertEquals("km당 60원 · 운전자 포함", ruleText(log))
        val me = log.shares.first { it.isDriver }
        val minji = log.shares.first { it.name == "민지" }
        val junho = log.shares.first { it.name == "준호" }
        assertEquals("80.0km · 운전 · 본인 부담", shareSubtitle(me, log))
        assertEquals("30.0km · 일부 구간", shareSubtitle(minji, log))
        assertEquals("80.0km", shareSubtitle(junho, log))
        assertEquals("3명 → 2명", peopleChangeText(log))
    }

    @Test
    fun `시간과 날짜 표기`() {
        assertEquals("14:10 — 15:30", timeRange(log.startedAtMillis, log.endedAtMillis, seoul))
        val today = LocalDate.of(2026, 11, 11)
        assertEquals("11월 11일 · 오늘", dateHeader(today, today))
        assertEquals("11월 10일 · 어제", dateHeader(today.minusDays(1), today))
        assertEquals("11월 9일 (월)", dateHeader(today.minusDays(2), today))
        assertEquals("2025년 11월 11일 (화)", dateHeader(LocalDate.of(2025, 11, 11), today))
    }

    @Test
    fun `기록은 날짜별, 최신 먼저`() {
        val a = log.copy(id = "a")
        val b = log.copy(id = "b", startedAtMillis = log.startedAtMillis + 3_600_000, endedAtMillis = log.endedAtMillis + 3_600_000)
        val c = log.copy(id = "c", startedAtMillis = log.startedAtMillis - 86_400_000, endedAtMillis = log.endedAtMillis - 86_400_000)
        val groups = groupByDate(listOf(a, c, b), seoul)
        assertEquals(2, groups.size)
        assertEquals(listOf("b", "a"), groups[0].second.map { it.id })
        assertEquals(listOf("c"), groups[1].second.map { it.id })
    }

    @Test
    fun `QR 안내는 실제 QR 내용과 맞는다`() {
        val minji = log.shares.first { it.name == "민지" }
        // 토스 아이디가 있으면: 링크에 아이디와 금액만
        val toss = PaymentRequest.payload(minji.name, minji.distanceKm, minji.amountWon, "나", "my.id", "계좌")
        assertEquals("https://toss.me/my.id/600", toss)
        assertTrue(qrContentsText("민지", "my.id", "계좌").contains("토스 아이디와 이 금액만"))
        // 없으면: 이름·거리·금액·받는 사람(+계좌)
        val text = PaymentRequest.payload(minji.name, minji.distanceKm, minji.amountWon, "나", null, "")
        assertTrue(text.contains("민지") && text.contains("30.0km") && text.contains("600원") && text.contains("받는 사람: 나"))
        assertFalse("다른 사람 금액이 들어가면 안 됨", text.contains("2,100원") || text.contains("준호"))
        assertFalse(qrContentsText("민지", null, "").contains("계좌"))
        assertTrue(qrContentsText("민지", null, "카카오 3333").contains("계좌 안내"))
    }

    @Test
    fun `이름 없는 동승자 표시`() {
        val guest = LogShare("guest-1", "뒷좌석 가운데", 80.0, 1_000, isDriver = false, unnamed = true)
        assertEquals("80.0km · 이름 없음", shareSubtitle(guest, log))
    }

    @Test
    fun `구간별 비용은 구간 거리 × km당 비용`() {
        val costs = bandCosts(log)
        assertEquals(listOf(1_800.0, 3_000.0), costs.map { it.costWon })
        assertEquals(listOf(600.0, 1_500.0), costs.map { it.perPersonWon })
    }
}

class SettleTextTest {
    private val seoul = java.time.ZoneId.of("Asia/Seoul")
    private fun at(d: Int, h: Int, m: Int) = java.time.ZonedDateTime.of(2026, 11, d, h, m, 0, 0, seoul).toInstant().toEpochMilli()

    @Test
    fun `자정을 넘긴 운행은 끝 시각에 날짜 차이를 붙인다`() {
        assertEquals("23:50 — 다음 날 00:20", timeRange(at(14, 23, 50), at(15, 0, 20), seoul))
        assertEquals("01:42 — 다음 날 01:40", timeRange(at(16, 1, 42), at(17, 1, 40), seoul))
        assertEquals("09:00 — 3일 뒤 10:00", timeRange(at(14, 9, 0), at(17, 10, 0), seoul))
    }

    @Test
    fun `반올림 문구는 운전자 이름과 무관하다`() {
        assertEquals(null, roundingText(0))
        assertEquals("반올림 차이 1원은 운전자 몫에서 맞췄어요(덜 부담).", roundingText(-1))
    }
}
