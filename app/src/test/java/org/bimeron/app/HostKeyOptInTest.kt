package org.bimeron.app

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.bimeron.app.AuthTestSupport.PROFILE
import org.bimeron.app.AuthTestSupport.attempt
import org.bimeron.app.HostKeyFixtures.REQUEST_A
import org.bimeron.app.HostKeyFixtures.REQUEST_B
import org.bimeron.app.HostKeyFixtures.SESSION
import org.bimeron.app.HostKeyFixtures.offer
import org.bimeron.app.HostKeyFixtures.wire
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The accept-new opt-in before start, its persistence after an accepted Trust, and the LastError mapping. */
class HostKeyOptInTest {
    @After fun reset() = VpnLive.resetAfterProcessStart()

    private fun input(text: String) = text.toByteArray().let {
        SessionInput(UUID.fromString(PROFILE), it, ProfileRevision(1, 1), Sha256.of(it), null, null, null)
    }
    private val ssh = input(Conf.fromKey("SSH", "ssh://user@ssh.example.com:2222"))
    private val sshType = input("[Profile]\nVersion = 1\nName = S\n\n[AccessKey]\nType = SSH\nServer = ssh.example.com\nUsername = u\n")
    private val shadowsocks = input(Conf.fromKey("SS", "ss://YWVzLTEyOC1nY206c2VjcmV0@example.com:8388"))

    @Test fun everySshProfileOptsInEvenWithoutStoredKeys() = runBlocking {
        val token = attempt()
        val reads = mutableListOf<UUID>()
        val stored = HostKeyFixtures.line().toByteArray()
        assertArrayEquals(stored, trustedHostKeysFor(ssh, token, read = { reads += it; stored }))
        // Empty store: still an opt-in (empty, not null), so an unknown host is offered.
        val empty = trustedHostKeysFor(sshType, token, read = { ByteArray(0) })
        assertArrayEquals(ByteArray(0), empty)
        assertEquals(listOf(UUID.fromString(PROFILE)), reads)
        // Positively non-SSH profiles do not opt in and are not read.
        assertNull(trustedHostKeysFor(shadowsocks, token, read = { fail(); ByteArray(0) }))
    }

    private fun assertFailsWith(code: VpnErrorCode, action: () -> Unit) {
        try { action(); fail("expected $code") } catch (e: BimeronVpnService.SessionFailure) { assertEquals(code, e.code) }
    }

    @Test fun unreadableStoreFailsClosedWithoutOptingIn() = runBlocking {
        val token = attempt()
        // Never an empty opt-in: a changed key for a trusted host would then be offered as a new server.
        for (failure in listOf<Exception>(IOException("synthetic"), IllegalArgumentException("bad line"), SecurityException())) {
            try {
                trustedHostKeysFor(ssh, token, read = { throw failure })
                fail()
            } catch (e: BimeronVpnService.SessionFailure) {
                assertEquals(VpnErrorCode.TRUSTED_KEYS_UNREADABLE, e.code)
            }
        }
    }

    @Test fun storeWithAnInvalidLineIsUnreadableEndToEnd() = runBlocking<Unit> {
        val base = java.io.File(kotlin.io.path.createTempDirectory("tofu").toFile().canonicalFile, "root").apply { mkdirs() }
        val store = ProfileStore(null, java.io.File(base, "profiles").apply { mkdirs() }, SshProfileResources(java.io.File(base, "ssh-resources"))) {}
        val saved = store.save(Conf.fromKey("SSH", "ssh://user@ssh.example.com:2222"))
        val id = UUID.fromString(saved.id)
        store.rememberTrustedHostKey(id, HostKeyFixtures.line())
        java.io.File(base, "trusted-hosts/$id.known_hosts").appendText("garbage\n")
        val input = store.readForSession(saved.id) { true }
        val token = attempt()
        try {
            trustedHostKeysFor(input, token, read = store::trustedHostKeys)
            fail()
        } catch (e: BimeronVpnService.SessionFailure) {
            assertEquals(VpnErrorCode.TRUSTED_KEYS_UNREADABLE, e.code)
        }
        // Forget is the recovery: afterwards the profile opts in with no keys again.
        store.forgetTrustedHostKeys(id)
        assertArrayEquals(ByteArray(0), trustedHostKeysFor(input, token, read = store::trustedHostKeys))
        base.parentFile.deleteRecursively()
    }

