package app.trackone.palette

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Marker rule: the neon marks only what needs the user. These fail when a retired colour or pill
 * comes back, or when the marker shows up somewhere new. Add a file to [allowed] only after
 * deciding it really is a "look here" job.
 */
class MarkerUsageTest {

    private val main = ColorResources.mainDir

    private val allowed = setOf(
        "res/values/colors.xml",                                 // defines it
        "res/drawable/bg_marker_banner.xml",                     // "Portfolio updated" banner
        "res/layout/fragment_watchlist.xml",                     // add-stock button
        "res/layout/activity_splash.xml",                        // launch mark
        "res/values/themes.xml",                                 // active-tab nav pill
        "res/drawable/bg_marker_add.xml",                        // section-header add square
        "java/app/trackone/ui/networth/NetWorthAssetAdapter.kt"  // data-quality warning highlight
    )

    private fun sourceFiles() = listOf(File(main, "res"), File(main, "java")).flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension in setOf("xml", "kt") }.toList()
    }

    private fun path(file: File) = file.relativeTo(main).invariantSeparatorsPath

    private fun linesMatching(pattern: Regex, skip: (String) -> Boolean = { false }) = sourceFiles()
        .filterNot { skip(path(it)) }
        .flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (pattern.containsMatchIn(line)) "${path(file)}:${i + 1}  ${line.trim()}" else null
            }
        }

    @Test
    fun `retired neon and pill resources are gone`() {
        val retired = Regex("""\b(neon_highlight|accent_blue|gain_green(_bg)?|loss_red(_bg)?|warning_text|bg_gain_pill|bg_loss_pill|bg_pnl_chip|TextAppearance\.App\.(Gain|Loss))\b""")
        val found = linesMatching(retired)
        assertTrue("Still referenced:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun `the neon hex is written only in colors xml`() {
        val found = linesMatching(Regex("(?i)F3FE78")) { it == "res/values/colors.xml" }
        assertTrue("Use @color/marker instead:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun `marker appears only where something needs the user`() {
        val found = linesMatching(Regex("""@color/marker\b|R\.color\.marker\b""")) { it in allowed }
        assertTrue("Marker outside its jobs:\n" + found.joinToString("\n"), found.isEmpty())
    }
}
