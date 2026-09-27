package app.trackone.palette

import app.trackone.data.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Marker palette promises, checked against values/ and values-night/colors.xml.
 * Numbers in comments are what the approved palette measured on 2026-09-28.
 */
class PaletteContractTest {

    private val themes = listOf(
        "light" to ColorResources.palette(night = false),
        "dark" to ColorResources.palette(night = true)
    )
    private val categories = AssetType.values().map { "cat_" + it.name.lowercase() }

    private fun assertContrast(min: Double, fg: String, bg: String) {
        for ((mode, palette) in themes) {
            val ratio = ColorMath.contrast(palette.opaque(fg), palette.opaque(bg))
            assertTrue("$mode: $fg on $bg is ${"%.2f".format(ratio)}:1, needs $min:1", ratio >= min)
        }
    }

    @Test
    fun `gain, loss and danger text is readable on every surface it sits on`() {
        // Lowest: gain on surface_variant in light, 4.85:1.
        for (fg in listOf("gain", "loss", "danger")) {
            for (bg in listOf("background", "surface_color", "surface_variant")) assertContrast(4.5, fg, bg)
        }
        assertContrast(4.5, "danger", "error_background")    // Watchlist "Failed to refresh" banner
    }

    @Test
    fun `ink selection stands out and its label reads in both themes`() {
        assertContrast(4.5, "on_ink", "ink")                 // 19.9 light, 19.1 dark
        assertContrast(3.0, "ink", "background")             // a selected chip against the page
        assertContrast(3.0, "ink", "surface_color")          // an "on" switch track against its card
        assertContrast(4.5, "on_danger", "danger")           // swipe-to-remove label
    }

    @Test
    fun `neutral chips and count pills stay readable`() {
        assertContrast(4.5, "text_secondary", "background")
        assertContrast(4.5, "text_secondary", "surface_variant")   // 7.0 light, 5.8 dark
    }

    @Test
    fun `marker and its ink are identical in both themes`() {
        assertContrast(4.5, "on_marker", "marker")           // 18.3:1
        val (light, dark) = themes.map { it.second }
        assertEquals(light.opaque("marker"), dark.opaque("marker"))
        assertEquals(light.opaque("on_marker"), dark.opaque("on_marker"))
    }

    @Test
    fun `gain and loss stay apart for colour-blind readers`() {
        for ((mode, palette) in themes) {
            val d = ColorMath.colourBlindDeltaE(palette.opaque("gain"), palette.opaque("loss"))
            assertTrue("$mode: gain vs loss is ΔE ${"%.1f".format(d)} with colour blindness, needs 8", d >= 8.0)
        }
    }

    @Test
    fun `Indian and US stocks are distinguishable with colour blindness`() {
        // The bug this palette fixes: blue vs violet was ΔE 1.3 for deuteranopes. Now 13.0 light, 15.9 dark.
        for ((mode, palette) in themes) {
            val d = ColorMath.colourBlindDeltaE(palette.opaque("cat_stock_in"), palette.opaque("cat_stock_us"))
            assertTrue("$mode: Indian vs US stocks is ΔE ${"%.1f".format(d)}, needs 8", d >= 8.0)
        }
    }

    @Test
    fun `neighbouring categories stay distinguishable with colour blindness`() {
        // Neighbours in AssetType order. Lowest: MF vs gold in dark, 6.9. Between 6 and 8 is only
        // acceptable because every bar segment also has a named legend row.
        for ((mode, palette) in themes) {
            categories.zipWithNext().forEach { (a, b) ->
                val d = ColorMath.colourBlindDeltaE(palette.opaque(a), palette.opaque(b))
                assertTrue("$mode: $a vs $b is ΔE ${"%.1f".format(d)} with colour blindness, needs 6", d >= 6.0)
            }
        }
    }

    @Test
    fun `no two categories look alike to anyone`() {
        // Lowest: crypto vs bank in dark, 8.3.
        for ((mode, palette) in themes) {
            for (i in categories.indices) for (j in i + 1 until categories.size) {
                val d = ColorMath.deltaE(palette.opaque(categories[i]), palette.opaque(categories[j]))
                assertTrue("$mode: ${categories[i]} vs ${categories[j]} is ΔE ${"%.1f".format(d)}, needs 8", d >= 8.0)
            }
        }
    }
}
