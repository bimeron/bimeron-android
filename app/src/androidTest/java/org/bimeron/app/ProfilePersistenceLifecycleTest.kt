package org.bimeron.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Run only in a verified isolated emulator user, with no running VPN. */
@RunWith(AndroidJUnit4::class)
class ProfilePersistenceLifecycleTest {
    @get:org.junit.Rule val state = LifecycleTestState()
    @Test fun backgroundResumeAndRecreatePreservePersistedProfile() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = ProfileStore(app)
        val model = AppModel(app)
        state.phase = "wait: model loaded"
        withTimeout(10_000) { model.loading.first { !it } }
        val previousSelection = model.selected.value
        val fixture = store.save(Conf.fromKey("Lifecycle fixture", "socks5://192.0.2.10:1080"))
        try {
            state.step("launch activity") { ActivityScenario.launch(MainActivity::class.java) }.use { scenario ->
                try {
                    state.scenario = scenario
                    state.step("move to CREATED") { scenario.moveToState(Lifecycle.State.CREATED) }
                    state.step("move to RESUMED") { scenario.moveToState(Lifecycle.State.RESUMED) }
                    assertEquals(fixture.text, store.load().first.single { it.id == fixture.id }.text)
                    state.step("recreate activity") { scenario.recreate() }
                    assertEquals(fixture.text, store.load().first.single { it.id == fixture.id }.text)
                } catch (failure: Throwable) {
                    state.captureFailure()
                    throw failure
                }
            }
        } finally {
            if (previousSelection != null) model.select(previousSelection).join()
            if (model.openStoredEditor(fixture.id) != null) model.delete(fixture.id) {}.join()
        }
    }
}
