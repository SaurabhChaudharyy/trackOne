package app.trackone.ui

import app.trackone.palette.ColorResources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A fragment's `binding` getter is `_binding!!`, and `_binding` is nulled in onDestroyView. A lambda handed to
 * `View.post` runs later, on the main looper, possibly after the view is gone, so reading `binding` inside it
 * is a NullPointerException waiting for the wrong frame (v1.4.5 crashed this way in WatchlistFragment).
 * Posted lambdas capture the view or use `_binding?`. Read straight from the sources, like the palette tests.
 */
class PostedBindingTest {

    private val main = ColorResources.mainDir

    private fun kotlinSources() = File(main, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** The text inside each `.post { … }` / `.postDelayed({ … }, n)` / `.postOnAnimation { … }` lambda, with its line. */
    private fun postedLambdas(text: String): List<Pair<Int, String>> =
        Regex("""\.(?:post|postDelayed|postOnAnimation)\s*\(?\s*\{""").findAll(text).map { m ->
            var depth = 1
            var i = m.range.last + 1
            while (i < text.length && depth > 0) {
                when (text[i]) { '{' -> depth++; '}' -> depth-- }
                i++
            }
            text.substring(0, m.range.first).count { it == '\n' } + 1 to text.substring(m.range.last + 1, i)
        }.toList()

    @Test
    fun `a posted lambda never reads the fragment binding`() {
        // `\bbinding` does not match `_binding`, so the nullable backing field stays allowed.
        val getter = Regex("""\bbinding\b""")
        val found = kotlinSources().flatMap { file ->
            postedLambdas(file.readText()).filter { (_, body) -> getter.containsMatchIn(body) }
                .map { (line, body) -> "${file.relativeTo(main).path}:$line  ${body.replace(Regex("\\s+"), " ").trim().take(100)}" }
        }
        assertTrue("Posted lambdas that read the binding getter (null after onDestroyView):\n" + found.joinToString("\n"), found.isEmpty())
    }
}
