package org.bimeron.app

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Explicit refresh against a real [ProfileStore] (internal constructor, temp dir). */
class SSConfRefreshStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val sourceA = "ssconf://a.example/config"
    private val sourceB = "ssconf://b.example/config"
    private val root by lazy { temp.root.canonicalFile }
    private val directory by lazy { File(root, "profiles").apply { mkdir() } }
    private val store by lazy { ProfileStore(null, directory, SshProfileResources(File(root, "ssh-resources")), coreValidation = {}) }
    private fun snap(id: UUID) = File(File(root, "ssconf"), "$id.snap")

    private suspend fun save(id: UUID, key: String) {
        val expected = store.load().first.firstOrNull { it.id == id.toString() }?.revision
        assertTrue(store.compareAndSave(UUID.randomUUID(), id, expected, Conf.fromKey("Profile", key), null) is CommitResult.Committed)
    }

    private fun CoroutineScope.coordinator(fetch: suspend (String) -> ByteArray, offers: MutableList<String> = mutableListOf()) =
        SSConfRefreshCoordinator(CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job])),
            read = { id, valid -> store.readForSession(id, valid) },
            commit = { ticket, body -> store.commitSnapshot(ticket, body) },
            fetch = fetch, validate = { _, _ -> }, needsConsent = { false },
            offerApply = { offers += it; true })

    private val SSConfRefreshCoordinator.result get() = (state.value as? SSConfRefreshState.Finished)?.result

    @Test fun refreshedSnapshotIsPersistedAndReused() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        val offers = mutableListOf<String>()
        val fetched = mutableListOf<String>()
        val refresh = coordinator({ fetched += it; "fresh body".toByteArray() }, offers)
        refresh.refresh(id.toString()).join()
        assertEquals(SSConfRefreshResult.REFRESHED, refresh.result)
        assertEquals(listOf(sourceA), fetched)
        assertEquals(listOf(id.toString()), offers)
        assertTrue(snap(id).isFile)
        val reopened = ProfileStore(null, directory, SshProfileResources(File(root, "ssh-resources")), coreValidation = {})
        assertArrayEquals("fresh body".toByteArray(), reopened.readForSession(id.toString()) { true }.snapshot!!.body)
        coroutineContext.cancelChildren()
    }

    @Test fun sourceChangeDuringFetchIsProfileChangedAndNotPersisted() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        val offers = mutableListOf<String>()
        val refresh = coordinator({ save(id, sourceB); "old source body".toByteArray() }, offers)
        refresh.refresh(id.toString()).join()
        assertEquals(SSConfRefreshResult.PROFILE_CHANGED, refresh.result)
        assertTrue(offers.isEmpty())
        assertNull(store.readForSession(id.toString()) { true }.snapshot)
        coroutineContext.cancelChildren()
    }

    @Test fun supersededRefreshLeavesOnlyTheNewerSnapshot() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val refresh = coordinator({
            if (++calls == 1) { firstEntered.complete(Unit); withContext(NonCancellable) { release.await() }; "older".toByteArray() }
            else "newer".toByteArray()
        })
        val first = refresh.refresh(id.toString())
        firstEntered.await()
        val second = refresh.refresh(id.toString())
        second.join()
        release.complete(Unit)
        first.join()
        assertTrue(first.isCancelled)
        assertEquals(SSConfRefreshResult.REFRESHED, refresh.result)
        assertArrayEquals("newer".toByteArray(), store.readForSession(id.toString()) { true }.snapshot!!.body)
        coroutineContext.cancelChildren()
    }

    @Test fun plainProfileIsInvalidForRefresh() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, "ss://YWVzLTEyOC1nY206cGFzcw@example.com:443")
        val refresh = coordinator({ fail("no fetch"); ByteArray(0) })
        refresh.refresh(id.toString()).join()
        assertEquals(SSConfRefreshResult.INVALID_PROFILE, refresh.result)
        refresh.refresh(UUID.randomUUID().toString()).join()
        assertEquals(SSConfRefreshResult.INVALID_PROFILE, refresh.result)
        assertFalse(File(root, "ssconf").exists() && File(root, "ssconf").list()!!.isNotEmpty())
        coroutineContext.cancelChildren()
    }

    @Test fun committedOfferUsesTheSaveApplyPath() = runBlocking<Unit> {
        val id = UUID.randomUUID(); save(id, sourceA)
        val runtime = MutableStateFlow(VpnState("connected", activeProfileId = id.toString(), attemptProfileId = id.toString(), connectedAtElapsed = 5))
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val operations = AppModelOperations(scope, { emptyList<Profile>() to false }, { _, _ -> error("no save") },
            { error("no delete") }, { null }, {}, runtime, {}, {})
        val refresh = SSConfRefreshCoordinator(scope,
            read = { pid, valid -> store.readForSession(pid, valid) },
            commit = { ticket, body -> store.commitSnapshot(ticket, body) },
            fetch = { "body".toByteArray() }, validate = { _, _ -> }, needsConsent = { false },
            offerApply = operations::offerApply)
        refresh.refresh(id.toString()).join()
        assertEquals(id.toString(), operations.pendingApply.value)
        // A new session invalidates the offer exactly as for a saved change.
        runtime.value = runtime.value.copy(connectedAtElapsed = 6)
        yield()
        assertNull(operations.pendingApply.value)
        scope.cancel()
        coroutineContext.cancelChildren()
    }
}
