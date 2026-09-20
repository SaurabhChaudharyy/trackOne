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
}
