package org.bimeron.app

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfileEditorTest {
    @get:org.junit.Rule val state = LifecycleTestState()
    @Test fun deployedCoreValidatesOfflineAndFailedSaveKeepsOriginal() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = ProfileStore(app)
        val original = Conf.fromKey("Editor contract", "https://192.0.2.10:443")
        val profile = store.save(original)
        try {
            store.validate(Conf.fromKey("Dynamic", "ssconf://example.invalid/key"))
            val disabled = ConfigDocument.parse(original).addRule().enableRule(0, false).render()
            store.save(disabled, profile.id)
            assertEquals(disabled, store.load().first.single { it.id == profile.id }.text)
            val invalid = disabled.replace("#| Action = proxy", "#| Action = proxy\n#| Unsupported = secret-value")
            try { store.save(invalid, profile.id); fail("Expected core rejection") }
            catch (e: ProfileError) { assertFalse(e.message.orEmpty().contains("secret-value")) }
            assertEquals(disabled, store.load().first.single { it.id == profile.id }.text)
            assertFalse(File(app.filesDir, "profiles").listFiles().orEmpty().any { it.extension == "tmp" })
        } finally { store.delete(profile.id) }
    }

    /** Saves go through an editor draft opened at the stored revision and edited to [text]. */
    private suspend fun editStored(model: AppModel, id: String, text: String) {
        val draft = checkNotNull(model.openStoredEditor(id)) { "profile $id is not stored" }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            draft.update(androidx.compose.ui.text.input.TextFieldValue(text))
        }
    }

    @Test fun activeSaveOffersReconnectOnlyForConfigurationChanges() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = ProfileStore(app)
        val profile = store.save(Conf.fromKey("Active editor fixture", "socks5://192.0.2.10:1080"))
        val model = AppModel(app)
        state.phase = "wait: model loaded"
        withTimeout(10_000) { model.loading.first { !it } }
        try {
            VpnLive.state.value = VpnState("connected", activeProfileId = profile.id)
            val renamed = ConfigDocument.parse(profile.text).set("Profile", "Name", "Renamed fixture")
            val renameDone = CompletableDeferred<Unit>()
            editStored(model, profile.id, renamed.render())
            model.save(renamed.render(), profile.id, "Renamed", false) { renameDone.complete(Unit) }
            withTimeout(10_000) { renameDone.await() }
            assertNull(model.pendingApply.value)
            assertEquals("connected", VpnLive.state.value.state)
            val changed = renamed.set("Interface", "MTU", "1400")
            val changeDone = CompletableDeferred<Unit>()
            // A successful save does not advance the draft's base; editing again starts from a reopened draft.
            editStored(model, profile.id, changed.render())
            model.save(changed.render(), profile.id, "Changed", true) { changeDone.complete(Unit) }
            withTimeout(10_000) { changeDone.await() }
            assertEquals(profile.id, model.pendingApply.value)
            model.clearPendingApply()
            assertEquals("connected", VpnLive.state.value.state)
            assertEquals(profile.id, VpnLive.state.value.activeProfileId)
            assertTrue(store.load().first.single { it.id == profile.id }.text.contains("MTU = 1400"))
        } finally {
            VpnLive.state.value = VpnState()
            store.delete(profile.id)
        }
    }

    @Test fun draftSurvivesRecreationAndBackConfirmsDiscard() = state.withLocale("ru") { runBlocking {
        val russian = LocaleStrings.context("ru")
        fun ru(id: Int, vararg args: Any) = russian.getString(id, *args)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = ProfileStore(app)
        val profile = store.save(Conf.fromKey("Editor UI fixture", "socks5://192.0.2.10:1080"))
        val model = AppModel(app)
        state.phase = "wait: model loaded"
        withTimeout(10_000) { model.loading.first { !it } }
        val previousSelection = model.selected.value
        model.select(profile.id)
        state.phase = "wait: fixture selected"
        withTimeout(10_000) { model.selected.first { it == profile.id } }
        try {
            state.step("launch activity") { ActivityScenario.launch(MainActivity::class.java) }.use { scenario ->
                try {
                    state.scenario = scenario
                    state.awaitActivityLocale("ru", ru(R.string.profile_editor))
                    click(waitNode("configure profile") { it.contentDescription?.toString() == ru(R.string.configure_profile, "Editor UI fixture") })
                    waitNode("editor title") { it.text?.toString() == ru(R.string.profile_editor) }
                    val name = waitNode("name field") { it.className?.toString() == "android.widget.EditText" && it.text?.toString()?.contains("Editor UI fixture") == true }
                    assertTrue(name.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Changed draft") }))
                    waitNode("changed editable name") { editableName(it, "Changed draft") }
                    var capturedDraft: EditorDraft? = null
                    var expectedTab = 1
                    fun draftStatus(): String {
                        var status = "sameDraft=false nameMatches=false dirty=false tab=false"
                        scenario.onActivity { activity ->
                            val draft = ViewModelProvider(activity)[AppModel::class.java].editorDraft
                            status = "sameDraft=${draft != null && draft === capturedDraft} " +
                                "nameMatches=${draft?.document?.value("Profile", "Name") == "Changed draft"} " +
                                "dirty=${draft?.dirty == true} tab=${draft?.tab == expectedTab}"
                        }
                        return status
                    }
                    state.editorDiagnostics = ::draftStatus
                    click(waitNode("network tab") { it.text?.toString() == ru(R.string.editor_network) })
                    waitNode("interface field") { it.text?.toString()?.contains(ru(R.string.editor_interface_name)) == true }
                    state.step("verify draft before recreation") {
                        scenario.onActivity { activity ->
                            capturedDraft = ViewModelProvider(activity)[AppModel::class.java].editorDraft
                        }
                        assertEquals("sameDraft=true nameMatches=true dirty=true tab=true", draftStatus())
                    }
                    state.step("recreate activity") { scenario.recreate() }
                    state.awaitActivityLocale("ru", ru(R.string.profile_editor))
                    state.step("verify draft after recreation") {
                        assertEquals("sameDraft=true nameMatches=true dirty=true tab=true", draftStatus())
                    }
                    waitNode("restored interface field") { it.text?.toString()?.contains(ru(R.string.editor_interface_name)) == true }
                    assertEquals("Editor UI fixture", store.load().first.single { it.id == profile.id }.name)
                    click(waitNode("back from network") { it.contentDescription?.toString() == ru(R.string.back) })
                    waitNode("discard dialog") { it.text?.toString() == ru(R.string.editor_discard_title) }
                    click(waitNode("continue editing") { it.text?.toString() == ru(R.string.editor_continue_editing) })
                    waitNode("continue dismissed dialog and editor title") { node ->
                        node.text?.toString() == ru(R.string.profile_editor) &&
                            AppWindowHelper.isAbsent {
                                it.text?.toString() == ru(R.string.editor_discard_title)
                            }
                    }
                    expectedTab = 0
                    click(waitNode("basic tab") { it.text?.toString() == ru(R.string.editor_general) })
                    waitNode("selected basic tab") { node ->
                        node.text?.toString() == ru(R.string.editor_general) && (selectedAncestor(node) || run {
                            var basic = false
                            scenario.onActivity { activity ->
                                basic = ViewModelProvider(activity)[AppModel::class.java].editorDraft?.tab == 0
                            }
                            basic
                        })
                    }
                    waitNode("accessible basic content") { it.isVisibleToUser && it.text?.toString() == ru(R.string.editor_key_source) }
                    waitNode("restored draft") { editableName(it, "Changed draft") }
                    click(waitNode("back from basic") { it.contentDescription?.toString() == ru(R.string.back) })
                    click(waitNode("discard changes") { it.text?.toString() == ru(R.string.editor_discard) })
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
            store.delete(profile.id)
        }
    } }
    private fun editableName(node: AccessibilityNodeInfo, expected: String) =
        node.isEditable && node.className?.toString() == "android.widget.EditText" &&
            node.text?.toString()?.contains(expected) == true

    private fun selectedAncestor(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isSelected) return true
            target = target.parent
        }
        return false
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
