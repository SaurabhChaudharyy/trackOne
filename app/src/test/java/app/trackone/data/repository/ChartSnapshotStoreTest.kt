package app.trackone.data.repository

import app.trackone.ui.home.PortfolioChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartSnapshotStoreTest {

    @Test
    fun `a chart survives the round trip`() {
        val points = listOf(
            PortfolioChartPoint(1_790_000_000_000, 805_430.82, 975_222.14),
            PortfolioChartPoint(1_790_086_400_000, 805_430.82, 974_319.45)
        )
        assertEquals(points, ChartSnapshotStore.decode(ChartSnapshotStore.encode(points)))
    }

    @Test
    fun `a damaged snapshot is skipped, not drawn`() {
        assertNull(ChartSnapshotStore.decode("1790000000000,805430.82"))
        assertNull(ChartSnapshotStore.decode("garbage"))
    }
}
