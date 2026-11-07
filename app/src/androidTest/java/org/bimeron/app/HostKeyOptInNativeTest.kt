package org.bimeron.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import org.bimeron.core.androidcore.Androidcore
import org.bimeron.core.androidcore.Session
import org.bimeron.core.androidcore.SocketProtector
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Packaged JNI check of `Session.acceptNewHostKeys` on unstarted sessions: no network, no VPN, no files. */
@RunWith(AndroidJUnit4::class)
class HostKeyOptInNativeTest {
    private val protector = object : SocketProtector {
        override fun protect(fd: Long): Boolean = throw AssertionError("An unstarted session protected a socket")
    }
    private fun session(): Session =
        Androidcore.newSession(Conf.fromKey("SSH", "ssh://user@ssh.example.com:2222").toByteArray(), protector)

    /** A line exactly as an offer would carry it, built from a synthetic ed25519 key blob. */
    private fun offerLine(host: String, seed: Int): SshAuthenticationController.HostKeyOffer {
        val type = "ssh-ed25519".toByteArray()
        val blob = ByteBuffer.allocate(8 + type.size + 32).putInt(type.size).put(type).putInt(32)
            .put(ByteArray(32) { (it + seed).toByte() }).array()
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        val lineHost = if (host.endsWith(":22")) host.removeSuffix(":22") else "[${host.substringBeforeLast(':')}]:${host.substringAfterLast(':')}"
        return SshAuthenticationController.HostKeyOffer(host, "ssh-ed25519", fingerprint, "$lineHost ssh-ed25519 ${Base64.getEncoder().encodeToString(blob)}")
    }

    private fun rejects(action: () -> Unit) {
        try { action() } catch (_: Exception) { return }
        fail("Expected rejection")
    }

    @Test fun appValidatedLinesAreAcceptedOnceBeforeStart() {
        val offers = listOf(offerLine("ssh.example.com:2222", 1), offerLine("ssh.example.com:22", 2))
        offers.forEach { assertTrue(it.knownHostsLine, SshAuthenticationController.validHostKey(it)) }
        val bytes = offers.joinToString("") { it.knownHostsLine + "\n" }.toByteArray()
        val session = session()
        try {
            session.acceptNewHostKeys(bytes)
            // At most once per session.
            rejects { session.acceptNewHostKeys(ByteArray(0)) }
        } finally { session.stop() }
        // Empty store: the opt-in still succeeds, so an unknown host would be offered.
        session().let { empty -> try { empty.acceptNewHostKeys(ByteArray(0)) } finally { empty.stop() } }
    }

    @Test fun linesTheAppRejectsCoreRejectsToo() {
        val good = offerLine("ssh.example.com:2222", 1).knownHostsLine
        val (host, type, key) = good.split(' ')
        for (line in listOf("@revoked $good", "*.example.com $type $key", "|1|c2FsdA==|aGFzaA== $type $key", "$host $type")) {
            assertTrue(line, !SshAuthenticationController.validKnownHostsLine(line))
            val session = session()
            try { rejects { session.acceptNewHostKeys("$line\n".toByteArray()) } } finally { session.stop() }
        }
        // Over Core's 256 KiB limit.
        val session = session()
        try { rejects { session.acceptNewHostKeys(ByteArray(TrustedHostKeys.MAX_BYTES + 1) { '#'.code.toByte() }) } }
        finally { session.stop() }
    }

    @Test fun stoppedSessionRefusesOptIn() {
        val session = session()
        session.stop()
        rejects { session.acceptNewHostKeys(ByteArray(0)) }
    }
}
