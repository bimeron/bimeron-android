package org.bimeron.app

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bimeron.core.androidcore.Androidcore
import org.bimeron.core.androidcore.SocketProtector
import org.bimeron.core.androidcore.SSConfSocketProtector
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Packaged JNI smoke test: no VPN permission, sockets, or persistent profiles. */
@RunWith(AndroidJUnit4::class)
class CoreCompatibilityTest {
    private fun expectException(action: () -> Unit) {
        try {
            action()
        } catch (expected: Exception) {
            return
        }
        fail("Expected rejection")
    }

    @Test fun cancelledSSConfDoesNotOpenSockets() {
        val request = Androidcore.newSSConfRequest(
            "ssconf://example.invalid/config", "synthetic-network", "192.0.2.53",
            object : SSConfSocketProtector {
                override fun protect(fd: Long): Boolean = throw AssertionError("Cancelled request protected socket")
                override fun bind(fd: Long): Boolean = throw AssertionError("Cancelled request bound socket")
            }
        )
        request.cancel()
        request.cancel()
        expectException { request.resolve() }
    }

    @Test fun packagedNativeContractAndSessionCleanup() {
        val version = Androidcore.version()
        val capabilities = JSONObject(Androidcore.capabilities())
        Log.i("CoreCompatibility", "API=${Build.VERSION.SDK_INT} ABI=${Build.SUPPORTED_ABIS.joinToString()} version=$version capabilities=$capabilities")
        assertTrue(version.isNotBlank())
        assertEquals("android", capabilities.getString("platform"))
        for (feature in listOf("vpn", "ipv4", "ipv6")) assertTrue(feature, capabilities.getBoolean(feature))
        val config = """
            [Profile]
            Version = 1
            Name = API compatibility fixture
            [AccessKey]
            Type = socks5
            Server = 192.0.2.1
            Port = 1080
            [Interface]
            Mode = vpn
            KillSwitch = false
            DNS = 192.0.2.53
        """.trimIndent().toByteArray()
        repeat(2) {
            val session = Androidcore.newSession(config, object : SocketProtector {
                override fun protect(fd: Long): Boolean = throw AssertionError("Unstarted session opened a socket")
            })
            try {
                assertEquals("null", session.pendingAuth())
                expectException { session.replyAuth("synthetic-request", "synthetic-challenge", "synthetic-answer") }
                expectException { session.cancelAuth("synthetic-request", "synthetic-challenge") }
                assertEquals("null", session.pendingAuth())
                assertTrue(session.mtu() in 1280L..65535L)
                assertEquals("disconnected", JSONObject(session.status()).getJSONObject("status").getString("State"))
            } finally {
                session.stop()
            }
        }
    }
}