    @Test fun staleTokenNeverReads() = runBlocking {
        val token = attempt()
        VpnLive.nextAttempt()
        try {
            trustedHostKeysFor(ssh, token, read = { fail(); ByteArray(0) })
            fail()
        } catch (_: CancellationException) {}
        // A token superseded during the read is rejected after it.
        val current = attempt()
        try {
            trustedHostKeysFor(ssh, current, read = { VpnLive.nextAttempt(); ByteArray(0) })
            fail()
        } catch (_: CancellationException) {}
    }

    @Test fun optInAlwaysPrecedesStart() {
        val calls = mutableListOf<String>()
        startCoreSession(byteArrayOf(1), optIn = { calls += "optIn:${it.size}" }, start = { calls += "start" })
        assertEquals(listOf("optIn:1", "start"), calls)
        calls.clear()
        startCoreSession(ByteArray(0), optIn = { calls += "optIn:${it.size}" }, start = { calls += "start" })
        assertEquals(listOf("optIn:0", "start"), calls)
        calls.clear()
        startCoreSession(null, optIn = { calls += "optIn" }, start = { calls += "start" })
        assertEquals(listOf("start"), calls)
    }

    @Test fun coreRefusingTheStoredLinesNeverStarts() {
        val calls = mutableListOf<String>()
        assertFailsWith(VpnErrorCode.TRUSTED_KEYS_UNREADABLE) {
            startCoreSession(byteArrayOf(1), optIn = { calls += "optIn"; throw IllegalStateException() }, start = { calls += "start" })
        }
        assertEquals(listOf("optIn"), calls)
    }

    private class Recorder { val lines = mutableListOf<String>() }
    private fun hostKeyAttempt(token: Long, port: FakeCorePort, recorder: Recorder, open: Boolean = true) =
        AuthAttempt(token, port, { open && VpnLive.gate.accepts(token) }, {}, onHostKeyAccepted = { recorder.lines += it })

