package org.bimeron.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnRuntimeStateTest {
    @Test fun unknownCoreStatesFailClosed() {
        assertEquals("failed", normalizeCoreState(""))
        assertEquals("failed", normalizeCoreState("future-state"))
        assertEquals("reconnecting", normalizeCoreState("reconnecting"))
    }

    @Test fun terminalDurationDoesNotKeepGrowing() {
        try {
            val token = VpnLive.gate.next()
            VpnLive.publish(VpnState("connected", connectedAtElapsed = 1000), token)
            assertEquals(9L, VpnLive.durationSeconds(10000))
            VpnLive.publish(VpnLive.state.value.copy(state = "failed"), token)
            assertEquals(0L, VpnLive.durationSeconds(20000))
            VpnLive.publish(VpnLive.state.value.copy(state = "stopping"), token)
            assertEquals(0L, VpnLive.durationSeconds(30000))
        } finally {
            VpnLive.resetAfterProcessStart()
        }
    }
}
