package org.bimeron.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SSConfConsentModeTest {
    @Test fun capturesEveryExplicitModeExactly() {
        for (mode in listOf("allow", "ignore", "deny", "default", "foreground")) {
            assertEquals(SSConfConsentMode(mode, false), SSConfConsentMode.parse("ACTIVATE_VPN: $mode\n"))
        }
    }

    @Test fun absentEntryUsesReportedDefaultAndIsDistinctFromExplicitDefault() {
        for (mode in listOf("allow", "ignore", "deny", "default", "foreground")) {
            val absent = SSConfConsentMode.parse("No operations.\nDefault mode: $mode\n")
            assertEquals(SSConfConsentMode(mode, true), absent)
            assertNotEquals(SSConfConsentMode.parse("ACTIVATE_VPN: $mode"), absent)
        }
    }

    @Test fun acceptsModeWithPlatformHistory() {
        for (history in listOf("; time=+1m2s345ms ago; rejectTime=+3s ago; duration=+1s",
            "; time=0 ago (running)", "; rejectTime=+1d2h3m4s5ms ago")) {
            assertEquals(SSConfConsentMode("ignore", false), SSConfConsentMode.parse("ACTIVATE_VPN: ignore$history"))
        }
    }

    @Test fun refusesMissingDefaultUidOverridesAndAmbiguousOrUnknownOutput() {
        for (output in listOf("", "No operations.", "No operations.\nDefault mode: unknown",
            "Uid mode: ACTIVATE_VPN: allow", "Uid mode: ACTIVATE_VPN: allow\nACTIVATE_VPN: deny",
            "ACTIVATE_VPN: allow\nACTIVATE_VPN: allow", "ACTIVATE_VPN: allowed",
            "ACTIVATE_VPN: unknown", "No operations.\nDefault mode: ignore\nACTIVATE_VPN: allow",
            "ACTIVATE_VPN: default\nDefault mode: ignore", "ACTIVATE_VPN: allow; unexpected=deny",
            "Error: package missing", "CAMERA: allow")) {
            assertThrows(output, IllegalStateException::class.java) { SSConfConsentMode.parse(output) }
        }
    }
}
