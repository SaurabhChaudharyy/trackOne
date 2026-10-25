package app.trackone.utils

import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoldingRepairTest {

    private val usdInr = 90.0

    private fun usStock(
        quantity: Double = 2.0,
        buyPrice: Double,
        currency: String = "INR",
        type: AssetType = AssetType.STOCK_US
    ) = NetWorthAssetEntity(
        name = "GOOGL", assetType = type, quantity = quantity, buyPrice = buyPrice, currentValue = 1.0, currency = currency
    )

    // ── a USD buy price under an INR label ────────────────────────────────────

    @Test
    fun `a USD buy price on an INR-labelled US row is converted`() {
        // Bought at $177.62, now worth $352 x 2 = ₹63,360 at 90: the row says INR but the buy price is dollars.
        val repaired = HoldingRepair.usdBuyPriceInInr(usStock(buyPrice = 177.62), valueInr = 63_360.0, usdInrRate = usdInr)

        assertEquals(177.62 * 90.0, repaired!!, 0.0001)
    }

    @Test
    fun `a buy price that is already in INR is left alone`() {
        // ₹15,986 x 2 against ₹63,360 is a 1.98x gain: nothing wrong.
        assertNull(HoldingRepair.usdBuyPriceInInr(usStock(buyPrice = 15_986.0), valueInr = 63_360.0, usdInrRate = usdInr))
    }

    @Test
    fun `a USD-labelled row is not this repair's business, the normal conversion handles it`() {
        assertNull(HoldingRepair.usdBuyPriceInInr(usStock(buyPrice = 177.62, currency = "USD"), 63_360.0, usdInr))
    }

    @Test
    fun `only US stocks are repaired`() {
        assertNull(HoldingRepair.usdBuyPriceInInr(usStock(buyPrice = 177.62, type = AssetType.STOCK_IN), 63_360.0, usdInr))
        assertNull(HoldingRepair.usdBuyPriceInInr(usStock(buyPrice = 177.62, type = AssetType.CRYPTO), 63_360.0, usdInr))
    }

    @Test
    fun `a huge gain that converting would not make normal is not touched`() {
        // 5000x: no exchange rate explains it, so it stays flagged for a person.
        assertNull(HoldingRepair.usdBuyPriceInInr(usStock(quantity = 1.0, buyPrice = 1.0), valueInr = 5_000.0, usdInrRate = usdInr))
    }

    @Test
    fun `a missing buy price or a missing rate is never guessed`() {
        assertNull(HoldingRepair.usdBuyPriceInInr(usStock(buyPrice = 0.0), 63_360.0, usdInr))
        assertNull(HoldingRepair.usdBuyPriceInInr(usStock(buyPrice = 177.62), 63_360.0, usdInrRate = 0.0))
    }

    // ── one security under two names ─────────────────────────────────────────

    private fun etf(
        name: String,
        quantity: Double,
        buyPrice: Double,
        currentValue: Double,
        currency: String = "INR",
        type: AssetType = AssetType.STOCK_IN
    ) = NetWorthAssetEntity(
        id = name.hashCode().toLong(), name = name, assetType = type, quantity = quantity,
        buyPrice = buyPrice, currentValue = currentValue, currency = currency
    )

    @Test
    fun `merging adds the quantities and averages the buy price by quantity`() {
        val target = etf("NIFTYBEES", 273.0, 282.88, 273 * 258.80)
        val duplicate = etf("NIP IND ETF NIFTY BEES", 328.0, 266.01, 90_836.32)

        val merged = HoldingRepair.merge(target, duplicate)!!

        assertEquals("NIFTYBEES", merged.name)
        assertEquals(601.0, merged.quantity, 0.0001)
        assertEquals(273.67, merged.buyPrice, 0.005)
    }

    @Test
    fun `the merged value follows the target's live price, not the duplicate's stale one`() {
        val target = etf("NIFTYBEES", 273.0, 282.88, 273 * 258.80)
        val duplicate = etf("NIP IND ETF NIFTY BEES", 328.0, 266.01, 90_836.32)

        assertEquals(601 * 258.80, HoldingRepair.merge(target, duplicate)!!.currentValue, 0.01)
    }

    @Test
    fun `rows in different currencies or of different kinds are not merged`() {
        val inr = etf("A", 1.0, 10.0, 10.0)

        assertNull(HoldingRepair.merge(inr, etf("A", 1.0, 10.0, 10.0, currency = "USD")))
        assertNull(HoldingRepair.merge(inr, etf("A", 1.0, 10.0, 10.0, type = AssetType.STOCK_US)))
    }

    @Test
    fun `a lot with no buy price or no quantity is not merged, an average would be invented`() {
        val target = etf("A", 2.0, 10.0, 20.0)

        assertNull(HoldingRepair.merge(target, etf("B", 1.0, 0.0, 10.0)))
        assertNull(HoldingRepair.merge(target, etf("B", 0.0, 10.0, 0.0)))
        assertNull(HoldingRepair.merge(etf("A", 2.0, 0.0, 20.0), etf("B", 1.0, 10.0, 10.0)))
    }
}
