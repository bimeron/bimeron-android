package org.bimeron.app

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.*
import org.junit.Test

/** Explicit SSConf refresh (contract §7.3) with fake store, fetch, Core validation and consent. */
class SSConfRefreshCoordinatorTest {
    private val source = "ssconf://a.example/config"

    private class Fixture(parent: CoroutineScope, private val source: String) {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        val runtime = MutableStateFlow(VpnState())
        val profileId = UUID.randomUUID().toString()
        var stops = 0
        val starts = mutableListOf<String>()
        val operations = AppModelOperations(scope, { emptyList<Profile>() to false }, { _, _ -> error("no save") },
            { error("no delete") }, { null }, {}, runtime, { stops++ }, {})
        var ssconf = true
        var readFails = false
        var needsConsent = false
        val tickets = mutableListOf<SnapshotTicket>()
        val fetched = mutableListOf<String>()
        var fetchHook: suspend (String) -> ByteArray = { "body".toByteArray() }
        var validateHook: suspend (ByteArray) -> Unit = {}
        val validated = mutableListOf<ByteArray>()
        val commits = mutableListOf<Pair<SnapshotTicket, ByteArray>>()
        var commitResult: (SnapshotTicket) -> SnapshotCommitResult = { ticket ->
            if (ticket.stillValid()) SnapshotCommitResult.Committed(StoredSnapshot(ByteArray(1), Sha256.of(ByteArray(1)), 0))
            else SnapshotCommitResult.Conflict(ConflictReason.STALE_TICKET)
        }
        var seq = 0L
        val refresh = SSConfRefreshCoordinator(
            scope = scope,
            read = { id, stillValid ->
                yield()
                if (readFails) throw ProfileError("INVALID_PROFILE")
                val uuid = UUID.fromString(id)
                val ticket = if (ssconf) SnapshotTicket(uuid, source, ++seq, stillValid).also { tickets += it } else null
                SessionInput(uuid, "text".toByteArray(), ProfileRevision(1, 1), Sha256.of(ByteArray(0)),
                    if (ssconf) SSConfBinding(source) else null, ticket, null)
            },
            commit = { ticket, body -> commits += ticket to body; commitResult(ticket) },
            fetch = { fetched += it; fetchHook(it) },
            validate = { _, body -> validated += body; validateHook(body) },
            needsConsent = { needsConsent },
            offerApply = operations::offerApply,
            io = Dispatchers.Unconfined,
        )
        fun connected(id: String = profileId) {
            runtime.value = VpnState("connected", activeProfileId = id, attemptProfileId = id, connectedAtElapsed = 10)
        }
        fun result() = (refresh.state.value as? SSConfRefreshState.Finished)?.result
    }

    private fun scenario(block: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture(this, source)
        try { withTimeout(5000) { f.block() } } finally { f.job.cancelAndJoin() }
    }

    @Test fun consentDeniedEndsWithConsentRequiredAndNoFetch() = scenario {
        needsConsent = true
        val job = refresh.refresh(profileId)
        val request = refresh.consent.filterNotNull().first()
        assertEquals(profileId, request.profileId)
        assertTrue(refresh.markLaunched(request))
        refresh.answerConsent(false)
        job.join()
        assertEquals(SSConfRefreshResult.CONSENT_REQUIRED, result())
        assertTrue(fetched.isEmpty())
        assertTrue(commits.isEmpty())
        assertNull(refresh.consent.value)
    }

    @Test fun consentGrantedResumesOnlyThisRefresh() = scenario {
        needsConsent = true
        val job = refresh.refresh(profileId)
        val request = refresh.consent.filterNotNull().first()
        // An answer for a request that was never launched is ignored.
        refresh.answerConsent(true)
        yield()
        assertTrue(job.isActive)
        assertTrue(refresh.markLaunched(request))
        // A recreated host does not launch the same request again.
        assertFalse(refresh.markLaunched(request))
        refresh.answerConsent(true)
        job.join()
        assertEquals(SSConfRefreshResult.REFRESHED, result())
        assertEquals(listOf(source), fetched)
        assertEquals(1, commits.size)
    }

    @Test fun cancelWhileConsentPendingEndsRefresh() = scenario {
        needsConsent = true
        val job = refresh.refresh(profileId)
        val request = refresh.consent.filterNotNull().first()
        refresh.markLaunched(request)
        refresh.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(SSConfRefreshState.Idle, refresh.state.value)
        assertNull(refresh.consent.value)
        // A late activity result after the cancel resolves nothing and starts nothing.
        refresh.answerConsent(true, request)
        assertTrue(fetched.isEmpty())
        assertTrue(commits.isEmpty())
    }

