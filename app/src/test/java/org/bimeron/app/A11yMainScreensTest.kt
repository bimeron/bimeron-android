package org.bimeron.app

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** TalkBack semantics and touch targets of the main screens, rendered on the JVM with Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class A11yMainScreensTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int, vararg args: Any) = context.getString(id, *args)
    private val home = Profile("home", "Home", "VLESS", "vpn", "")
    private val work = Profile("work", "Work", "SS", "vpn", "")

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        MaterialTheme(colorScheme = lightScheme) { Surface { content() } }
    }
    private fun role(value: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, value)
    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
    private val group = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)

    /** The touch area (layout bounds expanded to the minimum touch target) is at least 48x48 dp. */
    private fun SemanticsNodeInteraction.assertTouchTarget(): SemanticsNodeInteraction {
        val node = fetchSemanticsNode()
        val bounds = node.touchBoundsInRoot
        with(node.layoutInfo.density) {
            val width = bounds.width.toDp()
            val height = bounds.height.toDp()
            assertTrue("Touch target ${width}x$height is smaller than 48dp", width >= 47.5.dp && height >= 47.5.dp)
        }
        return this
    }

    @Test fun powerButtonNamesActionAndCurrentState() {
        show { ConnectionCard(home, VpnState(), {}) }
        compose.onNode(hasContentDescription(str(R.string.connect)) and hasClickAction())
            .assertIsEnabled()
            .assert(role(Role.Button))
            .assert(state(str(R.string.vpn_disconnected)))
            .assertTouchTarget()
        // The caption under the button is visual only; TalkBack gets the label from the button.
        compose.onAllNodesWithText(str(R.string.connect)).assertCountEquals(0)
        compose.onNodeWithText(str(R.string.vpn_disconnected))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun powerButtonOffersDisconnectWhenConnected() {
        show { ConnectionCard(home, VpnState(state = "connected"), {}) }
        compose.onNode(hasContentDescription(str(R.string.disconnect)) and hasClickAction())
            .assert(state(str(R.string.vpn_connected)))
    }

    @Test fun powerButtonIsDisabledWithoutProfileAndHintIsAnnounced() {
        show { ConnectionCard(null, VpnState(), {}) }
        compose.onNode(hasContentDescription(str(R.string.connect)) and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.profiles_empty_hint))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun profileRowsAreSingleChoiceWithSeparateSettingsAction() {
        var selected: String? = null
        var edited: String? = null
        show { Column { CollapsibleProfiles(home, listOf(home, work), true, false, VpnState(), {}, {}, { selected = it }, { edited = it }) } }
        compose.onNode(isHeading() and hasContentDescription(str(R.string.a11y_profiles_heading, 2))).assertExists()
        compose.onAllNodes(hasText("  2")).assertCountEquals(0)
        compose.onNode(group and hasAnyDescendant(hasText(home.name)) and hasAnyDescendant(hasText(work.name))).assertExists()
        compose.onAllNodes(role(Role.RadioButton)).assertCountEquals(2)
        compose.onNode(role(Role.RadioButton) and hasText(home.name) and hasText("VLESS") and isSelected()).assertExists()
        compose.onNode(role(Role.RadioButton) and hasText(work.name) and hasText("Shadowsocks") and isNotSelected())
            .assertTouchTarget().performClick()
        assertEquals(work.id, selected)
        compose.onNodeWithContentDescription(str(R.string.configure_profile, work.name))
            .assert(role(Role.Button))
            .assertTouchTarget()
            .performClick()
        assertEquals(work.id, edited)
        compose.onNode(hasContentDescription(str(R.string.add_profile)) and hasClickAction())
            .assertTouchTarget()
        compose.onNode(hasText(str(R.string.collapse)) and hasClickAction())
            .assert(role(Role.Button))
            .assert(state(str(R.string.a11y_expanded)))
            .assertTouchTarget()
    }

    @Test fun collapsedProfilesExposeCollapsedStateAndLockDuringConnect() {
        show { Column { CollapsibleProfiles(home, listOf(home, work), false, true, VpnState(state = "permission"), {}, {}, {}, {}) } }
        compose.onNode(hasText(str(R.string.expand)) and hasClickAction()).assert(state(str(R.string.a11y_collapsed)))
        compose.onNode(role(Role.RadioButton) and hasText(home.name)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.read_warning))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun bottomNavigationIsATabGroup() {
        show { BottomNav(Page.SETTINGS) {} }
        compose.onNode(group).assertExists()
        compose.onAllNodes(role(Role.Tab)).assertCountEquals(3)
        compose.onNode(role(Role.Tab) and hasText(str(R.string.settings)) and isSelected()).assertExists()
        listOf(R.string.connection, R.string.journal, R.string.settings).forEach {
            compose.onNode(role(Role.Tab) and hasText(str(it))).assertTouchTarget()
        }
    }

    @Test fun sideNavigationIsATabGroup() {
        show { SideNav(Page.JOURNAL) {} }
        compose.onNode(group).assertExists()
        compose.onNode(role(Role.Tab) and hasText(str(R.string.journal)) and isSelected())
            .assertTouchTarget()
        compose.onNodeWithContentDescription(str(R.string.app_name)).assertExists()
    }

    @Test fun themeChoicesAreOneRadioGroupWithoutNestedTargets() {
        var chosen: String? = null
        show { SettingsPage("dark", { chosen = it }, {}, {}) }
        compose.onNode(isHeading() and hasText(str(R.string.settings))).assertExists()
        compose.onNode(isHeading() and hasText(str(R.string.appearance))).assertExists()
        // Three groups: theme (3), log detail (3) and log size (3); the radio circles are not separate stops.
        compose.onAllNodes(role(Role.RadioButton)).assertCountEquals(9)
        compose.onAllNodes(group).assertCountEquals(3)
        compose.onNode(group and hasAnyDescendant(hasText(str(R.string.dark_theme)))).assertExists()
        compose.onAllNodes(role(Role.RadioButton) and hasParent(group and hasAnyDescendant(hasText(str(R.string.dark_theme)))))
            .assertCountEquals(3)
        compose.onNode(group and hasAnyDescendant(hasText(str(R.string.diag_level_info)))).assertExists()
        compose.onNode(group and hasAnyDescendant(hasText(str(R.string.diag_limit_500)))).assertExists()
        compose.onNode(role(Role.RadioButton) and hasText(str(R.string.dark_theme)) and isSelected()).assertExists()
        compose.onNode(role(Role.Switch) and hasText(str(R.string.diag_keep_previous))).assertExists()
        // 3 theme + 6 log radio rows, keep-previous switch, "connection log" and "about".
        compose.onAllNodes(hasClickAction()).assertCountEquals(12)
        compose.onNode(role(Role.RadioButton) and hasText(str(R.string.light_theme)))
            .assertTouchTarget().performClick()
        assertEquals("light", chosen)
    }

    @Test fun journalEntryIsOneStop() {
        show { LogJournalPage(listOf(LogEntry("05.11 10:00:00", str(R.string.vpn_connected)))) }
        compose.onNode(isHeading() and hasText(str(R.string.journal))).assertExists()
        compose.onNode(hasText("05.11 10:00:00") and hasText(str(R.string.vpn_connected))).assertExists()
    }

    @Test fun trafficMetricsReadLabelWithValue() {
        show { TrafficCard(VpnState(received = 2048, sent = 10)) }
        compose.onNode(isHeading() and hasText(str(R.string.session_traffic))).assertExists()
        compose.onNode(hasText(str(R.string.received)) and hasText(str(R.string.unit_kilobytes, 2.0))).assertExists()
        compose.onNode(hasText(str(R.string.sent)) and hasText(str(R.string.unit_bytes, 10L))).assertExists()
    }

    @Test fun mainPageTitleIsHeading() {
        show { MainPage(home, listOf(home), VpnState(), false, false, {}, {}, {}, {}, {}) }
        compose.onNode(isHeading() and hasText("Bimeron")).assertExists()
    }
}
