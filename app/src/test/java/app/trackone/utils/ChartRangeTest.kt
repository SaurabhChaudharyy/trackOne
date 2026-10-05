package app.trackone.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartRangeTest {

    @Test
    fun `the chips run from the shortest window to the full history`() {
        assertEquals(listOf("1D", "1W", "1M", "3M", "1Y", "ALL"), ChartRange.entries.map { it.label })
    }

    @Test
    fun `1D fetches intraday bars of the latest session`() {
        assertEquals("1d", ChartRange.DAY.yahooRange)
        assertEquals("5m", ChartRange.DAY.interval)
        assertTrue(ChartRange.DAY.intraday)
    }

    @Test
    fun `ALL fetches the whole history from an explicit start, in weekly bars`() {
        // range=max would let Yahoo pick its own bar size (quarterly for a long history).
        assertTrue(ChartRange.ALL.fullHistory)
        assertEquals(null, ChartRange.ALL.yahooRange)
        assertEquals("1wk", ChartRange.ALL.interval)
        assertFalse(ChartRange.ALL.intraday)
    }

    @Test
    fun `only ALL asks for the full history`() {
        assertEquals(listOf(ChartRange.ALL), ChartRange.entries.filter { it.fullHistory })
    }

    @Test
    fun `only 1D is intraday`() {
        assertEquals(listOf(ChartRange.DAY), ChartRange.entries.filter { it.intraday })
    }
}
