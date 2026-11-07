package org.bimeron.app

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The profile an always-on start connects: the interrupted session first, then the selection. */
class AlwaysOnTargetTest {
    private val interrupted = UUID.randomUUID().toString()
    private val selected = UUID.randomUUID().toString()

    @Test fun interruptedSessionWinsOverSelection() {
        assertEquals(interrupted, alwaysOnTarget(interrupted, selected))
    }

    @Test fun selectionIsUsedAfterAnExplicitStop() {
        assertEquals(selected, alwaysOnTarget(null, selected))
    }

    @Test fun noRecoveryIdAndNoSelectionHasNoTarget() {
        assertNull(alwaysOnTarget(null, null))
    }

    @Test fun nonProfileIdsAreIgnored() {
        assertEquals(selected, alwaysOnTarget("", selected))
        assertEquals(selected, alwaysOnTarget("not-a-uuid", selected))
        assertEquals(interrupted, alwaysOnTarget(interrupted, "not-a-uuid"))
        assertNull(alwaysOnTarget("not-a-uuid", " "))
    }

    private fun input(text: String): SessionInput {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return SessionInput(UUID.randomUUID(), bytes, ProfileRevision(1, 1), Sha256.of(bytes), null, null, null)
    }

    private val ssText = Conf.fromKey("Home server", "ss://YWVzLTEyOC1nY206c2VjcmV0@example.com:8388")

    @Test fun aVpnModeProfileWithoutKillSwitchRecovers() {
        assertTrue(runsOnAndroid(input(ssText)))
        assertFalse(mustSkipRecovery(input(ssText)))
    }

    /** The selected-profile fallback must not start what the UI refuses to start. */
    @Test fun proxyModeOrKillSwitchProfilesAreSkipped() {
        val proxy = ssText.replace("Mode = vpn", "Mode = proxy")
        val killSwitch = ssText.replace("KillSwitch = false", "KillSwitch = true")
        assertTrue(proxy != ssText && killSwitch != ssText)
        for (text in listOf(proxy, killSwitch)) {
            assertFalse(runsOnAndroid(input(text)))
            assertTrue(mustSkipRecovery(input(text)))
        }
    }

    @Test fun absentOrUnreadableInputIsSkipped() {
        assertTrue(mustSkipRecovery(null))
        assertTrue(mustSkipRecovery(input("not a profile")))
    }
}
