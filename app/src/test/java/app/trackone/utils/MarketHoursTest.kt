package app.trackone.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a market is open, worked out from the session times Yahoo sends with every quote
 * (`currentTradingPeriod`) instead of fixed weekday hours. Yahoo reports the *latest* session, so a
 * weekend or a holiday shows up as "now is outside it" — no holiday calendar needed.
 *
 * Fixture: NSE on Friday 2026-10-16, 09:15–15:30 IST (the session in the app's own logs).
 */
class MarketHoursTest {

    private val ist = 19_800
    private val fridayOpen = 1_792_122_300L                 // 09:15 IST
    private val fridayClose = 1_792_144_800L                // 15:30 IST
    private val mondayOpen = fridayOpen + 3 * 86_400L

    private fun session(
        start: Long = fridayOpen, end: Long = fridayClose, offset: Int = ist, fetchedAtMs: Long
    ) = TradingSession(preStart = start, start = start, end = end, postEnd = end, gmtOffset = offset, fetchedAt = fetchedAtMs)

    private fun ms(sec: Long) = sec * 1_000L
    private val minute = 60_000L

    @Test
    fun `inside the session is open`() {
        val now = ms(fridayOpen + 3_600)

        assertEquals(MarketHours.State.OPEN, MarketHours.state(session(fetchedAtMs = now - minute), now))
    }

    @Test
    fun `an old quote from earlier in the same session still says open`() {
        val now = ms(fridayOpen + 3 * 3_600)

        assertEquals(MarketHours.State.OPEN, MarketHours.state(session(fetchedAtMs = ms(fridayOpen + 600)), now))
    }

    @Test
    fun `before the session starts is closed`() {
        val now = ms(fridayOpen - 3_600)

        assertEquals(MarketHours.State.CLOSED, MarketHours.state(session(fetchedAtMs = now - 2 * minute), now))
    }

    @Test
    fun `after the close is closed`() {
        val now = ms(fridayClose + 3_600)

        assertEquals(MarketHours.State.CLOSED, MarketHours.state(session(fetchedAtMs = now - 2 * minute), now))
    }

    @Test
    fun `a weekend is closed even when the quote was taken a day ago`() {
        val saturday = ms(fridayClose + 86_400)

        assertEquals(MarketHours.State.CLOSED, MarketHours.state(session(fetchedAtMs = ms(fridayClose + 600)), saturday))
    }

    @Test
    fun `monday before the open is still closed on friday's data`() {
        val now = ms(mondayOpen - 3_600)

        assertEquals(MarketHours.State.CLOSED, MarketHours.state(session(fetchedAtMs = ms(fridayClose + 600)), now))
    }

    @Test
    fun `an old quote cannot say whether a new session has opened`() {
        val mondayMidday = ms(mondayOpen + 3_600)

        assertEquals(MarketHours.State.UNKNOWN, MarketHours.state(session(fetchedAtMs = ms(fridayClose + 600)), mondayMidday))
    }

    @Test
    fun `a fresh quote that still shows the last session means no session today, such as a holiday`() {
        val holidayMidday = ms(mondayOpen + 3_600)

        assertEquals(MarketHours.State.CLOSED, MarketHours.state(session(fetchedAtMs = holidayMidday - minute), holidayMidday))
    }

    @Test
    fun `no session data is unknown`() {
        assertEquals(MarketHours.State.UNKNOWN, MarketHours.state(null, 1_000L))
        assertEquals(MarketHours.State.UNKNOWN, MarketHours.state(session(start = 0, end = 0, fetchedAtMs = 1_000L), 1_000L))
    }

    @Test
    fun `after a midweek session the next open is the next day`() {
        val wednesdayOpen = fridayOpen + 5 * 86_400L        // 2026-10-21

        assertEquals(wednesdayOpen + 86_400, MarketHours.nextOpenSec(session(start = wednesdayOpen, end = wednesdayOpen + 22_500, fetchedAtMs = 0)))
    }

    @Test
    fun `after a friday session the next open is monday`() {
        assertEquals(mondayOpen, MarketHours.nextOpenSec(session(fetchedAtMs = 0)))
    }

    @Test
    fun `the weekday is the exchange's, not UTC's`() {
        // Friday 10:00 in Auckland (UTC+13) is Thursday 21:00 UTC. By the UTC date it would be a Thursday.
        val aucklandFriday = fridayOpen - 24_300
        val s = session(start = aucklandFriday, end = aucklandFriday + 21_600, offset = 46_800, fetchedAtMs = 0)

        assertEquals(aucklandFriday + 3 * 86_400, MarketHours.nextOpenSec(s))
    }

    @Test
    fun `an exchange session is short, a round-the-clock market is not`() {
        assertTrue(MarketHours.isExchangeSession(session(fetchedAtMs = 0)))                                   // 6.25 h
        assertFalse(MarketHours.isExchangeSession(session(start = 0 + 1, end = 1 + 86_399, fetchedAtMs = 0))) // crypto, ~24 h
        assertFalse(MarketHours.isExchangeSession(session(start = 1, end = 1 + 23 * 3_600, fetchedAtMs = 0))) // futures, 23 h
        assertFalse(MarketHours.isExchangeSession(session(start = 0, end = 0, fetchedAtMs = 0)))              // unknown
    }
}
