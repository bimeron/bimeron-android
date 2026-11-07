package org.bimeron.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pure-model tests: no activity is involved, so the models here are not owned by one (see
 * [withDetachedModel]); they use the process-wide store like every production model.
 */
@RunWith(AndroidJUnit4::class)
class ProfileSelectionTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun selectionPersistsWhileExpansionIsOnlyForCurrentRun() = runBlocking {
        val store = sharedStore(app)
        store.deleteFixturesNamed(*FIXTURE_NAMES)
        runThenCleanUp<Unit>({
            store.createFixture(Conf.fromKey(SELECTION_ONE, "socks5://192.0.2.1:1080"))
            val second = store.createFixture(Conf.fromKey(SELECTION_TWO, "socks5://192.0.2.2:1080"))
            withDetachedModel(app) { model ->
                withTimeout(10_000) { model.profiles.first { it.any { profile -> profile.id == second.id } } }
                // The initial load resolves the saved selection; restore that selection afterwards.
                model.awaitLoaded()
                val originalSelection = originalSelection(model)
                runThenCleanUp({
                    withContext(Dispatchers.Main) {
                        model.expanded.value = true
                        model.requestSelect(second.id)
                    }
                    withTimeout(10_000) { model.selected.first { it == second.id } }
                    assertTrue(model.expanded.value)
                    withContext(Dispatchers.Main) { model.requestSelect(second.id) }
                    assertTrue(model.expanded.value)
                    withDetachedModel(app) { reopened ->
                        withTimeout(10_000) { reopened.selected.first { it == second.id } }
                        assertFalse(reopened.expanded.value)
                        assertEquals(second.id, reopened.selected.value)
                    }
                }) { restoreSelection(model, originalSelection) }
            }
        }) { store.deleteFixturesNamed(*FIXTURE_NAMES) }
    }

    @Test fun activeSwitchWaitsForConfirmationAndCancelKeepsSession() = runBlocking {
        val store = sharedStore(app)
        store.deleteFixturesNamed(*FIXTURE_NAMES)
        runThenCleanUp<Unit>({
            val first = store.createFixture(Conf.fromKey(ACTIVE_ONE, "socks5://192.0.2.3:1080"))
            val second = store.createFixture(Conf.fromKey(ACTIVE_TWO, "socks5://192.0.2.4:1080"))
            withDetachedModel(app) { model ->
                withTimeout(10_000) { model.profiles.first { it.any { profile -> profile.id == second.id } } }
                model.awaitLoaded()
                val originalSelection = originalSelection(model)
                runThenCleanUp({
                    withContext(Dispatchers.Main) { model.select(first.id) }
                    withTimeout(10_000) { model.selected.first { it == first.id } }
                    withContext(Dispatchers.Main) {
                        model.expanded.value = true
                        VpnLive.state.value = VpnState("connected", activeProfileId = first.id)
                        model.requestSelect(second.id)
                    }
                    assertEquals(second.id, model.pendingSwitch.value)
                    assertEquals(first.id, model.selected.value)
                    withContext(Dispatchers.Main) { model.cancelSwitch() }
                    assertNull(model.pendingSwitch.value)
                    assertEquals(first.id, VpnLive.state.value.activeProfileId)
                    assertTrue(model.expanded.value)
                    val started = CompletableDeferred<String>()
                    withContext(Dispatchers.Main) {
                        model.requestSelect(second.id)
                        model.confirmSwitch { started.complete(it) }
                    }
                    withTimeout(10_000) { model.selected.first { it == second.id } }
                    assertEquals(second.id, withTimeout(10_000) { started.await() })
                    assertTrue(model.expanded.value)
                }) {
                    VpnLive.state.value = VpnState()
                    restoreSelection(model, originalSelection)
                }
            }
        }) { store.deleteFixturesNamed(*FIXTURE_NAMES) }
    }

    /** The selection to restore; a fixture id here is a leftover selection, not one worth restoring. */
    private suspend fun originalSelection(model: AppModel): String? {
        val fixtures = sharedStore(app).load().first.filter { it.name in FIXTURE_NAMES }.map { it.id }
        return model.selected.value?.takeUnless { it in fixtures }
    }

    private suspend fun restoreSelection(model: AppModel, original: String?) {
        if (original == null || model.profiles.value.none { it.id == original }) return
        withContext(Dispatchers.Main) { model.select(original) }
        withTimeout(10_000) { model.selected.first { it == original } }
    }

    private companion object {
        const val SELECTION_ONE = "Profile selection fixture one"
        const val SELECTION_TWO = "Profile selection fixture two"
        const val ACTIVE_ONE = "Profile switch fixture one"
        const val ACTIVE_TWO = "Profile switch fixture two"
        val FIXTURE_NAMES = arrayOf(SELECTION_ONE, SELECTION_TWO, ACTIVE_ONE, ACTIVE_TWO)
    }
}
