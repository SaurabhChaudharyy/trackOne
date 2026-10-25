package app.trackone.ui

import app.trackone.palette.ColorResources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SplashActivity finishes itself after starting MainActivity, so tapping the launcher icon on an app
 * that is already running starts a brand-new Splash and MainActivity on top of the old ones (the task
 * grew to three), and each new MainActivity loads Home again. A launcher start that is not the root of
 * its task has to end at once, before it inflates anything or starts a prefetch. Read straight from the source.
 */
class SplashRelaunchTest {

    private val source = File(ColorResources.mainDir, "java/app/trackone/ui/splash/SplashActivity.kt").readText()

    @Test
    fun `a launcher start that is not the task root finishes before doing any work`() {
        val onCreate = source.substringAfter("override fun onCreate").substringBefore("private fun startAnimations")
        val guard = onCreate.indexOf("!isTaskRoot")
        assertTrue("SplashActivity.onCreate has no isTaskRoot guard", guard >= 0)

        val finish = onCreate.indexOf("finish()", guard)
        assertTrue("The isTaskRoot guard does not finish the activity", finish >= 0)

        val firstWork = listOf("setContentView", "startAnimations()", "awaitReadyAndNavigate()")
            .map { onCreate.indexOf(it) }.filter { it >= 0 }.min()
        assertTrue("The isTaskRoot guard must come before the layout, animations and prefetch", guard < firstWork)
    }

    @Test
    fun `the guard only applies to a plain launcher start`() {
        val guardLine = source.lineSequence().first { "!isTaskRoot" in it }
        assertTrue(
            "Without the MAIN/LAUNCHER check, a share or deep-link start would be dropped:\n$guardLine",
            "CATEGORY_LAUNCHER" in guardLine && "ACTION_MAIN" in guardLine
        )
    }
}
