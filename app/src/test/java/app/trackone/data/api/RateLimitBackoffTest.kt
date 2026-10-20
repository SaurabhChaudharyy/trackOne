package app.trackone.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitBackoffTest {

    private var now = 10_000_000L
    private val backoff = RateLimitBackoff { now }

    @Test
    fun `not paused until Yahoo says to slow down`() {
        backoff.onResponse(200, null)

        assertFalse(backoff.isPaused())
    }

    @Test
    fun `a 429 with no hint pauses for a minute`() {
        backoff.onResponse(429, null)

        assertTrue(backoff.isPaused())
        assertEquals(60_000L, backoff.remainingMs())
    }

    @Test
    fun `the pause ends on its own`() {
        backoff.onResponse(429, null)

        now += 60_000

        assertFalse(backoff.isPaused())
        assertEquals(0L, backoff.remainingMs())
    }

    @Test
    fun `Retry-After in seconds is honoured`() {
        backoff.onResponse(429, "20")

        assertEquals(20_000L, backoff.remainingMs())
    }

    @Test
    fun `an absurd Retry-After is capped`() {
        backoff.onResponse(429, "86400")

        assertEquals(RateLimitBackoff.MAX_PAUSE_MS, backoff.remainingMs())
    }

    @Test
    fun `a Retry-After that is not a number falls back to the default`() {
        backoff.onResponse(429, "Wed, 21 Oct 2026 07:28:00 GMT")

        assertEquals(60_000L, backoff.remainingMs())
    }

    @Test
    fun `a 503 only pauses when it says how long`() {
        backoff.onResponse(503, null)
        assertFalse(backoff.isPaused())

        backoff.onResponse(503, "30")
        assertEquals(30_000L, backoff.remainingMs())
    }

    @Test
    fun `other errors do not pause`() {
        listOf(400, 401, 404, 500).forEach { backoff.onResponse(it, null) }

        assertFalse(backoff.isPaused())
    }

    @Test
    fun `being limited again straight after a pause doubles it`() {
        backoff.onResponse(429, null)
        now += 60_000
        backoff.onResponse(429, null)
        assertEquals(120_000L, backoff.remainingMs())

        now += 120_000
        backoff.onResponse(429, null)
        assertEquals(240_000L, backoff.remainingMs())

        now += 240_000
        backoff.onResponse(429, null)
        assertEquals(RateLimitBackoff.MAX_PAUSE_MS, backoff.remainingMs())
    }

    @Test
    fun `a success resets the doubling`() {
        backoff.onResponse(429, null)
        now += 60_000
        backoff.onResponse(200, null)

        backoff.onResponse(429, null)

        assertEquals(60_000L, backoff.remainingMs())
    }

    @Test
    fun `a second 429 while already paused does not extend or escalate the pause`() {
        backoff.onResponse(429, null)
        now += 10_000
        backoff.onResponse(429, null)               // a request that was already in flight when the first came back

        assertEquals(50_000L, backoff.remainingMs())
    }

    // Yahoo throttled one network for 11 hours by leaving requests hanging, not by answering 429, so a
    // pause that waited for a 429 never started.

    @Test
    fun `a couple of timeouts do not pause`() {
        repeat(RateLimitBackoff.TIMEOUTS_BEFORE_PAUSE - 1) { backoff.onTimeout() }

        assertFalse(backoff.isPaused())
    }

    @Test
    fun `timeouts in a row pause like a 429 with no hint`() {
        repeat(RateLimitBackoff.TIMEOUTS_BEFORE_PAUSE) { backoff.onTimeout() }

        assertTrue(backoff.isPaused())
        assertEquals(60_000L, backoff.remainingMs())
    }

    @Test
    fun `an answer from Yahoo, whatever it says, ends the run of timeouts`() {
        repeat(RateLimitBackoff.TIMEOUTS_BEFORE_PAUSE - 1) { backoff.onTimeout() }
        backoff.onResponse(404, null)
        repeat(RateLimitBackoff.TIMEOUTS_BEFORE_PAUSE - 1) { backoff.onTimeout() }

        assertFalse(backoff.isPaused())
    }

    @Test
    fun `timeouts straight after a pause double it`() {
        repeat(RateLimitBackoff.TIMEOUTS_BEFORE_PAUSE) { backoff.onTimeout() }
        now += 60_000
        repeat(RateLimitBackoff.TIMEOUTS_BEFORE_PAUSE) { backoff.onTimeout() }

        assertEquals(120_000L, backoff.remainingMs())
    }

    @Test
    fun `timeouts of requests already in flight do not extend or escalate a pause`() {
        backoff.onResponse(429, null)
        now += 10_000
        repeat(RateLimitBackoff.TIMEOUTS_BEFORE_PAUSE * 2) { backoff.onTimeout() }

        assertEquals(50_000L, backoff.remainingMs())
    }

    @Test
    fun `a success after timeouts resets the doubling`() {
        repeat(RateLimitBackoff.TIMEOUTS_BEFORE_PAUSE) { backoff.onTimeout() }
        now += 60_000
        backoff.onResponse(200, null)

        repeat(RateLimitBackoff.TIMEOUTS_BEFORE_PAUSE) { backoff.onTimeout() }

        assertEquals(60_000L, backoff.remainingMs())
    }
}
