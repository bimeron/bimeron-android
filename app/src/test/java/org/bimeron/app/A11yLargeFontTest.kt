package org.bimeron.app

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Phone-width screens at the largest system font scale must neither clip nor truncate fixed UI text (Russian, the longest locale). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru-w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class A11yLargeFontTest {
    @get:Rule val compose = createComposeRule()

    private val home = Profile("home", "Home", "VLESS", "vpn", "")
    private val work = Profile("work", "Work", "SS", "proxy", "")

    // Resolved through the class-level "ru" qualifier, so fixed UI text comes from values-ru.
    private fun str(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Before fun largeFont() { RuntimeEnvironment.setFontScale(2f) }

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) = compose.setContent {
        MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme) { Surface(Modifier.fillMaxSize()) { content() } }
    }

    private fun assertFits() {
        compose.waitForIdle()
        val rootWidth = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.right
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("No text rendered", texts.isNotEmpty())
        texts.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            val text = layout.layoutInput.text.text
            // didOverflowWidth is unreliable for semantics layouts (paragraph width = max constraint), so check lines directly.
            val ellipsized = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
            // Trailing spaces of wrapped lines may hang past the edge without being drawn.
            val widest = (0 until layout.lineCount)
                .filterNot { text.substring(layout.getLineStart(it), layout.getLineEnd(it)).last().isWhitespace() }
                .maxOfOrNull { layout.multiParagraph.getLineWidth(it) } ?: 0f
            assertFalse("Text is truncated at fontScale 2: \"$text\"", ellipsized || layout.multiParagraph.didExceedMaxLines || layout.didOverflowHeight)
            assertTrue("Text \"$text\" is wider than its box: $widest > ${layout.size.width}", widest <= layout.size.width + 1f)
            assertTrue("Text \"$text\" extends past the window: ${node.boundsInRoot} > $rootWidth", node.boundsInRoot.right <= rootWidth + 1f)
        }
    }

    @Test fun connectionScreenAndBottomNavigation() {
        show {
            Column {
                Box(Modifier.weight(1f)) { MainPage(home, listOf(home, work), VpnState(state = "failed", message = str(R.string.core_error)), true, true, {}, {}, {}, {}, {}) }
                BottomNav(Page.CONNECTION) {}
            }
        }
        assertFits()
    }

    @Config(qualifiers = "ru-w1000dp-h700dp-mdpi")
    @Test fun tabletSideNavigationAndTwoColumns() {
        show(dark = true) {
            Row {
                SideNav(Page.CONNECTION) {}
                Box(Modifier.weight(1f)) { MainPage(home, listOf(home, work), VpnState(), true, false, {}, {}, {}, {}, {}) }
            }
        }
        assertFits()
    }

    @Test fun emptyConnectionScreen() {
        show(dark = true) { MainPage(null, emptyList(), VpnState(), false, false, {}, {}, {}, {}, {}) }
        assertFits()
    }

    @Test fun settingsScreen() {
        show { SettingsPage("system", {}, {}, {}) }
        assertFits()
    }

    @Test fun journalScreen() {
        show(dark = true) { LogJournalPage(listOf(LogEntry("05.11 10:00:00", str(R.string.permission_denied)))) }
        assertFits()
    }
}
