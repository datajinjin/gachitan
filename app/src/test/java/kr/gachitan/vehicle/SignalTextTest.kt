package kr.gachitan.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 진단 화면 문구: 에뮬레이터에서 실제로 받은 오류 원문을 사람이 읽는 말로 바꾸는지. */
class SignalTextTest {
    @Test
    fun `지원하지 않는 효율 조회는 기본 효율로 계산한다고 알린다`() {
        val label = SignalText.labelFor("getCurrentEvEfficiency")
        assertEquals("전비 (EvBattery)", label)
        assertEquals("이 차량에서 제공하지 않음 · 설정한 기본 효율로 계산",
            SignalText.friendlyError(label, "ERROR_NOT_SUPPORTED_PROPERTY"))
    }

    @Test
    fun `속성 설정이 없다는 예외도 미지원으로 본다`() {
        val msg = "java.lang.IllegalArgumentException: property ID is not in carPropertyConfig list, and so it is not supported: 0x21400be5"
        assertTrue(SignalText.friendlyError(SignalText.labelFor("getFuelPercent"), msg).startsWith("이 차량에서 제공하지 않음"))
    }

    @Test
    fun `권한 오류와 좌석 값`() {
        assertEquals("권한 없음 · 장소 이름은 직접 붙이기",
            SignalText.friendlyError("위치 (FusedLocation)", "need Permission pleos.car.permission.FUSED_LOCATION"))
        assertEquals("탑승", SignalText.friendlyValue("좌석 탑승 · 조수석", "true"))
        assertEquals("비어 있음", SignalText.friendlyValue("좌석 탑승 · 조수석", "false"))
        assertEquals("100", SignalText.friendlyValue("배터리 잔량 (EvBattery SOC)", "100"))
    }
}
