package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class SessionConfirmationTest {
    @Test fun trafficDoesNotInvalidateConfirmation() {
        val connected = VpnState(state = "connected", activeProfileId = "a", connectedAtElapsed = 10)
        assertTrue(sameModelSession(connected, connected.copy(received = 100, sent = 42)))
    }

    @Test fun restartedSessionInvalidatesConfirmation() {
        val connected = VpnState(state = "connected", activeProfileId = "a", connectedAtElapsed = 10)
        assertFalse(sameModelSession(connected, connected.copy(connectedAtElapsed = 20)))
        assertFalse(sameModelSession(connected, connected.copy(state = "stopping")))
        assertFalse(sameModelSession(connected, connected.copy(attemptProfileId = "b")))
    }
}
