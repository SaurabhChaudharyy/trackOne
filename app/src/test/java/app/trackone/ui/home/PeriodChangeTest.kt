package app.trackone.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PeriodChangeTest {

    private fun points(vararg values: Double) =
        values.mapIndexed { i, v -> PortfolioChartPoint(timestamp = i * 86_400_000L, invested = 500.0, current = v) }

    @Test
    fun `the change is the last point against the first`() {
        val change = periodChange(points(1000.0, 1300.0, 900.0, 1100.0))

        assertNotNull(change)
        assertEquals(100.0, change!!.absChange, 0.0001)
        assertEquals(10.0, change.pctChange, 0.0001)
    }

    @Test
    fun `a window that ends lower is a loss`() {
        val change = periodChange(points(2000.0, 1500.0))!!

        assertEquals(-500.0, change.absChange, 0.0001)
        assertEquals(-25.0, change.pctChange, 0.0001)
    }

    @Test
    fun `the invested cost basis plays no part in a window change`() {
        val change = periodChange(listOf(
            PortfolioChartPoint(0L, invested = 1.0, current = 100.0),
            PortfolioChartPoint(1L, invested = 1.0, current = 110.0)
        ))!!

        assertEquals(10.0, change.pctChange, 0.0001)
    }

    @Test
    fun `fewer than two points has no change to show`() {
        assertNull(periodChange(emptyList()))
        assertNull(periodChange(points(1000.0)))
    }

    @Test
    fun `a window that starts at zero has no percentage to show`() {
        assertNull(periodChange(points(0.0, 500.0)))
    }
}