    @Test fun supersededRefreshHasStaleTicketAndDoesNotCommit() = scenario {
        val firstEntered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<ByteArray>()
        fetchHook = { firstEntered.complete(Unit); never.await() }
        val first = refresh.refresh(profileId)
        firstEntered.await()
        val firstTicket = tickets.single()
        assertTrue(firstTicket.stillValid())
        fetchHook = { "second".toByteArray() }
        val second = refresh.refresh(profileId)
        assertFalse(firstTicket.stillValid())
        first.join(); second.join()
        assertTrue(first.isCancelled)
        assertEquals(1, commits.size)
        assertSame(tickets[1], commits.single().first)
        assertArrayEquals("second".toByteArray(), commits.single().second)
        assertEquals(SSConfRefreshResult.REFRESHED, result())
    }

    @Test fun staleTicketAtCommitIsNotOffered() = scenario {
        connected()
        // The generation moves on while the commit is in flight: the store reports STALE_TICKET.
        commitResult = { ticket -> refresh.cancel(); assertFalse(ticket.stillValid()); SnapshotCommitResult.Conflict(ConflictReason.STALE_TICKET) }
        val job = refresh.refresh(profileId)
        job.join()
        assertNull(operations.pendingApply.value)
        assertEquals(SSConfRefreshState.Idle, refresh.state.value)
    }

    @Test fun committedWhileConnectedToSameProfileOffersApply() = scenario {
        connected()
        refresh.refresh(profileId).join()
        assertEquals(SSConfRefreshResult.REFRESHED, result())
        assertEquals(profileId, operations.pendingApply.value)
        // Nothing reconnects without confirmation.
        assertTrue(starts.isEmpty())
        assertEquals(0, stops)
        // The offer is the one save makes; the dialog confirmation consumes it.
        operations.applyPending { starts += it }
        assertNull(operations.pendingApply.value)
    }

    @Test fun committedWhileReconnectingToSameProfileOffersApply() = scenario {
        runtime.value = VpnState("reconnecting", activeProfileId = profileId, attemptProfileId = profileId, connectedAtElapsed = 10)
        refresh.refresh(profileId).join()
        assertEquals(profileId, operations.pendingApply.value)
    }

    @Test fun committedWhileDisconnectedOrOnAnotherProfileDoesNotOfferApply() = scenario {
        refresh.refresh(profileId).join()
        assertEquals(SSConfRefreshResult.REFRESHED, result())
        assertNull(operations.pendingApply.value)
        connected(UUID.randomUUID().toString())
        refresh.refresh(profileId).join()
        assertEquals(SSConfRefreshResult.REFRESHED, result())
        assertNull(operations.pendingApply.value)
        runtime.value = VpnState("connecting", attemptProfileId = profileId)
        refresh.refresh(profileId).join()
        assertNull(operations.pendingApply.value)
        assertEquals(3, commits.size)
    }

    @Test fun notPersistedReportsNotSavedWithoutApply() = scenario {
        connected()
        commitResult = { SnapshotCommitResult.NotPersisted }
        refresh.refresh(profileId).join()
        assertEquals(SSConfRefreshResult.NOT_SAVED, result())
        assertFalse(SSConfRefreshResult.NOT_SAVED.success)
        assertNull(operations.pendingApply.value)
    }

    @Test fun conflictReportsProfileChangedWithoutApply() = scenario {
        connected()
        for (reason in listOf(ConflictReason.SOURCE_CHANGED, ConflictReason.DELETED, ConflictReason.SUPERSEDED)) {
            commitResult = { SnapshotCommitResult.Conflict(reason) }
            refresh.refresh(profileId).join()
            assertEquals(SSConfRefreshResult.PROFILE_CHANGED, result())
            assertNull(operations.pendingApply.value)
        }
    }

    @Test fun validateFailureIsSnapshotInvalidAndNotCommitted() = scenario {
        validateHook = { throw SSConfSnapshots.InvalidSnapshot() }
        refresh.refresh(profileId).join()
        assertEquals(SSConfRefreshResult.SNAPSHOT_INVALID, result())
        assertEquals(1, validated.size)
        validateHook = {}
        fetchHook = { ByteArray(0) }
        refresh.refresh(profileId).join()
        assertEquals(SSConfRefreshResult.SNAPSHOT_INVALID, result())
        fetchHook = { ByteArray(SSConfSnapshots.MAX_BODY + 1) }
        refresh.refresh(profileId).join()
        assertEquals(SSConfRefreshResult.SNAPSHOT_INVALID, result())
        // Out-of-range bodies never reach Core.
        assertEquals(1, validated.size)
        validateHook = { throw UnsatisfiedLinkError() }
        fetchHook = { "body".toByteArray() }
        refresh.refresh(profileId).join()
        assertEquals(SSConfRefreshResult.CORE_INCOMPATIBLE, result())
        assertTrue(commits.isEmpty())
    }

