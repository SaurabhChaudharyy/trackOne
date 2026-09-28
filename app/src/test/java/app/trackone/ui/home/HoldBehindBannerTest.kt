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
}
