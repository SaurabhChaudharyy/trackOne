package app.trackone.data.api

/**
 * How recent a quote has to be before asking Yahoo again is pointless. There is no backend of ours
 * between the app and Yahoo, so a user pulling to refresh over and over is that many requests to
 * Yahoo from their own IP, which is what gets an app rate-limited. A refresh inside the window is
 * answered from the saved quote instead; the user still sees the refresh happen.
 *
 * Yahoo's own quote responses carry `cache-control: max-age=10`, so nothing younger than that can
 * differ; 30 seconds costs next to nothing in freshness.
 */
object RefreshPolicy {

    /** Shared by the watchlist and net worth: one number to explain, one to change. */
    const val WINDOW_MS = 30_000L

    /** True when a quote stamped [lastUpdated] needs no refetching. Null means never fetched. */
    fun isFresh(lastUpdated: Long?, maxAgeMs: Long = WINDOW_MS, now: Long = System.currentTimeMillis()): Boolean {
        if (lastUpdated == null) return false
        val age = now - lastUpdated
        // A stamp from the future means the clock was changed; nothing can be said about its age.
        return age in 0 until maxAgeMs
    }
}
