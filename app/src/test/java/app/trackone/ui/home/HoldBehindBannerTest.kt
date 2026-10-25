package app.trackone.ui.home

import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldBehindBannerTest {

    private fun holding(id: Long, value: Double, qty: Double = 1.0, name: String = "H$id") = NetWorthAssetEntity(
        id = id, name = name, assetType = AssetType.STOCK_IN, quantity = qty, buyPrice = 1.0,
        currentValue = value, addedAt = 0L, updatedAt = 0L
    )

    private val shown = listOf(holding(1, 1000.0), holding(2, 500.0))

    @Test
    fun `a background price move over one rupee is held`() {
        val latest = listOf(holding(1, 1100.0).copy(updatedAt = 9L), holding(2, 500.0))
        assertTrue(holdBehindBanner(shown, latest, backgroundWrite = mapOf(1L to 1100.0)))
    }

    @Test
    fun `a move of one rupee or less shows at once`() {
        val latest = listOf(holding(1, 1000.8), holding(2, 500.0))
        assertFalse(holdBehindBanner(shown, latest, backgroundWrite = mapOf(1L to 1000.8)))
    }

    @Test
    fun `a price the background refresh did not write shows at once`() {
        val latest = listOf(holding(1, 1100.0), holding(2, 500.0))
        // A refresh the user asked for leaves no background write; a manual edit doesn't match it.
        assertFalse(holdBehindBanner(shown, latest, backgroundWrite = emptyMap()))
        assertFalse(holdBehindBanner(shown, latest, backgroundWrite = mapOf(1L to 1050.0)))
    }

    @Test
    fun `an edit or a rename shows at once, even alongside a background price`() {
        val edited = listOf(holding(1, 1100.0, qty = 2.0), holding(2, 500.0))
        val renamed = listOf(holding(1, 1100.0, name = "RELIANCE"), holding(2, 500.0))
        assertFalse(holdBehindBanner(shown, edited, backgroundWrite = mapOf(1L to 1100.0)))
        assertFalse(holdBehindBanner(shown, renamed, backgroundWrite = mapOf(1L to 1100.0)))
    }

    @Test
    fun `an added or removed holding shows at once`() {
        val added = shown + holding(3, 10_000.0)
        val removed = listOf(holding(1, 1100.0))
        assertFalse(holdBehindBanner(shown, added, backgroundWrite = mapOf(1L to 1100.0)))
        assertFalse(holdBehindBanner(shown, removed, backgroundWrite = mapOf(1L to 1100.0)))
    }

    // ── how big a move earns the banner: a fraction of the total, not a fixed rupee ──────────────

    private val bigShown = listOf(holding(1, 1_000_000.0), holding(2, 1_000_000.0))   // ₹20 lakh on screen

    @Test
    fun `on a large portfolio a few hundred rupees is applied quietly`() {
        // 0.025% of the total, the size of an FX tick on US holdings.
        val latest = listOf(holding(1, 1_000_500.0).copy(updatedAt = 9L), holding(2, 1_000_000.0))
        assertFalse(holdBehindBanner(bigShown, latest, backgroundWrite = mapOf(1L to 1_000_500.0)))
    }

    @Test
    fun `a move of a tenth of a percent or more of the total is held`() {
        val atBoundary = listOf(holding(1, 1_002_000.0).copy(updatedAt = 9L), holding(2, 1_000_000.0))
        val justUnder = listOf(holding(1, 1_001_999.0).copy(updatedAt = 9L), holding(2, 1_000_000.0))
        assertTrue(holdBehindBanner(bigShown, atBoundary, backgroundWrite = mapOf(1L to 1_002_000.0)))
        assertFalse(holdBehindBanner(bigShown, justUnder, backgroundWrite = mapOf(1L to 1_001_999.0)))
    }

    @Test
    fun `a fall is held on the same terms as a rise`() {
        val latest = listOf(holding(1, 996_000.0).copy(updatedAt = 9L), holding(2, 1_000_000.0))
        assertTrue(holdBehindBanner(bigShown, latest, backgroundWrite = mapOf(1L to 996_000.0)))
    }

    @Test
    fun `on a small portfolio the bar is proportionally small`() {
        val tiny = listOf(holding(1, 1000.0), holding(2, 500.0))          // ₹1,500 on screen
        val moveOfTwoRupees = listOf(holding(1, 1002.0).copy(updatedAt = 9L), holding(2, 500.0))
        val moveOfOneRupee = listOf(holding(1, 1001.0).copy(updatedAt = 9L), holding(2, 500.0))
        assertTrue(holdBehindBanner(tiny, moveOfTwoRupees, backgroundWrite = mapOf(1L to 1002.0)))   // 0.13%
        assertFalse(holdBehindBanner(tiny, moveOfOneRupee, backgroundWrite = mapOf(1L to 1001.0)))   // 0.07%
    }
}
