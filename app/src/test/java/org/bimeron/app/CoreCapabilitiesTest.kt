package org.bimeron.app

import org.junit.Assert.*
import org.junit.Test

class CoreCapabilitiesTest {
    @Test fun dualStackRequiresEveryExplicitFlagAndAndroid() {
        val flags = mapOf("vpn" to true, "ipv4" to true, "ipv6" to true)
        assertTrue(CoreCapabilities(platform = "android", support = flags).canStartVpn)
        for (key in flags.keys) for (value in listOf(false, null)) {
            assertFalse(CoreCapabilities(platform = "android", support = flags + (key to value)).canStartVpn)
        }
        for (platform in listOf(null, "linux", "Android")) {
            assertFalse(CoreCapabilities(platform = platform, support = flags).canStartVpn)
        }
    }
    @Test fun missingCapabilityMethodIsUnknown() {
        assertNull(CoreCapabilities.capabilityJson { throw NoSuchMethodError() })
        assertNull(CoreCapabilities.capabilityJson { throw UnsatisfiedLinkError() })
    }
    @Test(expected = OutOfMemoryError::class)
    fun fatalErrorsPropagate() { CoreCapabilities.capabilityJson { throw OutOfMemoryError() } }
}
