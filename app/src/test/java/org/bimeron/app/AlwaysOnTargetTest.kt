package org.bimeron.app

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
