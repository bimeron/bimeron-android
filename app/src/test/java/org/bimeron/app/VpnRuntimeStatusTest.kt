package org.bimeron.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnRuntimeStatusTest {
    @Test fun callbackFailureAndOldGenerationCannotBeRevived() {
        try {
            val old = VpnLive.gate.next()
            VpnLive.publish(VpnState("failed", message = "callback", attemptProfileId = "old"), old)
            VpnLive.publishCoreStatus(old, "old", "connected", 99, 88, 1000)
            assertEquals("callback", VpnLive.state.value.message)
            val stopped = VpnLive.gate.next()
            VpnLive.publish(VpnState(), stopped)
            val next = VpnLive.gate.next()
            VpnLive.publish(VpnState("connecting", attemptProfileId = "new"), next)
            VpnLive.publishCoreStatus(old, "old", "connected", 99, 88, 2000)
            assertEquals("new", VpnLive.state.value.attemptProfileId)
            assertEquals(0L, VpnLive.state.value.received)
            VpnLive.publishCoreStatus(next, "new", "connected", 10, 20, 3000)
            VpnLive.publishCoreStatus(next, "new", "reconnecting", 0, 0, 5000)
            assertEquals(2L, VpnLive.durationSeconds(5000))
            assertEquals(0L, VpnLive.state.value.sent)
            VpnLive.publishCoreStatus(next, "new", "connected", 1, 2, 6000)
            assertEquals(3L, VpnLive.durationSeconds(6000))
            assertEquals(1L, VpnLive.state.value.received)
        } finally {
            VpnLive.resetAfterProcessStart()
        }
    }

    @Test fun statusTransitionsAndStalePublication() {
        VpnLive.resetAfterProcessStart()
        try {
            val token = VpnLive.gate.next()
            fun status(state: String, down: Long = 12, up: Long = 34) =
                VpnLive.publishCoreStatus(token, "p", state, down, up, 1000)
            status("connected")
            assertEquals(12L, VpnLive.state.value.received)
            assertEquals(34L, VpnLive.state.value.sent)
            status("reconnecting", -1, -2)
            assertEquals(1000L, VpnLive.state.value.connectedAtElapsed)
            assertEquals(0L, VpnLive.state.value.received)
            assertEquals(0L, VpnLive.state.value.sent)
            status("unknown")
            assertEquals("failed", VpnLive.state.value.state)
            assertEquals(0L, VpnLive.state.value.connectedAtElapsed)
            assertEquals("failed", status("connected"))
            VpnLive.gate.next()
            assertEquals("disconnected", status("connected"))
            assertEquals("failed", VpnLive.state.value.state)
        } finally {
            VpnLive.resetAfterProcessStart()
        }
    }
}
