package app.trackone.data.repository

import android.content.Context
import android.content.SharedPreferences
import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.ui.home.PortfolioChartPoint
import app.trackone.utils.ChartRange
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ChartSnapshotStoreTest {

    private val saved = mutableMapOf<String, String>()
    private lateinit var store: ChartSnapshotStore

    @Before
    fun setUp() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers { saved[firstArg()] = secondArg(); editor }
        val prefs = mockk<SharedPreferences> {
            every { getString(any(), any()) } answers { saved[firstArg()] }
            every { edit() } returns editor
        }
        val context = mockk<Context> { every { getSharedPreferences(any(), any()) } returns prefs }
        store = ChartSnapshotStore(context)
    }

    private fun asset(name: String, qty: Double, value: Double = qty * 100) = NetWorthAssetEntity(
        id = name.hashCode().toLong(), name = name, assetType = AssetType.STOCK_US,
        quantity = qty, buyPrice = 90.0, currentValue = value
    )

    private val points = listOf(
        PortfolioChartPoint(1_000L, 500.0, 900.0),
        PortfolioChartPoint(2_000L, 500.0, 950.0)
    )

    @Test
    fun `a chart survives the round trip`() {
        val roundTrip = listOf(
            PortfolioChartPoint(1_790_000_000_000, 805_430.82, 975_222.14),
            PortfolioChartPoint(1_790_086_400_000, 805_430.82, 974_319.45)
        )
        assertEquals(roundTrip, ChartSnapshotStore.decode(ChartSnapshotStore.encode(roundTrip)))
    }

    @Test
    fun `a damaged snapshot is skipped, not drawn`() {
        assertNull(ChartSnapshotStore.decode("1790000000000,805430.82"))
        assertNull(ChartSnapshotStore.decode("garbage"))
    }

    @Test
    fun `a snapshot is reused for the same holdings`() {
        val holdings = listOf(asset("AAPL", 2.0), asset("MSFT", 1.0))
        store.save(ChartRange.MONTH, points, ChartSnapshotStore.fingerprint(holdings))

        assertNotNull(store.load(ChartRange.MONTH, ChartSnapshotStore.fingerprint(holdings)))
    }

    @Test
    fun `a snapshot saved before an import is not reused once holdings were added`() {
        // Drawing it would end old history on the new, larger total: a sharp jump on the latest day.
        val before = listOf(asset("AAPL", 2.0))
        store.save(ChartRange.MONTH, points, ChartSnapshotStore.fingerprint(before))

        val after = before + asset("MSFT", 1.0)
        assertNull(store.load(ChartRange.MONTH, ChartSnapshotStore.fingerprint(after)))
    }

    @Test
    fun `a snapshot is not reused when a quantity changed`() {
        val before = listOf(asset("AAPL", 2.0))
        store.save(ChartRange.MONTH, points, ChartSnapshotStore.fingerprint(before))

        assertNull(store.load(ChartRange.MONTH, ChartSnapshotStore.fingerprint(listOf(asset("AAPL", 3.0)))))
    }

    @Test
    fun `a price move alone does not invalidate the snapshot`() {
        val before = listOf(asset("AAPL", 2.0, value = 200.0))
        store.save(ChartRange.MONTH, points, ChartSnapshotStore.fingerprint(before))

        val repriced = listOf(asset("AAPL", 2.0, value = 260.0))
        assertNotNull(store.load(ChartRange.MONTH, ChartSnapshotStore.fingerprint(repriced)))
    }

    @Test
    fun `fingerprint ignores row order`() {
        val a = asset("AAPL", 2.0)
        val b = asset("MSFT", 1.0)
        assertEquals(ChartSnapshotStore.fingerprint(listOf(a, b)), ChartSnapshotStore.fingerprint(listOf(b, a)))
    }

    @Test
    fun `fingerprint differs between different holdings`() {
        assertNotEquals(
            ChartSnapshotStore.fingerprint(listOf(asset("AAPL", 2.0))),
            ChartSnapshotStore.fingerprint(listOf(asset("AAPL", 2.0), asset("MSFT", 1.0)))
        )
    }

    @Test
    fun `a snapshot with no recorded fingerprint (saved by an older build) is not reused`() {
        saved[ChartRange.MONTH.name] = ChartSnapshotStore.encode(points)

        assertNull(store.load(ChartRange.MONTH, ChartSnapshotStore.fingerprint(listOf(asset("AAPL", 2.0)))))
    }
}
