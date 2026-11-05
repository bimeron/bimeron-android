package org.bimeron.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bimeron.core.androidcore.Androidcore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreCapabilitiesRuntimeTest {
    @Test fun installedAarContract() {
        val version = Androidcore.version()
        val capabilities = Androidcore.capabilities()
        assertTrue(JSONObject(version).getString("version").isNotBlank())
        val json = JSONObject(capabilities)
        assertEquals("android", json.getString("platform"))
        CoreCapabilities.flags.forEach { assertTrue("Boolean field: $it", json.get(it) is Boolean) }
        assertTrue(json.getJSONObject("ikev2").get("supported") is Boolean)
        assertTrue(CoreCapabilities.parse(version, capabilities).canStartVpn)
    }
    @Test fun versionFailureDoesNotHideCapabilities() {
        val core = CoreCapabilities.read({ throw UnsatisfiedLinkError() },
            { "{\"platform\":\"android\",\"vpn\":true,\"ipv4\":true,\"ipv6\":true}" })
        assertNull(core.version)
        assertTrue(core.canStartVpn)
    }
    @Test fun malformedAndMissingFieldsStayUnknown() {
        for (input in listOf(null, "", "broken", "[]", "{}")) {
            val core = CoreCapabilities.parse(input, input)
            assertNull(core.version)
            assertFalse(core.canStartVpn)
            assertNull(core.support["vpn"])
        }
        val core = CoreCapabilities.parse("{\"version\":42}",
            "{\"platform\":\"android\",\"vpn\":\"true\",\"ipv4\":true,\"ipv6\":false}")
        assertNull(core.version)
        assertNull(core.support["vpn"])
        assertEquals(true, core.support["ipv4"])
        assertEquals(false, core.support["ipv6"])
        assertFalse(core.canStartVpn)
    }
}
