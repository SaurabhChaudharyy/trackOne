package app.trackone.security

import app.trackone.palette.ColorResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the lock screen is put in front of the app. In v1.4.5 LockActivity was `singleTask`: when
 * AppLockWatcher launched it over a MainActivity that had just started (Splash's timer fires while the
 * gate is still up), Android reused the existing lock and cleared everything above it, so MainActivity
 * was destroyed within milliseconds and the app closed when the lock was dismissed. The lock has to be
 * brought to the front, not allowed to clear the task. Read straight from the sources.
 */
class LockGateLaunchTest {

    private val main = ColorResources.mainDir

    @Test
    fun `the lock activity does not clear the activities above it`() {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(main, "AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val activities = doc.getElementsByTagName("activity")
        var launchMode: String? = null
        for (i in 0 until activities.length) {
            val a = activities.item(i) as org.w3c.dom.Element
            if (a.getAttributeNS(android, "name") == ".ui.lock.LockActivity") launchMode = a.getAttributeNS(android, "launchMode")
        }
        assertTrue("LockActivity is not declared in the manifest", launchMode != null)
        assertTrue(
            "LockActivity is launchMode=\"$launchMode\": relaunching it clears the activities above it, destroying MainActivity",
            launchMode !in setOf("singleTask", "singleInstance")
        )
    }

    @Test
    fun `the watcher brings an existing lock to the front instead of stacking another`() {
        val source = File(main, "java/app/trackone/FinanceApplication.kt").readText()
        val launch = Regex("""startActivity\(\s*Intent\([^)]*LockActivity::class\.java\)[^)]*\)""").find(source)?.value
        assertTrue("AppLockWatcher no longer starts LockActivity", launch != null)
        assertEquals(
            "The lock launch must carry FLAG_ACTIVITY_REORDER_TO_FRONT:\n$launch",
            true, launch!!.contains("FLAG_ACTIVITY_REORDER_TO_FRONT")
        )
    }
}
