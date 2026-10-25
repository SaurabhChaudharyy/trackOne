package app.trackone.data.api

/**
 * Remembers that Yahoo asked us to slow down, so the whole app stops asking for a while instead of
 * every screen retrying into the same wall. Pure logic over an injected [clock]; the OkHttp side is
 * [BackoffInterceptor]. [clock] should be SystemClock.elapsedRealtime, which a phone's sleep and a
 * wall-clock change do not disturb.
 *
 * A 429 pauses for `Retry-After` when Yahoo says how long, else a minute; being limited again right
 * after a pause doubles it, up to [MAX_PAUSE_MS]. Any success starts over.
 *
 * Yahoo does not always say 429: one network was throttled for 11 hours by leaving requests hanging
 * until they timed out. [TIMEOUTS_BEFORE_PAUSE] timeouts in a row, with no answer from Yahoo between
 * them, pause the same way. Being offline (no host, no route) is a different failure and is not counted.
 */
class RateLimitBackoff(private val clock: () -> Long) {

    @Volatile private var pausedUntil = 0L
    @Volatile private var strikes = 0
    @Volatile private var timeouts = 0

    fun remainingMs(): Long = (pausedUntil - clock()).coerceAtLeast(0L)

    fun isPaused(): Boolean = remainingMs() > 0L

    @Synchronized
    fun onResponse(code: Int, retryAfter: String?) {
        timeouts = 0                    // Yahoo answered, whatever it said
        if (code in 200..299) {
            strikes = 0
            return
        }
        val hinted = retryAfter?.trim()?.toLongOrNull()?.times(1_000L)
        val limited = code == 429 || (code == 503 && hinted != null)
        // Requests already in flight when the first 429 came back will say 429 too: one pause, not several.
        if (!limited || isPaused()) return

        pause(hinted)
    }

    /** A request to Yahoo timed out, which is what being throttled by silence looks like. */
    @Synchronized
    fun onTimeout() {
        // Requests that were already in flight when a pause began will time out too: not a new strike.
        if (isPaused()) return
        if (++timeouts < TIMEOUTS_BEFORE_PAUSE) return
        timeouts = 0
        pause(null)
    }

    private fun pause(hintedMs: Long?) {
        strikes++
        val escalated = (DEFAULT_PAUSE_MS shl (strikes - 1).coerceAtMost(10)).coerceAtMost(MAX_PAUSE_MS)
        val pause = (hintedMs ?: escalated).coerceIn(1_000L, MAX_PAUSE_MS)
        pausedUntil = clock() + pause
    }

    companion object {
        const val DEFAULT_PAUSE_MS = 60_000L
        const val MAX_PAUSE_MS = 5 * 60_000L

        /** One slow request is a bad connection; this many with no answer in between is Yahoo. */
        const val TIMEOUTS_BEFORE_PAUSE = 3
    }
}
