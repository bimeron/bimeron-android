package org.bimeron.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.bimeron.app.AuthTestSupport.PROFILE
import org.bimeron.app.AuthTestSupport.attempt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** LastError catalog codes (core #106, #104) map to fixed app codes and user text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreErrorMappingTest {
    @After fun reset() = VpnLive.resetAfterProcessStart()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun catalogCodesTheAppReliesOn() {
        val expected = mapOf(
            "ssh_known_hosts_missing" to VpnErrorCode.SSH_KNOWN_HOSTS_MISSING,
            "ssh_host_key_mismatch" to VpnErrorCode.HOST_KEY_MISMATCH,
            "ssh_host_key_ca_required" to VpnErrorCode.HOST_KEY_CA_REQUIRED,
            "ssh_host_key_rejected" to VpnErrorCode.AUTH_CANCELLED,
            "ssh_host_key_unanswered" to VpnErrorCode.AUTH_UNANSWERED,
            "ssh_host_key_unknown" to VpnErrorCode.SSH_CONNECTION_FAILED,
            "ssh_connection_failed" to VpnErrorCode.SSH_CONNECTION_FAILED,
            "network_unavailable" to VpnErrorCode.NETWORK_ERROR,
            "network_changed" to VpnErrorCode.NETWORK_ERROR,
            "hysteria2_network_failed" to VpnErrorCode.NETWORK_ERROR,
            "hysteria2_network_timeout" to VpnErrorCode.NETWORK_ERROR,
            // #106: every unclassified failure is connection_failed, which stays the generic connect failure.
            "connection_failed" to VpnErrorCode.CORE_CONNECT_FAILED,
        )
        for ((lastError, code) in expected) for (state in listOf("failed", "disconnected", "reconnecting")) {
            assertEquals("$lastError/$state", code, coreFailureCode(state, lastError))
        }
    }

    /** An SSH profile without KnownHostsFile: the clear message, never the generic connect failure. */
    @Test fun knownHostsMissingSurvivesTheGenericFallback() {
        for (history in AuthHistory.entries) {
            val token = attempt()
            val code = coreFailureCode("failed", "ssh_known_hosts_missing", history)
            assertEquals(VpnErrorCode.SSH_KNOWN_HOSTS_MISSING, code)
            // As BimeronVpnService.publishStatus does: the message comes only from the code's resource.
            VpnLive.publishCoreStatus(token, PROFILE, "failed", 0, 0, 0, app.getString(code!!.messageRes), code)
            // The status loop then throws SessionFailure(CORE_CONNECT_FAILED); the recorded code must win.
            VpnLive.publishFailure(VpnErrorCode.CORE_CONNECT_FAILED, app.getString(VpnErrorCode.CORE_CONNECT_FAILED.messageRes), PROFILE, token)
            val state = VpnLive.state.value
            assertEquals(VpnErrorCode.SSH_KNOWN_HOSTS_MISSING, state.code)
            assertNotEquals(VpnErrorCode.CORE_CONNECT_FAILED, state.code)
            assertEquals(app.getString(R.string.vpn_error_ssh_known_hosts_missing), state.message)
        }
        assertEquals("The server's key could not be checked: this profile has no imported known_hosts file and no usable " +
            "trusted server keys. Forget the trusted server keys in the profile editor, or import a known_hosts file, then connect again.",
            app.getString(R.string.vpn_error_ssh_known_hosts_missing))
    }
}
