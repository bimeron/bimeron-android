package org.bimeron.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bimeron.core.androidcore.Androidcore
import org.bimeron.core.androidcore.Session
import org.bimeron.core.androidcore.SocketProtector
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The packaged Core error catalog (#106) and real LastError codes, against the app's mapping. No sockets. */
@RunWith(AndroidJUnit4::class)
class CoreErrorCatalogTest {
    private fun codes(): Set<String> = JSONObject(Androidcore.errorCatalog()).getJSONObject("codes").keys().asSequence().toSet()

    @Test fun everyCatalogCodeMapsAndEverySshCodeIsExplicit() {
        val codes = codes()
        for (needed in SSH_HOST_KEY_FAULTS.keys + listOf("ssh_connection_failed", "connection_failed", "network_unavailable",
            "network_changed", "hysteria2_network_failed", "hysteria2_network_timeout")) {
            assertTrue(needed, needed in codes)
        }
        for (code in codes) assertNotNull(code, coreFailureCode("failed", code))
        // A new SSH code in Core must be mapped on purpose, not fall through to the generic connect failure.
        for (code in codes.filter { it.startsWith("ssh_") }) {
            assertTrue(code, code == "ssh_connection_failed" || code in SSH_HOST_KEY_FAULTS)
            assertNotEquals(code, VpnErrorCode.CORE_CONNECT_FAILED, coreFailureCode("failed", code))
        }
        assertEquals(VpnErrorCode.CORE_CONNECT_FAILED, coreFailureCode("failed", "connection_failed"))
    }

    private fun sshSession(): Session = Androidcore.newSession(
        "[Profile]\nVersion = 1\nName = S\n\n[AccessKey]\nType = ssh\nServer = 192.0.2.1\nPort = 22\nUsername = u\n\n[Interface]\nMode = vpn\nKillSwitch = false\nDNS = 192.0.2.53\n".toByteArray(),
        object : SocketProtector { override fun protect(fd: Long): Boolean = false })

    /** Polls until Core reports a LastError or [timeoutMillis] passes; returns it (empty when none). */
    private fun lastError(session: Session, timeoutMillis: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val error = JSONObject(session.status()).getJSONObject("status").optString("LastError")
            if (error.isNotBlank()) return error
            Thread.sleep(100)
        }
        return ""
    }

    @Test fun sshWithoutKnownHostsOrOptInFailsAsKnownHostsMissingBeforeDialing() {
        val session = sshSession()
        try {
            session.setNetwork("synthetic-network", "192.0.2.53")
            session.start()
            val error = lastError(session, 20_000)
            assertEquals("ssh_known_hosts_missing", error)
            assertEquals(VpnErrorCode.SSH_KNOWN_HOSTS_MISSING, coreFailureCode("failed", error))
        } finally {
            // Stop reports the session's terminal fault (the service ignores it, see finishPacketWorkers).
            val stopped = runCatching { session.stop() }.exceptionOrNull()
            assertTrue(stopped?.message.toString(), stopped == null || stopped.message == "ssh_known_hosts_missing")
        }
    }

    @Test fun optedInSshWithoutKnownHostsIsNotKnownHostsMissing() {
        val session = sshSession()
        try {
            session.acceptNewHostKeys(ByteArray(0))
            session.setNetwork("synthetic-network", "192.0.2.53")
            session.start()
            // Every socket is refused by the protector, so it cannot reach an offer; it must not be the missing case.
            val error = lastError(session, 40_000)
            assertNotEquals("ssh_known_hosts_missing", error)
        } finally {
            val stopped = runCatching { session.stop() }.exceptionOrNull()
            assertNotEquals("ssh_known_hosts_missing", stopped?.message)
        }
    }
}
