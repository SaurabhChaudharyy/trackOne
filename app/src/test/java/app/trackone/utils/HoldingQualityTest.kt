package app.trackone.utils

import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoldingQualityTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun holding(
        type: AssetType = AssetType.STOCK_IN,
        buyPrice: Double = 100.0,
        quantity: Double = 1.0,
        currentValue: Double = 110.0,
        currency: String = "INR",
        updatedAt: Long = now
    ) = NetWorthAssetEntity(
        name = "X", assetType = type, quantity = quantity, buyPrice = buyPrice,
        currentValue = currentValue, currency = currency, updatedAt = updatedAt
    )

    @Test
    fun `a healthy, freshly refreshed holding has no issues`() {
        assertEquals(emptyList<HoldingIssue>(), HoldingQuality.issues(holding(), now))
    }

    @Test
    fun `a market-priced holding not refreshed for days is flagged`() {
        val stale = holding(updatedAt = now - 4 * day)

        assertEquals(HoldingIssue.NO_LIVE_PRICE, HoldingQuality.primaryIssue(stale, now))
    }

    @Test
    fun `three days is not yet stale, so a long weekend does not cry wolf`() {
        assertNull(HoldingQuality.primaryIssue(holding(updatedAt = now - 3 * day), now))
    }

    @Test
    fun `hand-entered holdings are never flagged for a stale price`() {
        for (type in listOf(AssetType.MF, AssetType.CASH, AssetType.BANK)) {
            assertNull(type.name, HoldingQuality.primaryIssue(holding(type = type, buyPrice = 0.0, updatedAt = now - 90 * day), now))
        }
    }

    @Test
    fun `a value still in a foreign currency is flagged as not converted`() {
        val usd = holding(type = AssetType.STOCK_US, currency = "USD")

        assertEquals(HoldingIssue.FOREIGN_CURRENCY, HoldingQuality.primaryIssue(usd, now))
    }

    @Test
    fun `a stale price outranks the currency note, because that is the fixable cause`() {
        val both = holding(type = AssetType.STOCK_US, currency = "USD", updatedAt = now - 10 * day)

        assertEquals(
            listOf(HoldingIssue.NO_LIVE_PRICE, HoldingIssue.FOREIGN_CURRENCY),
            HoldingQuality.issues(both, now)
        )
    }

    @Test
    fun `an implausible gain or loss points at the buy price`() {
        assertEquals(HoldingIssue.IMPLAUSIBLE_GAIN, HoldingQuality.primaryIssue(holding(buyPrice = 1.0, currentValue = 500.0), now))   // 500x
        assertEquals(HoldingIssue.IMPLAUSIBLE_GAIN, HoldingQuality.primaryIssue(holding(buyPrice = 1000.0, currentValue = 10.0), now)) // -99%
    }

    @Test
    fun `ordinary large moves are not flagged`() {
        assertNull(HoldingQuality.primaryIssue(holding(buyPrice = 100.0, currentValue = 900.0), now))   // 9x
        assertNull(HoldingQuality.primaryIssue(holding(buyPrice = 100.0, currentValue = 20.0), now))    // -80%
    }

    @Test
    fun `cash and bank are exempt from the gain check because they have no cost basis`() {
        assertNull(HoldingQuality.primaryIssue(holding(type = AssetType.BANK, buyPrice = 1.0, currentValue = 125_000.0), now))
    }
}
