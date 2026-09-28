package app.trackone.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeRolesTest {

    private val theme = ColorResources.styleItems("Theme.FinanceWidget")

    @Test
    fun `every Material colour role is mapped to an app colour`() {
        // An unmapped role falls back to Material 3's baseline purple: the Theme dialog's radios were #CCC2DC.
        val roles = listOf(
            "colorPrimary", "colorOnPrimary", "colorPrimaryContainer", "colorOnPrimaryContainer", "colorPrimaryInverse",
            "colorSecondary", "colorOnSecondary", "colorSecondaryContainer", "colorOnSecondaryContainer",
            "colorTertiary", "colorOnTertiary", "colorTertiaryContainer", "colorOnTertiaryContainer",
            "colorError", "colorOnError", "colorErrorContainer", "colorOnErrorContainer",
            "android:colorBackground", "colorOnBackground",
            "colorSurface", "colorOnSurface", "colorSurfaceVariant", "colorOnSurfaceVariant",
            "colorSurfaceInverse", "colorOnSurfaceInverse",
            "colorSurfaceContainerLowest", "colorSurfaceContainerLow", "colorSurfaceContainer",
            "colorSurfaceContainerHigh", "colorSurfaceContainerHighest",
            "colorOutline", "colorOutlineVariant", "colorControlActivated", "colorControlNormal"
        )
        val missing = roles.filterNot { it in theme }
        assertTrue("Unmapped Material roles: $missing", missing.isEmpty())
        val notAppColours = roles.associateWith { theme.getValue(it) }.filterValues { !it.startsWith("@color/") }
        assertTrue("Roles must point at an app colour: $notAppColours", notAppColours.isEmpty())
    }

    @Test
    fun `accent roles flip with the theme so nothing is black on black`() {
        assertEquals("@color/ink", theme["colorPrimary"])
        assertEquals("@color/on_ink", theme["colorOnPrimary"])
        assertEquals("@color/ink", theme["colorControlActivated"])
    }

    @Test
    fun `the theme leaves each view's own font alone`() {
        // AppCompat's fontFamily on the theme outranks every view's android:fontFamily, so all
        // layout fonts (Geist Mono numbers, semibold labels) rendered as plain Inter.
        assertTrue("Theme sets AppCompat's fontFamily", "fontFamily" !in theme)
        assertEquals("@font/inter", theme["android:fontFamily"])
    }

    @Test
    fun `filled buttons are ink with inverse text`() {
        // Widget.App.Button used the constant black: invisible on the dark background.
        val button = ColorResources.styleItems("Widget.App.Button")
        assertEquals("@color/ink", button["backgroundTint"])
        assertEquals("@color/on_ink", button["android:textColor"])
    }
}
