package app.trackone.data.api

import app.trackone.data.database.StockEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a saved quote is good enough to skip the network. Three kinds of caller (see [Freshness]):
 * ALWAYS (a screen that needs the live price), PULL (the user pulled to refresh: only age counts)
 * and PASSIVE (the app opening itself: a closing price taken after the close also stays good until
 * the next session, because nothing can change before then).
 *
 * Fixture: NSE on Friday 2026-10-16, 09:15–15:30 IST.
 */
class QuoteFreshnessTest {

    private val open = 1_792_122_300L
    private val close = 1_792_144_800L
    private val mondayOpen = open + 3 * 86_400L
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun stock(
        lastUpdatedMs: Long,
        start: Long = open, end: Long = close, offset: Int = 19_800
    ) = StockEntity(
        "^NSEI", "NIFTY 50", 1.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0,
        lastUpdated = lastUpdatedMs,
        sessionPreStart = start, sessionStart = start, sessionEnd = end, sessionPostEnd = end, sessionGmtOffset = offset
    )

    private fun ms(sec: Long) = sec * 1_000L

    @Test
    fun `a missing quote is never fresh`() {
        assertFalse(QuoteFreshness.isFresh(null, Freshness.PASSIVE, now = 0))
    }

    @Test
    fun `ALWAYS never skips the network`() {
        val now = ms(open + 3_600)

        assertFalse(QuoteFreshness.isFresh(stock(now - 1_000), Freshness.ALWAYS, now))
    }

    @Test
    fun `a pull skips only what is under thirty seconds old`() {
        val now = ms(open + 3_600)

        assertTrue(QuoteFreshness.isFresh(stock(now - 10_000), Freshness.PULL, now))
        assertFalse(QuoteFreshness.isFresh(stock(now - 40_000), Freshness.PULL, now))
    }

    @Test
    fun `a pull never trusts a closing price, so a pull always means something`() {
        val saturday = ms(close + 86_400)

        assertFalse(QuoteFreshness.isFresh(stock(ms(close + 3_600)), Freshness.PULL, saturday))
    }

    @Test
    fun `when the app opens by itself a quote under five minutes old is enough`() {
        val now = ms(open + 3_600)

        assertTrue(QuoteFreshness.isFresh(stock(now - 2 * minute), Freshness.PASSIVE, now))
    }

    @Test
    fun `during the session an older quote is refetched`() {
        val now = ms(open + 3 * 3_600)

        assertFalse(QuoteFreshness.isFresh(stock(now - 10 * minute), Freshness.PASSIVE, now))
    }

    @Test
    fun `a closing price taken after the close stays good all weekend`() {
        val saturdayNoon = ms(close + 20 * 3_600)

        assertTrue(QuoteFreshness.isFresh(stock(ms(close + 30 * 60)), Freshness.PASSIVE, saturdayNoon))
    }

    @Test
    fun `it stays good until the next open and not a minute past it`() {
        val saved = stock(ms(close + 30 * 60))

        assertTrue(QuoteFreshness.isFresh(saved, Freshness.PASSIVE, ms(mondayOpen - 60)))
        assertFalse(QuoteFreshness.isFresh(saved, Freshness.PASSIVE, ms(mondayOpen + 60)))
    }

    @Test
    fun `a quote taken during the session is refetched once the market has closed`() {
        val afterClose = ms(close + 2 * 3_600)

        assertFalse(QuoteFreshness.isFresh(stock(ms(close - 600)), Freshness.PASSIVE, afterClose))
    }

    @Test
    fun `a quote taken just after the close waits for the price to settle`() {
        val now = ms(close + 20 * 60)

        assertFalse(QuoteFreshness.isFresh(stock(ms(close + 60)), Freshness.PASSIVE, now))
        assertTrue(QuoteFreshness.isFresh(stock(ms(close + 15 * 60)), Freshness.PASSIVE, now))
    }

    @Test
    fun `a round-the-clock market never counts as closed`() {
        val start = open
        val now = ms(start + 30 * 3_600)
        val crypto = stock(ms(start + 25 * 3_600), start = start, end = start + 86_399)

        assertFalse(QuoteFreshness.isFresh(crypto, Freshness.PASSIVE, now))
    }

    @Test
    fun `without session times only age counts`() {
        val now = ms(close + 20 * 3_600)

        assertFalse(QuoteFreshness.isFresh(stock(now - 6 * minute, start = 0, end = 0), Freshness.PASSIVE, now))
        assertTrue(QuoteFreshness.isFresh(stock(now - 2 * minute, start = 0, end = 0), Freshness.PASSIVE, now))
    }

    @Test
    fun `a quote stamped in the future is not trusted`() {
        val now = ms(close + 3_600)

        assertFalse(QuoteFreshness.isFresh(stock(now + hour), Freshness.PASSIVE, now))
    }

    @Test
    fun `the presets are what the screens were promised`() {
        assertTrue(Freshness.PULL.maxAgeMs == 30_000L && !Freshness.PULL.honourClosedMarket)
        assertTrue(Freshness.PASSIVE.maxAgeMs == 5 * minute && Freshness.PASSIVE.honourClosedMarket)
        assertTrue(Freshness.ALWAYS.maxAgeMs == 0L)
    }

    @Test
    fun `a refresh the user asked for is a pull and one the app started itself is passive`() {
        assertTrue(Freshness.forRefresh(userRequested = true) == Freshness.PULL)
        assertTrue(Freshness.forRefresh(userRequested = false) == Freshness.PASSIVE)
    }
}
