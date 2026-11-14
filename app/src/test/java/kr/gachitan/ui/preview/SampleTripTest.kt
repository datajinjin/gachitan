package kr.gachitan.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Test

/** 디자인 시안의 계산 사례가 실제 정산 엔진에서 그대로 나오는지 확인합니다(미리보기용 데이터). */
class SampleTripTest {
    @Test
    fun `km당 60원, 30km 3명 + 50km 2명 = 4,800원`() {
        val log = SampleTrip.log()
        assertEquals(80.0, log.distanceKm, 1e-9)
        assertEquals(60.0, log.wonPerKm, 1e-9)
        assertEquals(4_800L, log.totalWon)
        val won = log.shares.associate { it.name to it.amountWon }
        assertEquals(mapOf("나" to 2_100L, "준호" to 2_100L, "민지" to 600L), won)
        assertEquals(30.0, log.shares.first { it.name == "민지" }.distanceKm, 1e-9)
    }

    @Test
    fun `구간 막대 비율은 3 대 5`() {
        val bands = SampleTrip.log().bands()
        val total = bands.sumOf { it.km }
        assertEquals(listOf(0.375, 0.625), bands.map { it.km / total })
        assertEquals(listOf(3, 2), bands.map { it.people })
        assertEquals("민지 하차", bands[1].changeBefore)
    }
}
