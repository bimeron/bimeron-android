package org.bimeron.app

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an isolated emulator user; never run against a personal VPN session. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 33)
@RunWith(AndroidJUnit4::class)
class EditorLocaleLifecycleTest {
    @Test fun russianEditorLifecycle() = withLocale("ru")
    @Test fun englishEditorLifecycle() = withLocale("en")

    @get:org.junit.Rule val state = LifecycleTestState()

    private fun withLocale(tag: String) = state.withLocale(tag) {
        exerciseEditor(tag, LocaleStrings.get(tag, R.string.profile_editor))
    }
    private fun exerciseEditor(tag: String, expectedTitle: String) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = ProfileStore(app)
        val model = AppModel(app)
        state.phase = "wait: model loaded"
        withTimeout(10_000) { model.loading.first { !it } }
        val previousSelection = model.selected.value
        val profile = store.save(Conf.fromKey("Editor UI fixture", "socks5://192.0.2.10:1080"))
        try {
            model.refresh().join()
            model.select(profile.id).join()
            state.phase = "wait: fixture selected"
            withTimeout(10_000) { model.selected.first { it == profile.id } }
            state.step("launch activity") { ActivityScenario.launch(MainActivity::class.java) }.use { scenario ->
                try {
                    state.scenario = scenario
                    state.awaitActivityLocale(tag, expectedTitle)
                    fun label(id: Int, vararg args: Any): String {
                        var value = ""
                        scenario.onActivity { value = it.getString(id, *args) }
                        return value
                    }
                    click(waitNode("configure profile") { it.contentDescription?.toString() == label(R.string.configure_profile, "Editor UI fixture") })
                    waitNode("editor title") { it.text?.toString() == expectedTitle }
                    val name = waitNode("name field") { it.className?.toString() == "android.widget.EditText" && it.text?.toString()?.contains("Editor UI fixture") == true }
                    assertTrue(name.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Changed draft") }))
                    click(waitNode("network tab") { it.text?.toString() == label(R.string.editor_network) })
                    waitNode("interface field") { it.text?.toString()?.contains(label(R.string.editor_interface_name)) == true }
                    state.step("move to CREATED") { scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED) }
                    state.step("move to RESUMED") { scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED) }
                    state.step("recreate activity") { scenario.recreate() }
                    state.awaitActivityLocale(tag, expectedTitle)
                    waitNode("restored interface field") { it.text?.toString()?.contains(label(R.string.editor_interface_name)) == true }
                    assertEquals("Editor UI fixture", store.load().first.single { it.id == profile.id }.name)
                    click(waitNode("back from network") { it.contentDescription?.toString() == label(R.string.back) })
                    waitNode("discard dialog") { it.text?.toString() == label(R.string.editor_discard_title) }
                    click(waitNode("continue editing") { it.text?.toString() == label(R.string.editor_continue_editing) })
                    click(waitNode("basic tab") { it.text?.toString() == label(R.string.editor_general) })
                    waitNode("restored draft") { it.text?.toString()?.contains("Changed draft") == true }
                    click(waitNode("back from basic") { it.contentDescription?.toString() == label(R.string.back) })
                    click(waitNode("discard changes") { it.text?.toString() == label(R.string.editor_discard) })
                    waitNode("home title") { it.text?.toString() == "Bimeron" }
                    assertEquals("Editor UI fixture", store.load().first.single { it.id == profile.id }.name)
                } catch (failure: Throwable) {
                    state.captureFailure()
                    throw failure
                }
            }
        } finally {
            if (previousSelection != null) {
                model.select(previousSelection)
                state.phase = "wait: selection restored"
                withTimeout(10_000) { model.selected.first { it == previousSelection } }
            }
            model.deleteStoredFixture(store, profile.id)
        }
    }
    private fun waitNode(phase: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        state.phase = "wait: $phase"
        return AppWindowHelper.waitNode(predicate)
    }
    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
}
