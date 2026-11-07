package org.bimeron.app

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
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

    @Test fun conflictPanelResolvesPerFieldAndTheNextSaveCommitsTheMerge() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        // The process-wide store that MainActivity's model also uses.
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
                    // Resolved from the current app locale (CI applies "ru").
                    fun s(id: Int, vararg args: Any) = app.getString(id, *args)
                    try {
                        click(waitNode("configure profile") { it.contentDescription?.toString() == s(R.string.configure_profile, FIXTURE) })
                        waitNode("editor title") { it.text?.toString() == s(R.string.profile_editor) }
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

    private companion object {
        const val FIXTURE = "Conflict dialog fixture"
        const val MINE = "Conflict dialog mine"
        const val THEIRS = "Conflict dialog theirs"
        val FIXTURE_NAMES = arrayOf(FIXTURE, MINE, THEIRS)
    }
}
