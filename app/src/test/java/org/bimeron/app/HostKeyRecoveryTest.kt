package org.bimeron.app

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Accept-new host keys and recovery starts (sticky restart and always-on, #23). A recovery never shows UI, so
 * every profile that would opt in to host-key offers must be skipped before `runSession`; a recovery that does
 * proceed is positively non-SSH, never opts in and never reads the trusted-keys store.
 */
class HostKeyRecoveryTest {
    @After fun reset() = VpnLive.resetAfterProcessStart()

    private fun input(text: String) = text.toByteArray().let {
        SessionInput(UUID.randomUUID(), it, ProfileRevision(1, 1), Sha256.of(it), null, null, null)
    }
    private val sshProfiles = listOf(
        Conf.fromKey("S", "ssh://u@ssh.example.com:22"),
        "[Profile]\nVersion = 1\nName = S\n\n[AccessKey]\nType = ssh\nServer = ssh.example.com\nUsername = u\n",
        "[Profile]\nVersion = 1\nName = S\n\n[AccessKey]\nSSH = ssh://u@ssh.example.com:2222\n",
        "[Profile]\nVersion = 1\nName = S\n\n[AccessKey]\nKey = ssh://u@ssh.example.com:22?known_hosts=/sdcard/x\n",
        "not a profile",
    ).map(::input)
    private val shadowsocks = input(Conf.fromKey("SS", "ss://YWVzLTEyOC1nY206c2VjcmV0@example.com:8388"))

    private class Port(val input: SessionInput) : RecoveryStartPort {
        val effects = mutableListOf<String>()
        override suspend fun read(id: String, token: Long): SessionInput { effects += "read"; return input }
        override suspend fun clearRecovery(): Boolean { effects += "clear"; return true }
        override val destroyed = false
        override fun postNotification() { effects += "post" }
        override fun cancelNotification() { effects += "cancel" }
        override fun recordSkip(message: Int) { effects += "journal" }
        override fun publishSkipped(token: Long) { effects += "publish"; VpnLive.publish(recoverySkippedState { "fixed" }, token) }
    }

    @Test fun everyProfileThatWouldOptInIsSkippedOnRecovery() = runBlocking {
        for (profile in sshProfiles) {
            // The opt-in classifier and the recovery classifier agree: would opt in => must skip.
            assertTrue(mayNeedInteractiveAuth(profile))
            assertTrue(mustSkipRecovery(profile))
            val token = VpnLive.nextAttempt()
            val port = Port(profile)
            assertEquals(StartPlan.Skipped, planStart(true, profile.id.toString(), token, port))
            // Skipped with a notification and a journal entry only: no session, no auth offer, no dialog.
            assertEquals(listOf("read", "clear", "post", "journal", "publish"), port.effects)
            assertNull(VpnLive.auth.value)
            assertEquals(VpnErrorCode.RECOVERY_NEEDS_USER, VpnLive.state.value.code)
        }
    }

    @Test fun aRecoveryThatProceedsNeverOptsInOrReadsTrustedKeys() = runBlocking {
        val token = VpnLive.nextAttempt()
        val plan = planStart(true, shadowsocks.id.toString(), token, Port(shadowsocks))
        val proceeding = (plan as StartPlan.Proceed).input!!
        // runSession's own guards still apply to the classified input it reuses.
        requireSshFilesInsideApp(proceeding, null)
        assertNull(trustedHostKeysFor(proceeding, token, read = { fail("read trusted keys"); ByteArray(0) }))
    }

    @Test fun ifAnSshRecoveryEverReachedRunSessionItWouldStillFailClosed() = runBlocking {
        // Defence in depth: the fail-closed read is applied to every input, not only user starts.
        val token = VpnLive.nextAttempt()
        try {
            trustedHostKeysFor(sshProfiles.first(), token, read = { throw java.io.IOException("unreadable") })
            fail()
        } catch (e: BimeronVpnService.SessionFailure) {
            assertEquals(VpnErrorCode.TRUSTED_KEYS_UNREADABLE, e.code)
        }
        try {
            requireSshFilesInsideApp(sshProfiles[3], java.io.File("/data/data/org.bimeron.app/files"))
            fail()
        } catch (e: BimeronVpnService.SessionFailure) {
            assertEquals(VpnErrorCode.SSH_FILE_OUTSIDE_APP, e.code)
        }
    }
}
