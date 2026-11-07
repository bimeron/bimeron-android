package org.bimeron.app

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The SSConf refresh button explains why a dirty draft disables it, visibly and to accessibility services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SSConfRefreshHintTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val draft = EditorDraft(Conf.parse(Conf.fromKey("Subscription", "ssconf://example.com/config")).copy(id = "test"))
    private var running by mutableStateOf(false)
    private var clicks = 0

    private val action get() = context.getString(R.string.ssconf_refresh_action)
    private val saveFirst get() = context.getString(R.string.ssconf_refresh_save_first)
    private val defaultHint get() = context.getString(R.string.ssconf_refresh_hint)
    private val button get() = compose.onNode(hasClickAction())

    private fun show() = compose.setContent {
        MaterialTheme {
            SSConfRefreshControls(running = running, dirty = draft.dirty, enabled = true, finished = null,
                onRefresh = { clicks++ })
        }
    }

    private fun stateDescription() = button.fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }

    @Test fun dirtyDraftDisablesButtonAndShowsReason() {
        show()
        compose.onNodeWithText(saveFirst).assertDoesNotExist()
        compose.onNodeWithText(defaultHint).assertIsDisplayed()
        button.assertIsEnabled()

        draft.set("Profile", "Name", "Renamed")
        compose.waitForIdle()
        assertTrue(draft.dirty)
        compose.onNodeWithText(saveFirst).assertIsDisplayed()
        compose.onNodeWithText(defaultHint).assertDoesNotExist()
        button.assertIsNotEnabled()
        button.performClick()
        assertEquals(0, clicks)

        draft.undo()
        compose.waitForIdle()
        assertFalse(draft.dirty)
        compose.onNodeWithText(saveFirst).assertDoesNotExist()
        compose.onNodeWithText(defaultHint).assertIsDisplayed()
        button.assertIsEnabled()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun reasonIsAnnouncedOnceWithTheDisabledButton() {
        show()
        assertEquals(null, stateDescription())

        draft.set("Profile", "Name", "Renamed")
        compose.waitForIdle()
        // The disabled button carries the reason; the visible text is hidden from TalkBack so it is not read twice.
        button.assertIsNotEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, saveFirst))
        compose.onNodeWithText(saveFirst).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.InvisibleToUser))
        compose.onNodeWithText(action).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, saveFirst))
    }

    @Test fun runningRefreshOnCleanDraftHasNoDirtyReason() {
        running = true
        show()
        button.assertIsNotEnabled()
        compose.onNodeWithText(saveFirst).assertDoesNotExist()
        compose.onNodeWithText(defaultHint).assertIsDisplayed()
        assertEquals(null, stateDescription())
    }
}
