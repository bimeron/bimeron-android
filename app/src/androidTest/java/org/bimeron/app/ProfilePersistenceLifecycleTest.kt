package org.bimeron.app

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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
        // The process-wide store that MainActivity's model also uses (contract D5: single writer).
        val store = sharedStore(app)
        store.deleteFixturesNamed(FIXTURE)
        runThenCleanUp<Unit>({
            val fixture = store.createFixture(Conf.fromKey(FIXTURE, "socks5://192.0.2.10:1080"))
            state.step("launch activity") { ActivityScenario.launch(MainActivity::class.java) }.use { scenario ->
                state.scenario = scenario
                val model = scenario.activityModel()
                state.phase = "wait: model loaded"
                model.awaitLoaded()
                // The initial load may select the fixture when nothing else was selected; that is not worth restoring.
                val previousSelection = model.selected.value?.takeUnless { it == fixture.id }
                runThenCleanUp({
                    try {
                        state.step("move to CREATED") { scenario.moveToState(Lifecycle.State.CREATED) }
                        state.step("move to RESUMED") { scenario.moveToState(Lifecycle.State.RESUMED) }
                        assertEquals(fixture.text, store.load().first.single { it.id == fixture.id }.text)
                        state.step("recreate activity") { scenario.recreate() }
                        assertEquals(fixture.text, store.load().first.single { it.id == fixture.id }.text)
                    } catch (failure: Throwable) {
                        state.captureFailure()
                        throw failure
                    }
                }) {
                    // Recreation keeps the activity's ViewModel, so this is still the live model.
                    if (previousSelection != null && model.profiles.value.any { it.id == previousSelection }) {
                        withContext(Dispatchers.Main) { model.select(previousSelection) }
                        withTimeout(10_000) { model.selected.first { it == previousSelection } }
                    }
                }
            }
        }) { store.deleteFixturesNamed(FIXTURE) }
    }

    private companion object {
        const val FIXTURE = "Persistence lifecycle fixture"
    }
}
