package org.bimeron.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileSelectionTest {
    @Test fun selectionPersistsWhileExpansionIsOnlyForCurrentRun() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = ProfileStore(app)
        val first = store.save(Conf.fromKey("Selection one", "socks5://192.0.2.1:1080"))
        val second = store.save(Conf.fromKey("Selection two", "socks5://192.0.2.2:1080"))
        try {
            val model = AppModel(app)
            withTimeout(10_000) { model.profiles.first { it.any { profile -> profile.id == second.id } } }
            model.expanded.value = true
            model.requestSelect(second.id)
            withTimeout(10_000) { model.selected.first { it == second.id } }
            assertTrue(model.expanded.value)
            model.requestSelect(second.id)
            assertTrue(model.expanded.value)
            val reopened = AppModel(app)
            withTimeout(10_000) { reopened.selected.first { it == second.id } }
            assertFalse(reopened.expanded.value)
            assertEquals(second.id, reopened.selected.value)
        } finally {
            store.delete(first.id)
            store.delete(second.id)
        }
    }

    @Test fun activeSwitchWaitsForConfirmationAndCancelKeepsSession() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = ProfileStore(app)
        val first = store.save(Conf.fromKey("Active one", "socks5://192.0.2.3:1080"))
        val second = store.save(Conf.fromKey("Active two", "socks5://192.0.2.4:1080"))
        try {
            val model = AppModel(app)
            withTimeout(10_000) { model.profiles.first { it.any { profile -> profile.id == second.id } } }
            model.select(first.id)
            withTimeout(10_000) { model.selected.first { it == first.id } }
            model.expanded.value = true
            VpnLive.state.value = VpnState("connected", activeProfileId = first.id)
            model.requestSelect(second.id)
            assertEquals(second.id, model.pendingSwitch.value)
            assertEquals(first.id, model.selected.value)
            model.cancelSwitch()
            assertNull(model.pendingSwitch.value)
            assertEquals(first.id, VpnLive.state.value.activeProfileId)
            assertTrue(model.expanded.value)
            val started = CompletableDeferred<String>()
            model.requestSelect(second.id)
            model.confirmSwitch { started.complete(it) }
            withTimeout(10_000) { model.selected.first { it == second.id } }
            assertEquals(second.id, withTimeout(10_000) { started.await() })
            assertTrue(model.expanded.value)
        } finally {
            VpnLive.state.value = VpnState()
            store.delete(first.id)
            store.delete(second.id)
        }
    }
}
