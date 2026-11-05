package org.bimeron.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileStoreTest {
    @Test fun createReadEditDeleteAndRejectExternalFile() = runBlocking {
        val store = ProfileStore(ApplicationProvider.getApplicationContext())
        val text = Conf.fromKey("Première", "socks5://192.0.2.1:1080")
        val created = store.save(text)
        try {
            assertEquals("Première", store.load().first.single { it.id == created.id }.name)
            store.save(Conf.rename(text, "Deuxième"), created.id)
            assertEquals("Deuxième", store.load().first.single { it.id == created.id }.name)
            val before = store.load().first.size
            try { store.save(text.replace("SOCKS5 = socks5://192.0.2.1:1080", "File = missing.key")); fail("Expected failure") }
            catch (_: ProfileError) {}
            assertEquals(before, store.load().first.size)
        } finally { store.delete(created.id) }
        assertFalse(store.load().first.any { it.id == created.id })
    }

    @Test fun selectedProfileClearsAfterDeletion() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = ProfileStore(application)
        val profile = store.save(Conf.fromKey("Selection fixture", "socks5://192.0.2.1:1080"))
        try {
            val model = AppModel(application)
            withTimeout(10_000) { model.profiles.first { items -> items.any { it.id == profile.id } } }
            model.select(profile.id)
            withTimeout(10_000) { model.selected.first { it == profile.id } }
            val deleted = CompletableDeferred<Unit>()
            model.delete(profile.id) { deleted.complete(Unit) }
            withTimeout(10_000) { deleted.await() }
            assertTrue(model.selected.value == null || model.profiles.value.any { it.id == model.selected.value })
            assertFalse(store.load().first.any { it.id == profile.id })
        } finally {
            if (store.load().first.any { it.id == profile.id }) store.delete(profile.id)
        }
    }
}
