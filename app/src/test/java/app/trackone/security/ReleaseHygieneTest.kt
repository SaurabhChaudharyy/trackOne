package app.trackone.security

import app.trackone.palette.ColorResources
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Release hygiene that is easy to break and hard to see: what the app writes to the log, and which
 * permissions it asks Play to review. Read straight from the sources, like the palette tests.
 */
class ReleaseHygieneTest {

    private val main = ColorResources.mainDir

    private fun kotlinSources() = File(main, "java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Every `Log.x(...)` call in [text], whole call text including continuation lines. */
    private fun logCalls(text: String): List<Pair<Int, String>> {
        val calls = mutableListOf<Pair<Int, String>>()
        Regex("""\bLog\.[vdiwe]\(""").findAll(text).forEach { m ->
            var depth = 1
            var i = m.range.last + 1
            while (i < text.length && depth > 0) {
                when (text[i]) { '(' -> depth++; ')' -> depth-- }
                i++
            }
            calls += text.substring(0, m.range.first).count { it == '\n' } + 1 to text.substring(m.range.first, i)
        }
        return calls
    }

    @Test
    fun `no log line carries an email address or what someone holds`() {
        // Release builds keep these lines, and anyone with adb can read them. Counts and outcomes are fine.
        val sensitive = Regex("""(?i)\bemail\b|\.email\b|oldName|newName|holding\.symbol|resolution\.symbol|\.name\b""")
        val found = kotlinSources().flatMap { file ->
            logCalls(file.readText()).filter { (_, call) -> sensitive.containsMatchIn(call) }
                .map { (line, call) -> "${file.relativeTo(main).path}:$line  ${call.replace(Regex("\\s+"), " ").take(110)}" }
        }
        assertTrue("Log lines with personal or portfolio data:\n" + found.joinToString("\n"), found.isEmpty())
    }

    @Test
    fun `the foreground service permission is not shipped unless something runs a foreground service`() {
        // WorkManager's own manifest adds FOREGROUND_SERVICE, so leaving it out of ours is not enough:
        // the app manifest has to remove it explicitly (tools:node="remove") when nothing needs it.
        val usesForeground = kotlinSources().any {
            Regex("""startForeground|setForeground|setExpedited|foregroundServiceType""").containsMatchIn(it.readText())
        }
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(main, "AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val tools = "http://schemas.android.com/tools"
        val permissions = doc.getElementsByTagName("uses-permission")
        var removed = false
        var declared = false
        for (i in 0 until permissions.length) {
            val p = permissions.item(i) as org.w3c.dom.Element
            if (p.getAttributeNS(android, "name") != "android.permission.FOREGROUND_SERVICE") continue
            if (p.getAttributeNS(tools, "node") == "remove") removed = true else declared = true
        }
        assertTrue("FOREGROUND_SERVICE is declared by the app but nothing uses a foreground service", !declared || usesForeground)
        assertTrue(
            "Nothing runs a foreground service, so the permission WorkManager adds must be removed with tools:node=\"remove\"",
            usesForeground || removed
        )
    }

    @Test
    fun `an exported receiver only answers the system's own broadcasts`() {
        // The widget provider has to be exported for the launcher to update it, which also lets any app
        // send it any action it lists. Its own actions (refresh, open a stock) belong on a receiver
        // that is not exported.
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(main, "AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val receivers = doc.getElementsByTagName("receiver")
        val offenders = mutableListOf<String>()
        for (i in 0 until receivers.length) {
            val receiver = receivers.item(i) as org.w3c.dom.Element
            if (receiver.getAttributeNS(android, "exported") != "true") continue
            val actions = receiver.getElementsByTagName("action")
            for (j in 0 until actions.length) {
                val name = (actions.item(j) as org.w3c.dom.Element).getAttributeNS(android, "name")
                if (!name.startsWith("android.")) offenders += "${receiver.getAttributeNS(android, "name")} answers $name"
            }
        }
        assertTrue("Exported receivers answering app-specific actions:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
