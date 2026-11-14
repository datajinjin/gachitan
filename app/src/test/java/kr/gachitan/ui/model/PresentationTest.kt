package kr.gachitan.ui.model

import kr.gachitan.core.Gear
import kr.gachitan.session.SessionState.Phase
import kr.gachitan.vehicle.Link
import kr.gachitan.vehicle.SignalHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresentationTest {
    @Test
    fun `거리 신호를 기다리는 동안에도 잠금이 유지된다`() {
        assertEquals(LockReason.RECORDING, lockReason(false, Gear.DRIVE, Phase.WAITING_FOR_ODOMETER, demo = false))
        assertEquals(LockReason.RECORDING, lockReason(false, Gear.PARK, Phase.WAITING_FOR_ODOMETER, demo = true))
    }

    @Test
    fun `플랫폼 규제는 다른 어떤 상태보다 우선한다`() {
        assertEquals(LockReason.PLATFORM, lockReason(true, Gear.PARK, Phase.READY, demo = true))
    }

    @Test
    fun `기어가 D면 기록 전이라도 잠근다`() {
        assertEquals(LockReason.GEAR, lockReason(false, Gear.DRIVE, Phase.READY, demo = false))
    }

    @Test
    fun `실차에서 규제와 기어를 모두 모르면 안전하게 잠근다`() {
        assertEquals(LockReason.UNKNOWN, lockReason(null, null, Phase.READY, demo = false))
        assertNull(lockReason(null, Gear.PARK, Phase.READY, demo = false))   // 기어가 P로 확인되면 풀림
        assertNull(lockReason(null, null, Phase.READY, demo = true))         // 시뮬레이터는 자체 기어를 씀
    }

    @Test
    fun `권한 거부와 주행거리 없음을 구분한다`() {
        assertEquals(ConnectionIssue.DENIED, connectionIssue(SignalHealth(Link.DENIED, Link.DENIED, Link.DENIED), false, false))
        assertEquals(ConnectionIssue.NO_ODOMETER, connectionIssue(SignalHealth(Link.OK, Link.UNSUPPORTED, Link.OK), false, false))
        assertEquals(ConnectionIssue.NO_SEATS, connectionIssue(SignalHealth(Link.OK, Link.OK, Link.FAILED), true, false))
        assertEquals(ConnectionIssue.NONE, connectionIssue(SignalHealth(Link.OK, Link.OK, Link.OK), true, false))
    }
}
