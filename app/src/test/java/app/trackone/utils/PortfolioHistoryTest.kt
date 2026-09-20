package app.trackone.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioHistoryTest {

    private fun closes(vararg dayPrice: Pair<Long, Double>) = dayPrice.map { DailyClose(it.first, it.second) }

    @Test
    fun `value on a day is quantity times that day's close, summed across holdings`() {
        val series = PortfolioHistory.buildSeries(
            listOf(
                HistoryHolding(quantity = 10.0, currentValue = 0.0, closes = closes(1L to 100.0, 2L to 110.0)),
                HistoryHolding(quantity = 2.0, currentValue = 0.0, closes = closes(1L to 500.0, 2L to 450.0))
            )
        )

        assertEquals(listOf(ValuePoint(1, 2000.0), ValuePoint(2, 2000.0)), series)   // 1000+1000, 1100+900
    }

    @Test
    fun `a market holiday carries the previous close forward instead of dropping the holding`() {
        // India trades on days 1,2,3; US is shut on day 2.
        val series = PortfolioHistory.buildSeries(
            listOf(
                HistoryHolding(1.0, 0.0, closes(1L to 100.0, 2L to 101.0, 3L to 102.0)),
                HistoryHolding(1.0, 0.0, closes(1L to 200.0, 3L to 210.0))
            )
        )

        assertEquals(listOf(300.0, 301.0, 312.0), series.map { it.valueInr })   // day 2 uses US's day-1 close
    }

    @Test
    fun `a holding that starts trading later is backfilled with its first close`() {
        val series = PortfolioHistory.buildSeries(
            listOf(
                HistoryHolding(1.0, 0.0, closes(1L to 100.0, 2L to 100.0, 3L to 100.0)),
                HistoryHolding(1.0, 0.0, closes(3L to 50.0))
            )
        )

        assertEquals(listOf(150.0, 150.0, 150.0), series.map { it.valueInr })
    }

    @Test
    fun `holdings without a price history stay flat at their current value`() {
        val series = PortfolioHistory.buildSeries(
            listOf(
                HistoryHolding(1.0, 0.0, closes(1L to 100.0, 2L to 120.0)),
                HistoryHolding(quantity = 1.0, currentValue = 50_000.0, closes = null),     // cash / failed lookup
                HistoryHolding(quantity = 1.0, currentValue = 1_000.0, closes = emptyList())
            )
        )

        assertEquals(listOf(51_100.0, 51_120.0), series.map { it.valueInr })
    }

    @Test
    fun `with no price history at all there is nothing honest to plot`() {
        assertTrue(PortfolioHistory.buildSeries(listOf(HistoryHolding(1.0, 5_000.0, null))).isEmpty())
        assertTrue(PortfolioHistory.buildSeries(emptyList()).isEmpty())
    }

    @Test
    fun `unsorted input closes are handled`() {
        val series = PortfolioHistory.buildSeries(listOf(HistoryHolding(1.0, 0.0, closes(3L to 30.0, 1L to 10.0, 2L to 20.0))))

        assertEquals(listOf(10.0, 20.0, 30.0), series.map { it.valueInr })
    }

    // ── live point ───────────────────────────────────────────────────────────

    @Test
    fun `the chart ends on the live total so it matches the header`() {
        val series = listOf(ValuePoint(1, 100.0), ValuePoint(2, 110.0))

        // today (day 2) already has a close: replace it with the live total
        assertEquals(listOf(ValuePoint(1, 100.0), ValuePoint(2, 123.0)), PortfolioHistory.withLivePoint(series, todayEpochDay = 2, liveTotal = 123.0))
        // today has no close yet (weekend/early): append it
        assertEquals(listOf(ValuePoint(1, 100.0), ValuePoint(2, 110.0), ValuePoint(3, 123.0)), PortfolioHistory.withLivePoint(series, todayEpochDay = 3, liveTotal = 123.0))
    }

    @Test
    fun `days are counted in IST so an Indian open and a US open land on the same day`() {
        val indiaOpen = 1_790_000_000L                 // any instant
        val sameIstDay = indiaOpen + 3 * 3600          // a few hours later, same IST calendar day

        assertEquals(PortfolioHistory.epochDayIst(indiaOpen), PortfolioHistory.epochDayIst(sameIstDay))
        assertEquals(1L, PortfolioHistory.epochDayIst(86_400L - 19_800L))       // IST midnight = day 1
        assertEquals(0L, PortfolioHistory.epochDayIst(86_400L - 19_800L - 1))
    }
}
