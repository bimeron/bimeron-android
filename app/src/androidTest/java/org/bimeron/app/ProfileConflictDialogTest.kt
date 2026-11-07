package org.bimeron.app

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** A save that conflicts with a change made through the store singleton opens the rebase panel. */
@RunWith(AndroidJUnit4::class)
class ProfileConflictDialogTest {
    @get:org.junit.Rule val state = LifecycleTestState()

    @Test fun conflictPanelResolvesPerFieldAndTheNextSaveCommitsTheMerge() = inOpenEditor { store, profile, model, s ->
                // Another writer renames the profile and changes DNS through the singleton store.
                val stored = store.load().first.single { it.id == profile.id }
                val theirs = ConfigDocument.parse(stored.text).set("Profile", "Name", THEIRS).set("Interface", "DNS", "9.9.9.9").render()
                assertTrue(store.compareAndSave(UUID.randomUUID(), UUID.fromString(profile.id), stored.revision, theirs) is CommitResult.Committed)
                val name = waitNode("name field") { it.isEditable && it.text?.toString()?.contains(FIXTURE) == true }
                assertTrue(name.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, MINE)
                }))
                waitNode("edited name") { it.isEditable && it.text?.toString()?.contains(MINE) == true }
                click(waitNode("done") { it.text?.toString() == s(R.string.editor_done) })
                waitNode("conflict panel") { it.text?.toString() == s(R.string.profile_conflict_title) }
                waitNode("auto-merged count") { it.text?.toString() == s(R.string.profile_conflict_auto_merged, 1) }
                waitNode("name conflict") { it.text?.toString() == "Profile · Name" }
                // Compose keeps text on child nodes; the clickable parent carries enabled and checked state.
                val apply = clickable(waitNode("apply") { it.text?.toString() == s(R.string.profile_conflict_apply) })
                assertFalse("apply needs every conflict resolved", apply.isEnabled)
                // Each option is one selectable radio row holding its label and value.
                fun mineOption(node: AccessibilityNodeInfo) = node.text?.toString() == s(R.string.profile_conflict_mine)
                waitNode("mine value") { it.text?.toString() == MINE }
                waitNode("theirs value") { it.text?.toString() == THEIRS }
                val option = radio(waitNode("mine option") { mineOption(it) })
                assertFalse(option.isChecked)
                click(option)
                // A selected radio row is checked (and no longer offers a click action).
                waitNode("selected mine option") { mineOption(it) && radio(it).isChecked }
                waitNode("progress") { it.text?.toString() == s(R.string.profile_conflict_progress, 1, 1) }
                click(waitNode("enabled apply") { it.text?.toString() == s(R.string.profile_conflict_apply) && clickable(it).isEnabled })
                state.phase = "wait: panel closed"
                AppWindowHelper.waitAbsent { it.text?.toString() == s(R.string.profile_conflict_title) }
                // Applying saves nothing: the stored profile is still theirs.
                assertEquals(THEIRS, store.load().first.single { it.id == profile.id }.name)
                click(waitNode("done after merge") { it.text?.toString() == s(R.string.editor_done) })
                state.phase = "wait: merged save"
                withTimeout(10_000) { model.profiles.first { items -> items.any { it.id == profile.id && it.name == MINE } } }
                val saved = ConfigDocument.parse(store.load().first.single { it.id == profile.id }.text)
                assertEquals(MINE, saved.value("Profile", "Name"))
                assertEquals("9.9.9.9", saved.value("Interface", "DNS"))
                waitNode("editor closed") { it.contentDescription?.toString() == s(R.string.configure_profile, MINE) }
    }

    @Test fun deleteAfterAChangeElsewhereConfirmsAndDeletesAnyway() = inOpenEditor { store, profile, model, s ->
        val stored = store.load().first.single { it.id == profile.id }
        val theirs = ConfigDocument.parse(stored.text).set("Profile", "Name", THEIRS).render()
        assertTrue(store.compareAndSave(UUID.randomUUID(), UUID.fromString(profile.id), stored.revision, theirs) is CommitResult.Committed)
        click(scrollTo("delete profile") { it.text?.toString() == s(R.string.editor_delete_profile) })
        waitNode("delete confirmation") { it.text?.toString() == s(R.string.delete_confirm) }
        click(waitNode("confirm delete") { it.text?.toString() == s(R.string.delete) })
        waitNode("changed before delete") { it.text?.toString() == s(R.string.profile_delete_changed_title) }
        assertEquals(THEIRS, store.load().first.single { it.id == profile.id }.name)
        click(waitNode("delete anyway") { it.text?.toString() == s(R.string.profile_delete_anyway) })
        state.phase = "wait: deleted and editor closed"
        withTimeout(10_000) { while (model.editorDraft != null || store.load().first.any { it.id == profile.id }) delay(100) }
        waitNode("home title") { it.text?.toString() == "Bimeron" }
        assertTrue(store.load().first.none { it.id == profile.id })
    }

    /**
     * Creates a fixture, selects it and opens its editor in MainActivity through the store singleton the
     * activity's model uses, then runs [body] with app-locale strings (CI applies "ru"). Cleans up fixtures
     * and the selection.
     */
    private fun inOpenEditor(body: suspend (ProfileStore, Profile, AppModel, AppStrings) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = ProfileStore.get(app)
        store.deleteFixturesNamed(*FIXTURE_NAMES)
        runThenCleanUp<Unit>({
            val profile = store.createFixture(Conf.fromKey(FIXTURE, "socks5://192.0.2.30:1080"))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                state.scenario = scenario
                var activityModel: AppModel? = null
                scenario.onActivity { activityModel = ViewModelProvider(it)[AppModel::class.java] }
                val model = checkNotNull(activityModel)
                state.phase = "wait: fixture loaded"
                withTimeout(10_000) { model.profiles.first { items -> items.any { it.id == profile.id } } }
                withTimeout(10_000) { model.loading.first { !it } }
                val originalSelection = model.selected.value?.takeUnless { it == profile.id }
                runThenCleanUp({
                    withContext(Dispatchers.Main) { model.select(profile.id) }
                    withTimeout(10_000) { model.selected.first { it == profile.id } }
                    val strings = AppStrings(app)
                    try {
                        click(waitNode("configure profile") { it.contentDescription?.toString() == app.getString(R.string.configure_profile, FIXTURE) })
                        waitNode("editor title") { it.text?.toString() == app.getString(R.string.profile_editor) }
                        body(store, profile, model, strings)
                    } catch (failure: Throwable) {
                        state.captureFailure()
                        throw failure
                    }
                }) {
                    runBlocking {
                        if (originalSelection != null) {
                            withContext(Dispatchers.Main) { model.select(originalSelection) }
                            withTimeout(10_000) { model.selected.first { it == originalSelection } }
                        }
                    }
                }
            }
        }) { runBlocking { store.deleteFixturesNamed(*FIXTURE_NAMES) } }
    }

    private fun waitNode(phase: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        state.phase = "wait: $phase"
        return AppWindowHelper.waitNode(predicate)
    }
    /** Scrolls the editor forward until a node matching [predicate] is laid out on screen. */
    private fun scrollTo(phase: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        state.phase = "scroll: $phase"
        repeat(12) {
            var found: AccessibilityNodeInfo? = null
            var page: AccessibilityNodeInfo? = null
            AppWindowHelper.visit { node ->
                if (found == null && predicate(node)) found = node
                if (page == null && node.isScrollable) page = node
                false
            }
            found?.let { return it }
            if (page?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) return waitNode(phase, predicate)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        return waitNode(phase, predicate)
    }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return checkNotNull(target) { "no clickable ancestor" }
    }
    private fun radio(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isCheckable) target = target.parent
        return checkNotNull(target) { "no radio ancestor" }
    }
    private fun click(node: AccessibilityNodeInfo) {
        check(clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    /** Strings from the current app locale. */
    private class AppStrings(private val context: android.content.Context) {
        operator fun invoke(id: Int, vararg args: Any): String = context.getString(id, *args)
    }

    private companion object {
        const val FIXTURE = "Conflict dialog fixture"
        const val MINE = "Conflict dialog mine"
        const val THEIRS = "Conflict dialog theirs"
        val FIXTURE_NAMES = arrayOf(FIXTURE, MINE, THEIRS)
    }
}
