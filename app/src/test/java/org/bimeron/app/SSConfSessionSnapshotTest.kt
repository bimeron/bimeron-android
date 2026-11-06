package org.bimeron.app

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVpnService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SSConfSessionSnapshotTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var app: Application
    private val source = "ssconf://secret-host.example/private-path"
    private val good = "valid body".toByteArray()
    private val other = "other valid body".toByteArray()
    private val rejected = "rejected body".toByteArray()

    /** Records every injected call in order. */
    private class Calls {
        val log = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var committed: ByteArray? = null
    }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AppEvents.resources = app.resources
        AppEvents.clear()
    }

    @After fun reset() {
        ShadowVpnService.setPrepareResult(null)
        VpnLive.resetAfterProcessStart()
        AppEvents.clear()
        AppEvents.resources = null
    }

    private fun ticket(stillValid: () -> Boolean = { true }) = SnapshotTicket(UUID.randomUUID(), source, 1, stillValid)
    private fun stored(body: ByteArray) = StoredSnapshot(body, Sha256.of(body), 1L)

    private suspend fun resolve(
        calls: Calls,
        stored: StoredSnapshot?,
        fetched: () -> ByteArray = { good },
        result: SnapshotCommitResult = SnapshotCommitResult.Committed(stored(good)),
        ensureAttempt: suspend () -> Unit = {},
        ticket: SnapshotTicket = ticket(),
    ): String = resolveSessionSnapshot(
        ticket = ticket,
        stored = stored,
        ensureAttempt = ensureAttempt,
        validate = { body ->
            calls.log += "validate"
            if (body.contentEquals(rejected)) throw IllegalStateException("core rejected $source") else String(body)
        },
        fetch = { calls.log += "fetch"; fetched() },
        discard = { _, digest -> calls.log += "discard"; assertEquals(Sha256.of(rejected), digest) },
        commit = { _, body -> calls.log += "commit"; calls.committed = body; result },
        warn = { calls.warnings += it; AppEvents.add(it, LogLevel.WARNING) },
    )

    private suspend fun expectFailure(code: VpnErrorCode, block: suspend () -> Unit): SSConfFailure {
        try { block(); fail("Expected $code") } catch (e: SSConfFailure) {
            assertEquals(code, e.code)
            assertNull(e.cause)
            assertEquals(code.name, e.message)
            return e
        }
        throw AssertionError()
    }

    @Test fun validStoredSnapshotIsReusedWithoutFetch() = runBlocking {
        val calls = Calls()
        assertEquals("valid body", resolve(calls, stored(good), fetched = { fail("fetched"); good }))
        assertEquals(listOf("validate"), calls.log)
    }

    @Test fun coreRejectedStoredSnapshotIsDiscardedThenFetchedOnce() = runBlocking {
        val calls = Calls()
        assertEquals("other valid body", resolve(calls, stored(rejected), fetched = { other }))
        assertEquals(listOf("validate", "discard", "fetch", "validate", "commit"), calls.log)
        assertTrue(other.contentEquals(calls.committed))
    }

    @Test fun absentSnapshotFetchesOnceAndCommits() = runBlocking {
        val calls = Calls()
        assertEquals("valid body", resolve(calls, null))
        assertEquals(listOf("fetch", "validate", "commit"), calls.log)
        assertTrue(good.contentEquals(calls.committed))
        assertTrue(calls.warnings.isEmpty())
    }

    @Test fun rejectedFetchedBodyIsSnapshotInvalidWithoutCommit() = runBlocking {
        val calls = Calls()
        expectFailure(VpnErrorCode.SSCONF_SNAPSHOT_INVALID) { resolve(calls, null, fetched = { rejected }) }
        assertEquals(listOf("fetch", "validate"), calls.log)
        assertNull(calls.committed)
    }

    @Test fun emptyOrOversizedFetchedBodyIsSnapshotInvalidWithoutValidationOrCommit() = runBlocking {
        for (body in listOf(ByteArray(0), ByteArray(SSConfSnapshots.MAX_BODY + 1) { 'a'.code.toByte() })) {
            val calls = Calls()
            expectFailure(VpnErrorCode.SSCONF_SNAPSHOT_INVALID) { resolve(calls, null, fetched = { body }) }
            assertEquals(listOf("fetch"), calls.log)
        }
    }

    @Test fun notPersistedContinuesWithInMemorySnapshotAndWarns() = runBlocking {
        val calls = Calls()
        assertEquals("valid body", resolve(calls, null, result = SnapshotCommitResult.NotPersisted))
        assertEquals(listOf(SNAPSHOT_NOT_PERSISTED), calls.warnings)
        val entry = AppEvents.entries.value.single()
        assertEquals("SNAPSHOT_NOT_PERSISTED", entry.message)
        assertEquals(LogLevel.WARNING, entry.level)
    }

    @Test fun everyConflictIsProfileChanged() = runBlocking {
        for (reason in listOf(ConflictReason.SOURCE_CHANGED, ConflictReason.DELETED,
            ConflictReason.SUPERSEDED, ConflictReason.STALE_TICKET)) {
            val calls = Calls()
            expectFailure(VpnErrorCode.PROFILE_CHANGED) {
                resolve(calls, null, result = SnapshotCommitResult.Conflict(reason))
            }
            assertTrue(calls.warnings.isEmpty())
        }
    }

    @Test fun fetchFailuresKeepFixedCodesWithoutSourceInStateOrJournal() = runBlocking {
        for (code in listOf(VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE, VpnErrorCode.SSCONF_FETCH_FAILED)) {
            AppEvents.clear()
            val calls = Calls()
            val token = VpnLive.gate.next()
            val failure = expectFailure(code) { resolve(calls, null, fetched = { throw SSConfFailure(code) }) }
            assertNull(calls.committed)
            VpnLive.publishFailure(failure.code, app.getString(failure.code.messageRes), "profile-${code.name}", token)
            val state = VpnLive.state.value
            assertEquals("failed", state.state)
            assertEquals(code, state.code)
            assertFalse(state.toString().contains("secret-host"))
            assertFalse(AppEvents.entries.value.any { it.message.contains("secret-host") || it.message.contains("ssconf://") })
        }
    }

    @Test fun unpreparedFetchMapsToNetworkUnavailableWithoutSource() = runBlocking {
        ShadowVpnService.setPrepareResult(Intent("consent"))
        val failure = expectFailure(VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE) { fetchSSConf(app, source) }
        assertFalse(failure.toString().contains("secret-host"))
        assertTrue(AppEvents.entries.value.isEmpty())
    }

    @Test fun staleTokenDuringFetchIsCancellationWithoutCommit() = runBlocking {
        val calls = Calls()
        val token = VpnLive.gate.next()
        try {
            resolve(calls, null, fetched = { VpnLive.gate.next(); good }, ensureAttempt = { ensureSessionAttempt(token) })
            fail("stale attempt continued")
        } catch (_: CancellationException) {}
        assertEquals(listOf("fetch"), calls.log)
        assertNull(calls.committed)
    }

    @Test fun staleTokenBeforeStartTouchesNothing() = runBlocking {
        val calls = Calls()
        val token = VpnLive.gate.next()
        VpnLive.gate.next()
        try {
            resolve(calls, stored(good), ensureAttempt = { ensureSessionAttempt(token) })
            fail("stale attempt continued")
        } catch (_: CancellationException) {}
        assertTrue(calls.log.isEmpty())
    }

    @Test fun realStoreCommitsOnceThenReusesWithoutRefetch() = runBlocking {
        val directory = temp.newFolder()
        val store = ProfileStore(null, directory, SshProfileResources(temp.newFolder())) {}
        val id = UUID.randomUUID()
        store.compareAndSave(UUID.randomUUID(), id, null, Conf.fromKey("A", source), null)
        suspend fun attempt(calls: Calls): String {
            val token = VpnLive.gate.next()
            val input = readSessionInput(store, id.toString(), token)
            return resolveSessionSnapshot(input.ticket!!, input.snapshot, { ensureSessionAttempt(token) },
                { String(it) }, { calls.log += "fetch"; good }, store::discardSnapshot, store::commitSnapshot,
                { calls.warnings += it })
        }
        val first = Calls()
        assertEquals("valid body", attempt(first))
        assertEquals(listOf("fetch"), first.log)
        val second = Calls()
        assertEquals("valid body", attempt(second))
        assertTrue(second.log.isEmpty())
        assertTrue(second.warnings.isEmpty())
    }

    @Test fun realStoreSourceChangeDuringAttemptIsProfileChanged() = runBlocking {
        val directory = temp.newFolder()
        val store = ProfileStore(null, directory, SshProfileResources(temp.newFolder())) {}
        val id = UUID.randomUUID()
        store.compareAndSave(UUID.randomUUID(), id, null, Conf.fromKey("A", source), null)
        val token = VpnLive.gate.next()
        val input = readSessionInput(store, id.toString(), token)
        assertNotNull(input.ticket)
        expectFailure(VpnErrorCode.PROFILE_CHANGED) {
            resolveSessionSnapshot(input.ticket!!, input.snapshot, { ensureSessionAttempt(token) }, { String(it) },
                fetch = {
                    File(directory, "$id.conf").writeText(Conf.fromKey("A", "ssconf://changed.example/config"))
                    good
                },
                discard = store::discardSnapshot, commit = store::commitSnapshot, warn = { fail("warned") })
        }
        Unit
    }
}
