package app.trackone.ui.home

import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.database.StockEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TopMoverTest {

    private fun stock(symbol: String, price: Double, currency: String = "USD") = StockEntity(
        symbol = symbol, companyName = symbol, currentPrice = price, change = 1.0, changePercent = 1.0,
        highPrice = 0.0, lowPrice = 0.0, openPrice = 0.0, previousClose = 0.0, currency = currency
    )

    private fun holding(name: String, type: AssetType, qty: Double, valueInr: Double, currency: String = "INR") =
        NetWorthAssetEntity(
            name = name, assetType = type, quantity = qty, buyPrice = 1.0,
            currentValue = valueInr, currency = currency
        )

    @Test
    fun `gold holding value is the rupee holding value, not USD-per-ounce times grams`() {
        // Regression: GC=F quotes USD per troy ounce; the holding is 15 GRAMS worth ₹204,567.
        // price * qty = 4424.90 * 15 = 66,373.5 "dollars" was shown before.
        val mover = buildTopMover(
            holding("GOLD", AssetType.GOLD, qty = 15.0, valueInr = 204567.42),
            stock("GC=F", price = 4424.90)
        )

        assertEquals(204567.42, mover.currentVal, 0.0001)
    }

    @Test
    fun `metals are labelled by name, not by their futures ticker`() {
        assertEquals("Gold", buildTopMover(holding("GOLD", AssetType.GOLD, 1.0, 1.0), stock("GC=F", 1.0)).label)
        assertEquals("Silver", buildTopMover(holding("SILVER", AssetType.SILVER, 1.0, 1.0), stock("SI=F", 1.0)).label)
    }

    @Test
    fun `Indian stock label drops the Yahoo exchange suffix`() {
        val mover = buildTopMover(
            holding("RELIANCE", AssetType.STOCK_IN, 10.0, 12264.0),
            stock("RELIANCE.NS", 1226.4, currency = "INR")
        )

        assertEquals("RELIANCE", mover.label)
    }

    @Test
    fun `a holding not yet converted to INR has no value to show rather than a mislabelled one`() {
        val mover = buildTopMover(
            holding("AAPL", AssetType.STOCK_US, 2.0, 400.0, currency = "USD"),
            stock("AAPL", 200.0)
        )

        assertEquals(0.0, mover.currentVal, 0.0)
    }
}