    @Test fun acceptedTrustPersistsTheMatchedOffersLine() = runBlocking {
        val token = attempt()
        val offer = offer()
        val port = FakeCorePort(wire(offer))
        val recorder = Recorder()
        val auth = hostKeyAttempt(token, port, recorder)
        VpnAuthEndpoint.register(token, auth)
        auth.poll()
        assertEquals(wire(offer), VpnLive.auth.value?.wire)
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, SshAuthenticationController.HOST_KEY_ACCEPT))
        assertEquals(listOf(offer.knownHostsLine), recorder.lines)
        assertEquals(listOf("reply:$REQUEST_A"), port.calls)
        assertEquals(AuthHistory.REPLIED, auth.history)
        // The same ids are never persisted twice, even before the next poll withdraws the offer.
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, SshAuthenticationController.HOST_KEY_ACCEPT))
        assertEquals(1, recorder.lines.size)
        auth.poll()
        assertNull(VpnLive.auth.value)
        assertEquals(AuthResult.STALE, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, SshAuthenticationController.HOST_KEY_ACCEPT))
        assertEquals(1, recorder.lines.size)
    }

    /** Lock order guard → draft → resources → gate: the persist hook (which takes the store guard) holds no lock. */
    @Test fun persistHookRunsOnIoWithNoGateOrAttemptLockHeld() = runBlocking {
        val token = attempt()
        val observed = mutableListOf<String>()
        lateinit var auth: AuthAttempt
        auth = AuthAttempt(token, FakeCorePort(wire(offer())), { true }, {}, onHostKeyAccepted = {
            val attemptLock = AuthAttempt::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(auth)
            observed += "gate=${Thread.holdsLock(VpnLive.gate)}"
            observed += "attempt=${Thread.holdsLock(attemptLock)}"
            observed += "io=${Thread.currentThread().name.startsWith("DefaultDispatcher-worker")}"
        })
        VpnAuthEndpoint.register(token, auth)
        auth.poll()
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, SshAuthenticationController.HOST_KEY_ACCEPT))
        assertEquals(listOf("gate=false", "attempt=false", "io=true"), observed)
    }

    @Test fun nothingIsPersistedWithoutCoreAcceptingTheTrust() = runBlocking {
        val token = attempt()
        val recorder = Recorder()
        val port = FakeCorePort(wire(offer()))
        val auth = hostKeyAttempt(token, port, recorder)
        VpnAuthEndpoint.register(token, auth)
        auth.poll()
        port.failWith = IllegalStateException("core text")
        assertEquals(AuthResult.REJECTED, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, SshAuthenticationController.HOST_KEY_ACCEPT))
        // Any answer other than the fixed acceptance never persists, even if a port accepted it.
        port.failWith = null
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, "[\"Accept\"]"))
        assertTrue(recorder.lines.isEmpty())
        // A cancel (decline) never persists.
        val next = attempt()
        val declined = hostKeyAttempt(next, FakeCorePort(wire(offer(), REQUEST_B)), recorder)
        VpnAuthEndpoint.register(next, declined)
        declined.poll()
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.cancel(next, SESSION, REQUEST_B))
        assertEquals(AuthHistory.CANCELLED_BY_USER, declined.history)
        assertTrue(recorder.lines.isEmpty())
    }

    @Test fun passwordRepliesNeverPersistAndCallbackFailureDoesNotFailTheReply() = runBlocking {
        val token = attempt()
        val recorder = Recorder()
        val port = FakeCorePort(AuthTestSupport.wire(REQUEST_A))
        val auth = hostKeyAttempt(token, port, recorder)
        VpnAuthEndpoint.register(token, auth)
        auth.poll()
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.reply(token, SESSION, REQUEST_A, SshAuthenticationController.HOST_KEY_ACCEPT))
        assertTrue(recorder.lines.isEmpty())
        val next = attempt()
        val failing = AuthAttempt(next, FakeCorePort(wire(offer())), { true }, {}, onHostKeyAccepted = { throw IOException("disk") })
        VpnAuthEndpoint.register(next, failing)
        failing.poll()
        assertEquals(AuthResult.ACCEPTED, VpnAuthEndpoint.reply(next, SESSION, REQUEST_A, SshAuthenticationController.HOST_KEY_ACCEPT))
    }

    @Test fun explicitHostKeyFaultsWinOverAuthHistory() {
        val expected = mapOf(
            "ssh_host_key_mismatch" to VpnErrorCode.HOST_KEY_MISMATCH,
            "ssh_host_key_rejected" to VpnErrorCode.AUTH_CANCELLED,
            "ssh_host_key_unanswered" to VpnErrorCode.AUTH_UNANSWERED,
            "ssh_host_key_unknown" to VpnErrorCode.SSH_CONNECTION_FAILED,
            "ssh_host_key_ca_required" to VpnErrorCode.HOST_KEY_CA_REQUIRED,
            "ssh_known_hosts_missing" to VpnErrorCode.SSH_KNOWN_HOSTS_MISSING,
        )
        // The new fixed codes are never inferred from Core text.
        assertTrue(VpnErrorCode.TRUSTED_KEYS_UNREADABLE !in expected.values && VpnErrorCode.SSH_FILE_OUTSIDE_APP !in expected.values)
        for ((lastError, code) in expected) for (history in AuthHistory.entries) for (state in listOf("failed", "disconnected", "reconnecting")) {
            assertEquals("$lastError/$history/$state", code, coreFailureCode(state, lastError, history))
        }
        // The generic SSH fault keeps the history heuristic.
        assertEquals(VpnErrorCode.AUTH_FAILED, coreFailureCode("failed", "ssh_connection_failed", AuthHistory.REPLIED))
        assertEquals(VpnErrorCode.CORE_CONNECT_FAILED, coreFailureCode("failed", "ssh_host_key_other", AuthHistory.NONE))
        assertEquals(expected, SSH_HOST_KEY_FAULTS)
    }

    @Test fun mismatchIsNotReplacedByALateUserCancel() {
        val token = attempt()
        VpnLive.publishCoreStatus(token, PROFILE, "failed", 0, 0, 0, "m", VpnErrorCode.HOST_KEY_MISMATCH)
        VpnLive.publishUserCancel("c", PROFILE, token)
        assertEquals(VpnErrorCode.HOST_KEY_MISMATCH, VpnLive.state.value.code)
    }
}
