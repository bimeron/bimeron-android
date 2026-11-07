package org.bimeron.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class AppModelOperationsTest {
    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun await() { entered.complete(Unit); release.await() }
    }
    private class Fixture(parent: CoroutineScope) {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        val disk = mutableListOf(Profile("a", "A", "", "vpn", ""), Profile("b", "B", "", "vpn", ""))
        val runtime = MutableStateFlow(VpnState())
        var persisted: String? = "a"
        var loadHook: suspend () -> Unit = {}
        var writeHook: suspend () -> Unit = {}
        var saveHook: suspend () -> Unit = {}
        var deleteHook: suspend () -> Unit = {}
        var saves = 0
        var stops = 0
        var done = 0
        val starts = mutableListOf<String>()
        val model = AppModelOperations(scope, { loadHook(); disk.toList() to false },
            { text, id -> saveHook(); saves++; Profile(id ?: "c", "Saved", "", "vpn", text).also { p -> disk.removeAll { it.id == p.id }; disk.add(p) } },
            { id -> deleteHook(); disk.removeAll { it.id == id } }, { persisted },
            { writeHook(); persisted = it }, runtime, { stops++ }, {})
        suspend fun ready() { model.refresh().join() }
        fun connected() { runtime.value = VpnState(state = "connected", activeProfileId = "a", connectedAtElapsed = 10) }
    }
    private fun scenario(block: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture(this)
        try { withTimeout(5000) { f.block() } } finally { f.job.cancelAndJoin() }
    }
    @Test fun committedSaveSurvivesRefreshFailureAndCancelledReload() = scenario {
        ready()
        loadHook = { throw IllegalStateException() }
        model.save("committed", null, "saved") { done++ }.join()
        assertEquals(1, saves)
        assertEquals(1, done)
        assertNull(model.error.value)
        assertTrue(model.warning.value)
        assertTrue(model.profiles.value.any { it.text == "committed" })
        loadHook = { throw CancellationException() }
        model.save("second", null, "saved") { done++ }.join()
        assertEquals(2, saves)
        assertEquals(2, done)
        assertNull(model.error.value)
    }
    @Test fun refreshThenSelectionKeepsNewIntent() = scenario {
        ready(); val g = Gate(); loadHook = { g.await() }
        val refresh = model.refresh(); g.entered.await(); val select = model.select("b")
        g.release.complete(Unit); refresh.join(); select.join()
        assertEquals("b", model.selected.value); assertEquals("b", persisted)
    }
    @Test fun currentSelectionOverridesPendingDisconnectedSelection() = scenario {
        ready(); val g = Gate(); writeHook = { g.await() }
        model.requestSelect("b"); g.entered.await()
        model.requestSelect("a"); g.release.complete(Unit); model.refresh().join()
        assertEquals("a", model.selected.value); assertEquals("a", persisted)
        assertTrue(starts.isEmpty()); assertEquals(0, stops)
    }
    @Test fun currentSelectionOverridesSelectionQueuedBehindRefresh() = scenario {
        ready(); val g = Gate(); loadHook = { g.await() }
        model.refresh(); g.entered.await()
        model.requestSelect("b"); model.requestSelect("a")
        g.release.complete(Unit); model.refresh().join()
        assertEquals("a", model.selected.value); assertEquals("a", persisted)
        assertTrue(starts.isEmpty()); assertEquals(0, stops)
    }
    @Test fun refreshSaveAndDeleteCannotResurrect() = scenario {
        ready(); val g = Gate(); loadHook = { g.await() }
        val refresh = model.refresh(); g.entered.await(); val save = model.save("new", null, "") {}
        g.release.complete(Unit); refresh.join(); save.join()
        assertTrue(model.profiles.value.any { it.id == "c" })
        val delete = model.delete("c") {}; val reload = model.refresh(); delete.join(); reload.join()
        assertFalse(model.profiles.value.any { it.id == "c" })
    }
    @Test fun queuedSaveOfDeletedIdIsRejected() = scenario {
        ready(); val g = Gate(); deleteHook = { g.await() }
        val delete = model.delete("a") {}; g.entered.await(); val save = model.save("", "a", "") { done++ }
        g.release.complete(Unit); delete.join(); save.join()
        assertEquals(0, saves); assertEquals(0, done); assertNotNull(model.error.value)
        assertFalse(model.profiles.value.any { it.id == "a" })
    }
    @Test fun cancelledSwitchRestoresPersistence() = scenario {
        ready(); connected(); val g = Gate(); writeHook = { g.await() }
        model.requestSelect("b"); model.confirmSwitch { starts.add(it) }; g.entered.await()
        model.cancelSwitch(); g.release.complete(Unit); model.refresh().join()
        assertEquals("a", persisted); assertEquals("a", model.selected.value); assertTrue(starts.isEmpty()); assertEquals(0, stops)
    }
    @Test fun newerSelectionInvalidatesSwitch() = scenario {
        ready(); connected(); val g = Gate(); writeHook = { g.await() }
        model.requestSelect("b"); model.confirmSwitch { starts.add(it) }; g.entered.await()
        val select = model.select("b"); g.release.complete(Unit); select.join()
        assertEquals("b", persisted); assertEquals("b", model.selected.value); assertTrue(starts.isEmpty())
    }
    @Test fun trafficDoesNotBlockRealConfirmation() = scenario {
        ready(); connected(); val g = Gate(); writeHook = { g.await() }
        model.requestSelect("b"); model.confirmSwitch { starts.add(it) }; g.entered.await()
        runtime.value = runtime.value.copy(received = 90, sent = 30)
        g.release.complete(Unit); model.refresh().join(); assertEquals(listOf("b"), starts)
    }
    @Test fun editingActiveProfilePreservesSelectionAndCanApply() = scenario {
        ready(); connected(); model.select("b").join()
        model.save("new", "a", "", true) {}.join()
        assertEquals("b", model.selected.value); assertEquals("a", model.pendingApply.value)
        model.applyPending { starts.add(it) }; assertEquals(listOf("a"), starts)
    }
    /** Audit L-3: a confirmed switch to a core KillSwitch = true profile stops like a proxy target; it never starts. */
    @Test fun switchToKillSwitchProfileStopsInsteadOfStarting() = scenario {
        disk.add(Profile("k", "K", "", "vpn", "[Interface]\nMode = vpn\nKillSwitch = true\n"))
        ready(); connected()
        model.requestSelect("k"); assertEquals("k", model.pendingSwitch.value)
        model.confirmSwitch { starts.add(it) }; model.refresh().join()
        assertTrue(starts.isEmpty()); assertEquals(1, stops)
        assertEquals("k", model.selected.value); assertEquals("k", persisted)
    }
    @Test fun switchToSupportedProfileStillStarts() = scenario {
        disk.add(Profile("f", "F", "", "vpn", "[Interface]\nMode = vpn\nKillSwitch = false\n"))
        ready(); connected()
        model.requestSelect("f"); model.confirmSwitch { starts.add(it) }; model.refresh().join()
        assertEquals(listOf("f"), starts); assertEquals(0, stops)
    }
    @Test fun systemVpnPredicateMatchesEveryStartPath() {
        assertTrue(Profile("a", "A", "", "vpn", "[Interface]\nKillSwitch = false\n").startsAsSystemVpn)
        assertTrue(Profile("a", "A", "", "vpn", "").startsAsSystemVpn)
        assertFalse(Profile("a", "A", "", "vpn", "[Interface]\n  KillSwitch=true  \n").startsAsSystemVpn)
        assertFalse(Profile("a", "A", "", "proxy", "").startsAsSystemVpn)
    }
    @Test fun restartedRuntimeInvalidatesApplyBeforeCollectorRuns() = scenario {
        ready(); connected(); model.save("new", "a", "", true) {}.join()
        assertEquals("a", model.pendingApply.value)
        runtime.value = runtime.value.copy(connectedAtElapsed = 20)
        model.applyPending { starts.add(it) }; assertTrue(starts.isEmpty()); assertNull(model.pendingApply.value)
    }
    @Test fun cancelledMutationsAndQueuedWorkClearCounters() = scenario {
        ready(); val g = Gate(); saveHook = { g.await() }
        val save = model.save("", "a", "") { done++ }; g.entered.await()
        val queued = model.delete("b") { done++ }; queued.cancelAndJoin()
        assertTrue(model.busy.value); save.cancelAndJoin(); assertFalse(model.busy.value)
        val d = Gate(); deleteHook = { d.await() }
        val delete = model.delete("b") { done++ }; d.entered.await(); delete.cancelAndJoin()
        assertFalse(model.busy.value); assertEquals(0, done); assertNull(model.error.value)
    }
    @Test fun staleDraftSuppressesSaveAndDeleteCallbacks() = scenario {
        ready(); model.editorDraft = Any(); val g = Gate(); saveHook = { g.await() }
        val save = model.save("", "a", "") { done++ }; g.entered.await(); model.editorDraft = Any()
        g.release.complete(Unit); save.join(); val d = Gate(); deleteHook = { d.await() }
        val delete = model.delete("b") { done++ }; d.entered.await(); model.editorDraft = Any()
        d.release.complete(Unit); delete.join(); assertEquals(0, done)
    }
    @Test fun queuedLoadingAndBusyRemainUntilCompletion() = scenario {
        ready(); val g = Gate(); val d = Gate(); loadHook = { g.await() }; saveHook = { d.await() }
        val refresh = model.refresh(); g.entered.await(); val second = model.refresh()
        val save = model.save("", "a", "") {}
        assertTrue(model.loading.value); assertTrue(model.busy.value)
        g.release.complete(Unit); refresh.join(); second.join(); d.entered.await()
        assertFalse(model.loading.value); assertTrue(model.busy.value)
        d.release.complete(Unit); save.join(); assertFalse(model.busy.value)
    }
    @Test fun failedInitialRefreshRetriesSavedSelection() = scenario {
        persisted = "b"; loadHook = { throw IllegalStateException() }
        model.refresh().join(); assertNotNull(model.error.value)
        loadHook = {}; model.refresh().join()
        assertEquals("b", model.selected.value); assertEquals("b", persisted)
    }
}
