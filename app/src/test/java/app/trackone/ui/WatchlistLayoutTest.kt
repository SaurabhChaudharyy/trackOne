package app.trackone.ui

import app.trackone.palette.ColorResources
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Two ways the v1.4.5 card layout broke the watchlist, kept from coming back. Read straight from the sources.
 *
 * 1. The list became a `wrap_content` card but kept `setHasFixedSize(true)`. A fixed-size RecyclerView does
 *    not re-measure itself when items arrive, so it stayed one row tall and scrolled inside that window.
 * 2. The card's side margins narrowed the weighted columns of a row until "↗ +3.92%" no longer fit and wrapped
 *    onto two lines, off-centre. The change-percent text has to stay on one line.
 */
class WatchlistLayoutTest {

    private val main = ColorResources.mainDir
    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file).documentElement

    private fun Element.descendants(): List<Element> =
        (0 until getElementsByTagName("*").length).map { getElementsByTagName("*").item(it) as Element }

    private fun String.camel() = split('_').mapIndexed { i, s -> if (i == 0) s else s.replaceFirstChar { it.uppercase() } }.joinToString("")

    @Test
    fun `a wrap_content list is never given a fixed size`() {
        val wrapIds = File(main, "res").walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("layout") }
            .flatMap { layout ->
                parse(layout).descendants()
                    .filter { it.tagName == "androidx.recyclerview.widget.RecyclerView" }
                    .filter { it.getAttributeNS(android, "layout_height") == "wrap_content" }
                    .map { it.getAttributeNS(android, "id").substringAfter("/") }
            }.filter { it.isNotEmpty() }.toList()

        val offenders = File(main, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val text = file.readText().replace(Regex("""//[^\n]*"""), "")   // code only: a comment may name the call
            wrapIds.filter { id ->
                Regex("""\b${id.camel()}\s*\.\s*(?:apply\s*\{[^}]*)?setHasFixedSize\(\s*true\s*\)""").containsMatchIn(text)
            }.map { "${file.relativeTo(main).path}: $it is wrap_content but setHasFixedSize(true)" }
        }.toList()
        assertTrue("A fixed-size wrap_content RecyclerView keeps its first measured height:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `the change percent in a stock row stays on one line`() {
        val row = parse(File(main, "res/layout/item_stock.xml")).descendants()
            .first { it.getAttributeNS(android, "id") == "@+id/tv_change_percent" }
        val oneLine = row.getAttributeNS(android, "singleLine") == "true" || row.getAttributeNS(android, "maxLines") == "1"
        assertTrue("tv_change_percent can wrap onto a second line when the row is narrow", oneLine)
    }
}