    @Test fun fetchFailuresMapToFixedResults() = scenario {
        val cases = listOf(
            VpnErrorCode.SSCONF_NETWORK_UNAVAILABLE to SSConfRefreshResult.NETWORK_UNAVAILABLE,
            VpnErrorCode.SSCONF_FETCH_FAILED to SSConfRefreshResult.FETCH_FAILED,
            VpnErrorCode.CORE_INCOMPATIBLE to SSConfRefreshResult.CORE_INCOMPATIBLE,
        )
        for ((code, expected) in cases) {
            fetchHook = { throw SSConfFailure(code) }
            refresh.refresh(profileId).join()
            assertEquals(expected, result())
        }
        fetchHook = { throw IllegalStateException("unclassified") }
        refresh.refresh(profileId).join()
        assertEquals(SSConfRefreshResult.FETCH_FAILED, result())
        fetchHook = { throw NoClassDefFoundError() }
        refresh.refresh(profileId).join()
        assertEquals(SSConfRefreshResult.CORE_INCOMPATIBLE, result())
        assertTrue(validated.isEmpty())
        assertTrue(commits.isEmpty())
    }

    @Test fun nonSSConfOrUnreadableProfileIsInvalidProfile() = scenario {
        ssconf = false
        refresh.refresh(profileId).join()
        assertEquals(SSConfRefreshResult.INVALID_PROFILE, result())
        ssconf = true
        readFails = true
        refresh.refresh(profileId).join()
        assertEquals(SSConfRefreshResult.INVALID_PROFILE, result())
        assertTrue(fetched.isEmpty())
        assertTrue(commits.isEmpty())
    }

    @Test fun cancellationPropagatesAndCommitsNothing() = scenario {
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        fetchHook = {
            entered.complete(Unit)
            try { awaitCancellation() } catch (e: CancellationException) { cancelled = true; throw e }
        }
        val job = refresh.refresh(profileId)
        entered.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(cancelled)
        assertTrue(commits.isEmpty())
        assertEquals(SSConfRefreshState.Idle, refresh.state.value)
        // Cancellation inside validation is not reported as an invalid snapshot.
        fetchHook = { "body".toByteArray() }
        validateHook = { throw CancellationException("cancelled") }
        val second = refresh.refresh(profileId)
        second.join()
        assertTrue(second.isCancelled)
        assertTrue(commits.isEmpty())
        assertNull(result())
    }

    @Test fun refreshNeverTouchesVpnLiveOrAttemptGate() = scenario {
        val token = VpnLive.gate.next()
        val before = VpnLive.state.value
        connected()
        needsConsent = true
        val job = refresh.refresh(profileId)
        val request = refresh.consent.filterNotNull().first()
        refresh.markLaunched(request)
        refresh.answerConsent(true)
        job.join()
        assertEquals(SSConfRefreshResult.REFRESHED, result())
        assertSame(before, VpnLive.state.value)
        assertTrue(VpnLive.gate.accepts(token))
        assertEquals(0, stops)
        assertTrue(starts.isEmpty())
    }

    @Test fun fetchAndValidationRunOffTheCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        var fetchThread: Thread? = null
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val id = UUID.randomUUID()
        val refresh = SSConfRefreshCoordinator(scope,
            read = { _, valid -> SessionInput(id, ByteArray(1), ProfileRevision(1, 1), Sha256.of(ByteArray(0)), SSConfBinding(source), SnapshotTicket(id, source, 1, valid), null) },
            commit = { _, _ -> SnapshotCommitResult.NotPersisted },
            fetch = { fetchThread = Thread.currentThread(); "body".toByteArray() },
            validate = { _, _ -> }, needsConsent = { false }, offerApply = { false })
        refresh.refresh(id.toString()).join()
        assertNotSame(caller, fetchThread)
        scope.cancel()
    }

    @Test fun snapshotValidationRejectsOutOfRangeBodiesWithoutCoreOrText() = runBlocking {
        for (body in listOf(ByteArray(0), ByteArray(SSConfSnapshots.MAX_BODY + 1))) {
            val failure = try { SSConfSnapshots.validate(source, body); null } catch (e: SSConfSnapshots.InvalidSnapshot) { e }
            assertNotNull(failure)
            assertNull(failure!!.message)
            assertNull(failure.cause)
            assertEquals(VpnErrorCode.SSCONF_SNAPSHOT_INVALID, failure.code)
        }
    }

    @Test fun everyResultHasItsOwnMessage() {
        val messages = SSConfRefreshResult.entries.map { it.messageRes }
        assertEquals(messages.size, messages.toSet().size)
        assertEquals(listOf(SSConfRefreshResult.REFRESHED), SSConfRefreshResult.entries.filter { it.success })
    }

}
