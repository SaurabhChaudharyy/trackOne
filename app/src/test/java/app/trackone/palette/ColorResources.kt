package app.trackone.palette

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads colour and theme resources straight from src/main/res, so palette rules can be checked
 * in plain JVM tests (this project has no Robolectric).
 */
object ColorResources {

    /** `app/src/main`, found from wherever the test runs (Gradle runs unit tests in the module dir). */
    val mainDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(File(it, "src/main"), File(it, "app/src/main")) }
        .firstOrNull { File(it, "res/values/colors.xml").isFile }
        ?: error("No src/main/res/values/colors.xml above ${File("").absolutePath}")

    /** The colours a light or dark screen actually resolves: values/, overlaid by values-night/. */
    fun palette(night: Boolean): Palette {
        val day = colors(File(mainDir, "res/values/colors.xml"))
        return Palette(if (night) day + colors(File(mainDir, "res/values-night/colors.xml")) else day)
    }

    /** The `<item>`s of one `<style>` in values/themes.xml, by item name. */
    fun styleItems(style: String): Map<String, String> {
        val styles = parse(File(mainDir, "res/values/themes.xml")).getElementsByTagName("style")
        val match = (0 until styles.length).map { styles.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == style }
            ?: throw AssertionError("No <style name=\"$style\"> in values/themes.xml")
        val items = match.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private fun colors(file: File): Map<String, String> {
        val nodes = parse(file).getElementsByTagName("color")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
}

/** One theme's colours, with `@color/` aliases resolved the way Android resolves them. */
class Palette(private val raw: Map<String, String>) {

    /** `#RRGGBB`; fails on an undefined, dangling or translucent colour. */
    fun opaque(name: String): String {
        var value = raw[name] ?: throw AssertionError("colour '$name' is not defined")
        var hops = 0
        while (value.startsWith("@color/")) {
            value = raw[value.removePrefix("@color/")] ?: throw AssertionError("'$name' points at undefined $value")
            if (++hops > 10) throw AssertionError("alias loop at '$name'")
        }
        val hex = value.removePrefix("#").uppercase()
        return when {
            hex.length == 6 -> "#$hex"
            hex.length == 8 && hex.startsWith("FF") -> "#${hex.substring(2)}"
            else -> throw AssertionError("'$name' is $value; contrast needs an opaque #RRGGBB colour")
        }
    }
}
