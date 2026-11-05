package org.bimeron.app

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2.1 contrast of the text and icon colors used on the main screens, in both themes. */
class A11yContrastTest {
    private val blue = Color(0xFF287BFF)
    private val themes = listOf("light" to lightScheme, "dark" to darkScheme)

    private fun ratio(foreground: Color, background: Color): Double {
        val a = foreground.compositeOver(background).luminance() + .05
        val b = background.luminance() + .05
        return maxOf(a, b).toDouble() / minOf(a, b)
    }

    private fun assertContrast(name: String, foreground: Color, background: Color, minimum: Double) {
        val value = ratio(foreground, background)
        assertTrue("$name: %.2f < %.1f".format(value, minimum), value >= minimum)
    }

    private fun ColorScheme.subtle() = if (this === darkScheme) subtleTextDark else subtleTextLight

    @Test fun bodyAndSecondaryTextMeetAa() {
        for ((theme, scheme) in themes) {
            for ((surfaceName, surface) in listOf("background" to scheme.background, "surface" to scheme.surface)) {
                val where = "$theme/$surfaceName"
                assertContrast("$where onSurface", scheme.onSurface, surface, 4.5)
                // Section titles and page subtitles.
                assertContrast("$where muted", scheme.onSurface.copy(alpha = MUTED_TEXT_ALPHA), surface, 4.5)
                // Hints, journal timestamps and traffic labels.
                assertContrast("$where hint", scheme.onSurface.copy(alpha = .6f), surface, 4.5)
                // Navigation labels and the power button caption.
                assertContrast("$where caption", scheme.onSurface.copy(alpha = .65f), surface, 4.5)
                assertContrast("$where primary", scheme.primary, surface, 4.5)
                assertContrast("$where error", scheme.error, surface, 4.5)
                assertContrast("$where profile subtitle", scheme.subtle(), surface, 4.5)
            }
            // Selected profile row.
            assertContrast("$theme/selected row name", scheme.onSurface, scheme.secondaryContainer, 4.5)
            assertContrast("$theme/selected row subtitle", scheme.subtle(), scheme.secondaryContainer, 4.5)
            assertContrast("$theme/selected row action", scheme.primary, scheme.secondaryContainer, 4.5)
        }
    }

    @Test fun interactiveIconsMeetNonTextContrast() {
        for ((theme, scheme) in themes) {
            assertContrast("$theme power icon", blue, scheme.surface, 3.0)
            assertContrast("$theme active bottom tab", blue, scheme.surface, 3.0)
            assertContrast("$theme profile settings icon", scheme.subtle(), scheme.surface, 3.0)
        }
        assertContrast("connected power icon", Color.White, blue, 3.0)
    }
}
