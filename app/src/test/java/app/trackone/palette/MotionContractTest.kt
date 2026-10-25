package app.trackone.palette

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Motion rules, read from the resources like the palette tests. One duration for anything that shows
 * a selection (the bottom tabs and every chip or underline), one for things that open, close or
 * shift, both in Material's "medium" range (250-400 ms) and sharing one easing. The tabs' own
 * duration comes from the Material library, so the tab bar is pointed at the same token.
 */
class MotionContractTest {

    private val main = ColorResources.mainDir
    private fun res(path: String) = File(main, "res/$path").readText()

    private fun integer(name: String): Int =
        Regex("""<integer name="$name">(\d+)</integer>""").find(res("values/integers.xml"))?.groupValues?.get(1)?.toInt()
            ?: error("integer $name is not defined in res/values/integers.xml")

    @Test
    fun `selection and layout durations sit in Material's medium range`() {
        val selection = integer("motion_duration_selection")
        val layout = integer("motion_duration_layout")
        assertTrue("selection $selection ms is outside 250-400", selection in 250..400)
        assertTrue("layout $layout ms is outside 250-400", layout in 250..400)
        assertTrue("things that open and close should not be quicker than a selection", layout >= selection)
    }

    @Test
    fun `the bottom tabs run their pill on the same duration as the chips`() {
        val themes = res("values/themes.xml")
        val overlay = Regex("""<style name="ThemeOverlay\.App\.BottomNavMotion"[^>]*>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL)
            .find(themes)?.groupValues?.get(1) ?: error("ThemeOverlay.App.BottomNavMotion is missing from themes.xml")
        assertTrue(
            "the overlay must set motionDurationLong2 (the library's nav indicator duration) to the selection token",
            Regex("""name="motionDurationLong2">@integer/motion_duration_selection<""").containsMatchIn(overlay)
        )
        val layout = res("layout/activity_main.xml")
        val nav = Regex("""<com\.google\.android\.material\.bottomnavigation\.BottomNavigationView(.*?)/>""", RegexOption.DOT_MATCHES_ALL)
            .find(layout)?.groupValues?.get(1) ?: error("BottomNavigationView not found in activity_main.xml")
        assertTrue("the tab bar must use the overlay", nav.contains("android:theme=\"@style/ThemeOverlay.App.BottomNavMotion\""))
    }

    @Test
    fun `code takes its durations from the tokens, not from numbers`() {
        val motionSpec = File(main, "java/app/trackone/ui/util/MotionSpec.kt").readText()
        assertTrue("MotionSpec must read the selection token", motionSpec.contains("R.integer.motion_duration_selection"))
        assertTrue("MotionSpec must read the layout token", motionSpec.contains("R.integer.motion_duration_layout"))
        assertTrue("no hard-coded 500 ms left in MotionSpec", !Regex("""\b500\b""").containsMatchIn(motionSpec.replace(Regex("//.*"), "")))
        assertEquals(
            "PillSelector animates selections with the selection duration",
            true, File(main, "java/app/trackone/ui/util/PillSelector.kt").readText().contains("MotionSpec.selectionDuration")
        )
    }
}
