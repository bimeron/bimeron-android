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
        val fallbacks = mutableListOf<VpnErrorCode>()
        var committed: ByteArray? = null
        var discarded: Sha256? = null
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

    /** A user connect unless [recovery]; Core rejects only [rejected]. Fallbacks write the production journal note. */
    private suspend fun resolve(
        calls: Calls,
        stored: StoredSnapshot?,
        recovery: Boolean = false,
        fetched: () -> ByteArray = { good },
        result: SnapshotCommitResult = SnapshotCommitResult.Committed(stored(good)),
        ensureAttempt: suspend () -> Unit = {},
        ticket: SnapshotTicket = ticket(),
        fetch: suspend () -> ByteArray = { calls.log += "fetch"; fetched() },
    ): String = resolveSessionSnapshot(
        ticket = ticket,
        stored = stored,
        recovery = recovery,
        ensureAttempt = ensureAttempt,
        validate = { body ->
            calls.log += "validate:${String(body)}"
            if (body.contentEquals(rejected)) throw IllegalStateException("core rejected $source") else String(body)
        },
        fetch = fetch,
        discard = { _, digest -> calls.log += "discard"; calls.discarded = digest },
        commit = { _, body -> calls.log += "commit"; calls.committed = body; result },
        warn = { calls.warnings += it; AppEvents.add(it, LogLevel.WARNING) },
        onFallback = { code ->
            calls.fallbacks += code
            AppEvents.add(R.string.journal_ssconf_fallback, LogLevel.WARNING, code.name)
        },
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

    private fun journalLeaksSource() =
        AppEvents.entries.value.any { it.message.contains("secret-host") || it.message.contains("ssconf://") || it.message.contains("valid body") }

    // User connect: always one fresh fetch.

    @Test fun userConnectFetchesOnceCommitsAndUsesFreshEvenWithValidStored() = runBlocking {
        val calls = Calls()
        assertEquals("other valid body", resolve(calls, stored(good), fetched = { other }))
        assertEquals(listOf("fetch", "validate:other valid body", "commit"), calls.log)
        assertTrue(other.contentEquals(calls.committed))
        assertTrue(calls.fallbacks.isEmpty())
        assertTrue(calls.warnings.isEmpty())
        assertTrue(AppEvents.entries.value.isEmpty())
    }

    @Test fun userConnectWithoutStoredFetchesOnceAndCommits() = runBlocking {
        val calls = Calls()
        assertEquals("valid body", resolve(calls, null))
        assertEquals(listOf("fetch", "validate:valid body", "commit"), calls.log)
        assertTrue(good.contentEquals(calls.committed))
        assertTrue(calls.warnings.isEmpty())
    }

    @Test fun fetchFailureWithValidStoredFallsBackWithJournalNote() = runBlocking {
        for (code in listOf(VpnErrorCode.SSCONF_FETCH_FAILED, VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE)) {
            AppEvents.clear()
            val calls = Calls()
            assertEquals("valid body", resolve(calls, stored(good), fetched = { throw SSConfFailure(code) }))
            assertEquals(listOf("fetch", "validate:valid body"), calls.log)
            assertNull(calls.committed)
            assertEquals(listOf(code), calls.fallbacks)
            val entry = AppEvents.entries.value.single()
            assertEquals(app.getString(R.string.journal_ssconf_fallback, code.name), entry.message)
            assertEquals(LogLevel.WARNING, entry.level)
            assertFalse(journalLeaksSource())
        }
    }

    @Test fun fetchFailureWithRejectedStoredDiscardsAndFailsWithFetchCode() = runBlocking {
        for (code in listOf(VpnErrorCode.SSCONF_FETCH_FAILED, VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE)) {
            val calls = Calls()
            expectFailure(code) { resolve(calls, stored(rejected), fetched = { throw SSConfFailure(code) }) }
            assertEquals(listOf("fetch", "validate:rejected body", "discard"), calls.log)
            assertEquals(Sha256.of(rejected), calls.discarded)
            assertTrue(calls.fallbacks.isEmpty())
        }
    }

    @Test fun fetchFailureWithoutStoredFailsWithFetchCode() = runBlocking {
        for (code in listOf(VpnErrorCode.SSCONF_FETCH_FAILED, VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE)) {
            val calls = Calls()
            expectFailure(code) { resolve(calls, null, fetched = { throw SSConfFailure(code) }) }
            assertEquals(listOf("fetch"), calls.log)
            assertTrue(calls.fallbacks.isEmpty())
            assertTrue(AppEvents.entries.value.isEmpty())
        }
    }

    @Test fun rejectedFetchedBodyFallsBackToValidStoredWithoutCommit() = runBlocking {
        val calls = Calls()
        assertEquals("valid body", resolve(calls, stored(good), fetched = { rejected }))
        assertEquals(listOf("fetch", "validate:rejected body", "validate:valid body"), calls.log)
        assertNull(calls.committed)
        assertEquals(listOf(VpnErrorCode.SSCONF_SNAPSHOT_INVALID), calls.fallbacks)
    }

    @Test fun rejectedFetchedBodyWithoutUsableStoredIsSnapshotInvalidWithoutCommit() = runBlocking {
        val none = Calls()
        expectFailure(VpnErrorCode.SSCONF_SNAPSHOT_INVALID) { resolve(none, null, fetched = { rejected }) }
        assertEquals(listOf("fetch", "validate:rejected body"), none.log)
        assertNull(none.committed)
        val bad = Calls()
        expectFailure(VpnErrorCode.SSCONF_SNAPSHOT_INVALID) { resolve(bad, stored(rejected), fetched = { rejected }) }
        assertEquals(listOf("fetch", "validate:rejected body", "validate:rejected body", "discard"), bad.log)
        assertNull(bad.committed)
        assertTrue(bad.fallbacks.isEmpty())
    }

    @Test fun emptyOrOversizedFetchedBodyIsSnapshotInvalidWithoutValidationOrCommit() = runBlocking {
        for (body in listOf(ByteArray(0), ByteArray(SSConfSnapshots.MAX_BODY + 1) { 'a'.code.toByte() })) {
            val calls = Calls()
            expectFailure(VpnErrorCode.SSCONF_SNAPSHOT_INVALID) { resolve(calls, null, fetched = { body }) }
            assertEquals(listOf("fetch"), calls.log)
            val fallback = Calls()
            assertEquals("valid body", resolve(fallback, stored(good), fetched = { body }))
            assertEquals(listOf("fetch", "validate:valid body"), fallback.log)
            assertEquals(listOf(VpnErrorCode.SSCONF_SNAPSHOT_INVALID), fallback.fallbacks)
        }
    }

    @Test fun notPersistedContinuesWithInMemorySnapshotAndWarns() = runBlocking {
        val calls = Calls()
        assertEquals("other valid body", resolve(calls, stored(good), fetched = { other }, result = SnapshotCommitResult.NotPersisted))
        assertEquals(listOf(SNAPSHOT_NOT_PERSISTED), calls.warnings)
        assertTrue(calls.fallbacks.isEmpty())
        val entry = AppEvents.entries.value.single()
        assertEquals("SNAPSHOT_NOT_PERSISTED", entry.message)
        assertEquals(LogLevel.WARNING, entry.level)
    }

    @Test fun everyConflictIsProfileChangedWithoutFallback() = runBlocking {
        for (reason in listOf(ConflictReason.SOURCE_CHANGED, ConflictReason.DELETED,
            ConflictReason.SUPERSEDED, ConflictReason.STALE_TICKET)) {
            val calls = Calls()
            expectFailure(VpnErrorCode.PROFILE_CHANGED) {
                resolve(calls, stored(good), result = SnapshotCommitResult.Conflict(reason))
            }
            assertEquals(listOf("fetch", "validate:valid body", "commit"), calls.log)
            assertTrue(calls.warnings.isEmpty())
            assertTrue(calls.fallbacks.isEmpty())
        }
    }

    @Test fun unexpectedFetchErrorPropagatesWithoutFallback() = runBlocking {
        val calls = Calls()
        val error = IllegalStateException("not an ssconf failure")
        try {
            resolve(calls, stored(good), fetched = { throw error })
            fail("fell back")
        } catch (e: IllegalStateException) {
            assertTrue(e === error)
        }
        assertEquals(listOf("fetch"), calls.log)
        assertTrue(calls.fallbacks.isEmpty())
    }

    // Recovery: stored snapshot only, never a fetch.

    @Test fun recoveryUsesValidStoredWithoutFetch() = runBlocking {
        val calls = Calls()
        assertEquals("valid body", resolve(calls, stored(good), recovery = true, fetched = { fail("fetched"); good }))
        assertEquals(listOf("validate:valid body"), calls.log)
        assertTrue(calls.fallbacks.isEmpty())
        assertTrue(AppEvents.entries.value.isEmpty())
    }

    @Test fun recoveryWithoutUsableStoredNeedsUserAndNeverFetches() = runBlocking {
        val none = Calls()
        expectFailure(VpnErrorCode.RECOVERY_NEEDS_USER) { resolve(none, null, recovery = true, fetched = { fail("fetched"); good }) }
        assertTrue(none.log.isEmpty())
        val bad = Calls()
        expectFailure(VpnErrorCode.RECOVERY_NEEDS_USER) {
            resolve(bad, stored(rejected), recovery = true, fetched = { fail("fetched"); good })
        }
        assertEquals(listOf("validate:rejected body", "discard"), bad.log)
        assertNull(bad.committed)
    }

    // Cancellation always propagates and never falls back.

    @Test fun cancellationDuringFetchPropagatesWithoutFallbackOrCommit() = runBlocking {
        val calls = Calls()
        try {
            resolve(calls, stored(good), fetched = { throw CancellationException("cancelled") })
            fail("cancellation swallowed")
        } catch (e: CancellationException) {
            assertFalse(e is SSConfFailure)
        }
        assertEquals(listOf("fetch"), calls.log)
        assertNull(calls.committed)
        assertTrue(calls.fallbacks.isEmpty())
    }

    @Test fun cancellationDuringFallbackValidationPropagates() = runBlocking {
        val calls = Calls()
        try {
            resolveSessionSnapshot(ticket(), stored(good), recovery = false, ensureAttempt = {},
                validate = { throw CancellationException("cancelled") },
                fetch = { calls.log += "fetch"; throw SSConfFailure(VpnErrorCode.SSCONF_FETCH_FAILED) },
                discard = { _, _ -> calls.log += "discard" }, commit = { _, _ -> fail("committed"); SnapshotCommitResult.NotPersisted },
                warn = {}, onFallback = { calls.fallbacks += it })
            fail("cancellation swallowed")
        } catch (e: CancellationException) {
            assertFalse(e is SSConfFailure)
        }
        assertEquals(listOf("fetch"), calls.log)
        assertTrue(calls.fallbacks.isEmpty())
    }

    @Test fun staleTokenAfterFailedFetchIsCancellationNotFallback() = runBlocking {
        val calls = Calls()
        val token = VpnLive.gate.next()
        try {
            resolve(calls, stored(good), ensureAttempt = { ensureSessionAttempt(token) },
                fetched = { VpnLive.gate.next(); throw SSConfFailure(VpnErrorCode.SSCONF_FETCH_FAILED) })
            fail("stale attempt continued")
        } catch (e: CancellationException) {
            assertFalse(e is SSConfFailure)
        }
        assertEquals("A stale attempt does not validate the stored snapshot", listOf("fetch"), calls.log)
        assertTrue(calls.fallbacks.isEmpty())
        assertNull(calls.committed)
    }

    /**
     * Real chain: [fetchSSConf] mapping (fake lease scope and request only) → [resolveSessionSnapshot]
     * → [sessionFailureCode] → the same [VpnLive.publishFailure] call BimeronVpnService.publishFailure makes.
     */
    @Test fun fetchFailuresPublishFixedCodesWithoutSourceInStateOrJournal() = runBlocking {
        val cases = listOf<Pair<VpnErrorCode, (FakeFetchLease) -> SSConfLeaseScope>>(
            VpnErrorCode.SSCONF_FETCH_FAILED to { lease -> lease.scope },
            VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE to { lease ->
                { block -> lease.state.invalidate(SSConfDiagnostic.NETWORK_LOST); block(lease) }
            },
            VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE to { _ ->
                { throw SSConfNetworkUnavailableException("state=INVALIDATED $source") }
            },
        )
        for ((expected, scopeOf) in cases) {
            AppEvents.clear()
            val calls = Calls()
            val lease = FakeFetchLease()
            val token = VpnLive.gate.next()
            val factory = FakeRequestFactory {
                FakeFetchRequest { throw java.io.IOException("resolve failed for $source") }
            }
            val error = try {
                resolve(calls, null, ensureAttempt = { ensureSessionAttempt(token) }, fetch = {
                    calls.log += "fetch"
                    fetchSSConf(source, scopeOf(lease), factory)
                })
                null
            } catch (e: SSConfFailure) { e } ?: throw AssertionError("Expected $expected")
            assertNull(calls.committed)
            assertEquals(listOf("fetch"), calls.log)
            assertTrue(calls.fallbacks.isEmpty())
            assertEquals(0, lease.openRegistrations)
            val code = sessionFailureCode(error)
            assertEquals(expected, code)
            VpnLive.publishFailure(code, app.getString(code.messageRes), "profile-${code.name}", token)
            val state = VpnLive.state.value
            assertEquals("failed", state.state)
            assertEquals(expected, state.code)
            assertEquals(app.getString(expected.messageRes), state.message)
            assertFalse(state.toString().contains("secret-host"))
            assertFalse(error.toString().contains("secret-host"))
            assertFalse(AppEvents.entries.value.any { it.message.contains("secret-host") || it.message.contains("ssconf://") })
        }
    }

    @Test fun staleTokenConflictIsCancellationNotProfileChanged() = runBlocking {
        val store = ProfileStore(null, temp.newFolder(), SshProfileResources(temp.newFolder())) {}
        val id = UUID.randomUUID()
        store.compareAndSave(UUID.randomUUID(), id, null, Conf.fromKey("A", source), null)
        val token = VpnLive.gate.next()
        val input = readSessionInput(store, id.toString(), token)
        var result: SnapshotCommitResult? = null
        try {
            resolveSessionSnapshot(input.ticket!!, input.snapshot, recovery = false, { ensureSessionAttempt(token) },
                { String(it) }, fetch = { good }, discard = store::discardSnapshot,
                commit = { ticket, body ->
                    // A newer attempt supersedes this one between the last check and the commit.
                    VpnLive.gate.next()
                    store.commitSnapshot(ticket, body).also { result = it }
                },
                warn = { fail("warned") }, onFallback = { fail("fell back") })
            fail("stale attempt continued")
        } catch (e: CancellationException) {
            assertFalse(e is SSConfFailure)
        }
        assertEquals(SnapshotCommitResult.Conflict(ConflictReason.STALE_TICKET), result)
        assertNull(store.readForSession(id.toString()) { true }.snapshot)
    }

    @Test fun nativeLoadFailureDuringValidationIsCoreIncompatible() = runBlocking {
        val errors = listOf<LinkageError>(UnsatisfiedLinkError("gojni"), NoSuchMethodError("newSSConfSnapshot"),
            NoClassDefFoundError("Androidcore").apply { initCause(UnsatisfiedLinkError("gojni")) })
        for (error in errors) {
            val calls = Calls()
            expectFailure(VpnErrorCode.CORE_INCOMPATIBLE) {
                resolveSessionSnapshot(ticket(), stored(good), recovery = true, {}, validate = { throw error },
                    fetch = { calls.log += "fetch"; good }, discard = { _, _ -> calls.log += "discard" },
                    commit = { _, _ -> calls.log += "commit"; SnapshotCommitResult.NotPersisted }, warn = {},
                    onFallback = { calls.log += "fallback" })
            }
            assertTrue(calls.log.isEmpty())
            // A user connect whose fetched body hits a broken Core never falls back either.
            val user = Calls()
            expectFailure(VpnErrorCode.CORE_INCOMPATIBLE) {
                resolveSessionSnapshot(ticket(), stored(good), recovery = false, {}, validate = { throw error },
                    fetch = { user.log += "fetch"; good }, discard = { _, _ -> user.log += "discard" },
                    commit = { _, _ -> user.log += "commit"; SnapshotCommitResult.NotPersisted }, warn = {},
                    onFallback = { user.log += "fallback" })
            }
            assertEquals(listOf("fetch"), user.log)
            assertEquals(VpnErrorCode.CORE_INCOMPATIBLE, sessionFailureCode(SSConfFailure(VpnErrorCode.CORE_INCOMPATIBLE)))
        }
    }

    @Test fun unrelatedLinkageErrorsPropagate() = runBlocking {
        for (error in listOf<LinkageError>(NoClassDefFoundError("Unrelated"), ClassFormatError("bad"))) {
            try {
                resolveSessionSnapshot(ticket(), stored(good), recovery = false, {}, { String(it) }, fetch = { throw error },
                    discard = { _, _ -> }, commit = { _, _ -> SnapshotCommitResult.NotPersisted }, warn = {},
                    onFallback = { fail("fell back") })
                fail("Expected $error")
            } catch (e: LinkageError) {
                assertTrue(e === error)
            }
        }
    }

    @Test fun storeLinkageErrorsAreNotMappedToCoreIncompatible() = runBlocking {
        val fromCommit = NoSuchMethodError("commitSnapshot")
        try {
            resolveSessionSnapshot(ticket(), null, recovery = false, {}, { String(it) }, fetch = { good },
                discard = { _, _ -> }, commit = { _, _ -> throw fromCommit }, warn = {}, onFallback = {})
            fail("Expected $fromCommit")
        } catch (e: LinkageError) {
            assertTrue(e === fromCommit)
        }
        val fromDiscard = NoSuchMethodError("discardSnapshot")
        try {
            resolveSessionSnapshot(ticket(), stored(rejected), recovery = false, {},
                { body -> if (body.contentEquals(rejected)) throw IllegalStateException("rejected") else String(body) },
                fetch = { throw SSConfFailure(VpnErrorCode.SSCONF_FETCH_FAILED) }, discard = { _, _ -> throw fromDiscard },
                commit = { _, _ -> SnapshotCommitResult.NotPersisted }, warn = {}, onFallback = {})
            fail("Expected $fromDiscard")
        } catch (e: LinkageError) {
            assertTrue(e === fromDiscard)
        }
    }

    @Test fun nativeLoadFailureDuringFetchIsCoreIncompatible() = runBlocking {
        var committed = false
        expectFailure(VpnErrorCode.CORE_INCOMPATIBLE) {
            resolveSessionSnapshot(ticket(), stored(good), recovery = false, {}, { String(it) },
                fetch = { throw UnsatisfiedLinkError("gojni") }, discard = { _, _ -> },
                commit = { _, _ -> committed = true; SnapshotCommitResult.NotPersisted }, warn = {},
                onFallback = { fail("fell back") })
        }
        assertFalse(committed)
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

    /** Real store: every user connect fetches and commits; recovery and a failed fetch reuse the last commit. */
    @Test fun realStoreUserConnectsRefetchWhileRecoveryAndFailuresReuseLastCommit() = runBlocking {
        val directory = temp.newFolder()
        val store = ProfileStore(null, directory, SshProfileResources(temp.newFolder())) {}
        val id = UUID.randomUUID()
        store.compareAndSave(UUID.randomUUID(), id, null, Conf.fromKey("A", source), null)
        suspend fun attempt(calls: Calls, recovery: Boolean, fetch: () -> ByteArray): String {
            val token = VpnLive.gate.next()
            val input = readSessionInput(store, id.toString(), token)
            return resolveSessionSnapshot(input.ticket!!, input.snapshot, recovery, { ensureSessionAttempt(token) },
                { String(it) }, { calls.log += "fetch"; fetch() }, store::discardSnapshot, store::commitSnapshot,
                { calls.warnings += it }, { calls.fallbacks += it })
        }
        suspend fun persisted() = store.readForSession(id.toString()) { true }.snapshot?.body?.let(::String)
        val first = Calls()
        assertEquals("valid body", attempt(first, recovery = false) { good })
        assertEquals(listOf("fetch"), first.log)
        assertEquals("valid body", persisted())
        val second = Calls()
        assertEquals("other valid body", attempt(second, recovery = false) { other })
        assertEquals(listOf("fetch"), second.log)
        assertEquals("other valid body", persisted())
        val recovery = Calls()
        assertEquals("other valid body", attempt(recovery, recovery = true) { fail("fetched"); good })
        assertTrue(recovery.log.isEmpty())
        val offline = Calls()
        assertEquals("other valid body",
            attempt(offline, recovery = false) { throw SSConfFailure(VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE) })
        assertEquals(listOf(VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE), offline.fallbacks)
        assertEquals("other valid body", persisted())
        assertTrue(first.warnings.isEmpty() && second.warnings.isEmpty() && offline.warnings.isEmpty())
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
            resolveSessionSnapshot(input.ticket!!, input.snapshot, recovery = false, { ensureSessionAttempt(token) },
                { String(it) },
                fetch = {
                    File(directory, "$id.conf").writeText(Conf.fromKey("A", "ssconf://changed.example/config"))
                    good
                },
                discard = store::discardSnapshot, commit = store::commitSnapshot, warn = { fail("warned") },
                onFallback = { fail("fell back") })
        }
        Unit
    }
}
