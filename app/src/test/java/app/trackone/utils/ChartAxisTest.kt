package app.trackone.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartAxisTest {

    @Test
    fun `bounds hug the data with padding, rather than starting at zero`() {
        // Real case: a 7.4L portfolio moving +-2%. A zero-based axis flattens that to a line.
        val (lo, hi) = ChartAxis.bounds(726_871.0, 753_525.0)

        assertTrue(lo < 726_871.0 && lo > 700_000.0)
        assertTrue(hi > 753_525.0 && hi < 780_000.0)
    }

    @Test
    fun `a perfectly flat series still gets a visible axis, never zero height`() {
        val (lo, hi) = ChartAxis.bounds(100_000.0, 100_000.0)

        assertTrue(hi - lo > 0.0)
        assertEquals(100_000.0, (lo + hi) / 2, 1e-6)    // centred on the value
    }

    @Test
    fun `padding scales with the swing so a big move is not squashed`() {
        val (lo, hi) = ChartAxis.bounds(100.0, 200.0)

        assertEquals(15.0, 100.0 - lo, 1e-9)     // 15% of the 100-wide swing
        assertEquals(15.0, hi - 200.0, 1e-9)
    }

    @Test
    fun `ticks put round labels on both chart edges and contain the data`() {
        val t = ChartAxis.ticks(952_000.0, 991_000.0)

        assertTrue(t.lo <= 952_000.0 && t.hi >= 991_000.0)
        assertEquals(0.0, t.lo % t.step, 1e-6)          // bottom edge is a label
        assertEquals(0.0, t.hi % t.step, 1e-6)          // top edge is a label
        assertTrue(t.count in 3..6)
    }

    @Test
    fun `ticks pick the finest round step, not a coarse one that leaves empty chart`() {
        // Real 1Y case: 8.1L..9.95L. A 1L step put the floor at 7L, a band of empty chart.
        val t = ChartAxis.ticks(810_000.0, 995_000.0)

        assertEquals(50_000.0, t.step, 1e-6)
        assertEquals(800_000.0, t.lo, 1e-6)
        assertEquals(1_050_000.0, t.hi, 1e-6)
    }

    @Test
    fun `ticks on a flat series still span a visible range`() {
        val t = ChartAxis.ticks(100_000.0, 100_000.0)

        assertTrue(t.hi > t.lo)
        assertTrue(t.lo <= 100_000.0 && t.hi >= 100_000.0)
        assertTrue(t.count >= 2)
    }

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    @Test
    fun `a single session is labelled with times of day`() {
        assertEquals("h:mm a", ChartAxis.labelPattern(spanMs = 6 * hour, intraday = true))
    }

    @Test
    fun `an intraday span that crosses a day boundary adds the date to the time`() {
        // India's session plus the US one that evening, or a weekend between two sessions.
        assertEquals("d MMM, h:mm a", ChartAxis.labelPattern(spanMs = 3 * day, intraday = true))
    }

    @Test
    fun `daily ranges up to about 200 days are labelled day and month`() {
        assertEquals("d MMM", ChartAxis.labelPattern(spanMs = 30 * day, intraday = false))
    }

    @Test
    fun `longer histories are labelled month and two-digit year`() {
        assertEquals("MMM ''yy", ChartAxis.labelPattern(spanMs = 365 * day, intraday = false))
    }
}
