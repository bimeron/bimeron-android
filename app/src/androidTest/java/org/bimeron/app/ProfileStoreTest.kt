package org.bimeron.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against the process-wide store on the device's real files directory (contract D5). Store
 * internals that need a directory of their own (revisions, CAS, the sweep) are covered by the JVM tests.
 */
@RunWith(AndroidJUnit4::class)
class ProfileStoreTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun createReadEditDeleteAndRejectExternalFile() = runBlocking {
        val store = sharedStore(app)
        store.deleteFixturesNamed(*FIXTURE_NAMES)
        runThenCleanUp({
            val text = Conf.fromKey(CREATED, "socks5://192.0.2.1:1080")
            val created = store.save(text)
            assertEquals(CREATED, store.load().first.single { it.id == created.id }.name)
            store.save(Conf.rename(text, RENAMED), created.id)
            assertEquals(RENAMED, store.load().first.single { it.id == created.id }.name)
            val before = store.load().first.size
            try { store.save(text.replace("SOCKS5 = socks5://192.0.2.1:1080", "File = missing.key")); fail("Expected failure") }
            catch (_: ProfileError) {}
            assertEquals(before, store.load().first.size)
            store.delete(created.id)
            assertFalse(store.load().first.any { it.id == created.id })
        }) { store.deleteFixturesNamed(*FIXTURE_NAMES) }
    }

    /** A pure-model test: no activity is involved, so the model is not owned by one. */
    @Test fun selectedProfileClearsAfterDeletion() = runBlocking {
        val store = sharedStore(app)
        store.deleteFixturesNamed(*FIXTURE_NAMES)
        runThenCleanUp({
            val profile = store.createFixture(Conf.fromKey(DELETION, "socks5://192.0.2.1:1080"))
            withDetachedModel(app) { model ->
                withTimeout(10_000) { model.profiles.first { items -> items.any { it.id == profile.id } } }
                model.awaitLoaded()
                // A fixture id here is a leftover selection, not one worth restoring.
                val originalSelection = model.selected.value?.takeUnless { it == profile.id }
                runThenCleanUp({
                    withContext(Dispatchers.Main) { model.select(profile.id) }
                    withTimeout(10_000) { model.selected.first { it == profile.id } }
                    val deleted = CompletableDeferred<Unit>()
                    assertNotNull(model.openStoredEditor(profile.id))
                    withContext(Dispatchers.Main) { model.delete(profile.id) { deleted.complete(Unit) } }
                    withTimeout(10_000) { deleted.await() }
                    assertTrue(model.selected.value == null || model.profiles.value.any { it.id == model.selected.value })
                    assertFalse(store.load().first.any { it.id == profile.id })
                }) {
                    if (originalSelection != null && model.profiles.value.any { it.id == originalSelection }) {
                        withContext(Dispatchers.Main) { model.select(originalSelection) }
                        withTimeout(10_000) { model.selected.first { it == originalSelection } }
                    }
                }
            }
        }) { store.deleteFixturesNamed(*FIXTURE_NAMES) }
    }

    private companion object {
        // Non-ASCII names on purpose: the store must round-trip them.
        const val CREATED = "Profile store fixture Première"
        const val RENAMED = "Profile store fixture Deuxième"
        const val DELETION = "Profile store deletion fixture"
        val FIXTURE_NAMES = arrayOf(CREATED, RENAMED, DELETION)
    }
}
