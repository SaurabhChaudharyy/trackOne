package app.trackone.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPolicyTest {

    private val now = 1_000_000L

    @Test
    fun `the window is thirty seconds`() {
        assertEquals(30_000L, RefreshPolicy.WINDOW_MS)
    }

    @Test
    fun `a quote younger than the window is fresh`() {
        assertTrue(RefreshPolicy.isFresh(lastUpdated = now - 5_000, now = now))
    }

    @Test
    fun `a quote exactly the window old is stale`() {
        assertFalse(RefreshPolicy.isFresh(lastUpdated = now - RefreshPolicy.WINDOW_MS, now = now))
    }

    @Test
    fun `a quote older than the window is stale`() {
        assertFalse(RefreshPolicy.isFresh(lastUpdated = now - 5 * 60_000, now = now))
    }

    @Test
    fun `a quote never fetched is stale`() {
        assertFalse(RefreshPolicy.isFresh(lastUpdated = null, now = now))
    }

    @Test
    fun `a quote stamped in the future, after the clock was changed, is stale`() {
        assertFalse(RefreshPolicy.isFresh(lastUpdated = now + 60_000, now = now))
    }

    @Test
    fun `a window of zero never treats anything as fresh`() {
        assertFalse(RefreshPolicy.isFresh(lastUpdated = now, maxAgeMs = 0, now = now))
    }
}
