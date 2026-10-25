package app.trackone.utils

import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Test

private fun asset(
    buyPrice: Double = 0.0,
    quantity: Double = 1.0,
    currentValue: Double
) = NetWorthAssetEntity(
    name = "TEST",
    assetType = AssetType.STOCK_IN,
    quantity = quantity,
    buyPrice = buyPrice,
    currentValue = currentValue
)

class PortfolioGainLossTest {

    @Test
    fun `empty portfolio - everything is zero, no divide-by-zero`() {
        val result = PortfolioGainLoss.compute(emptyList())

        assertEquals(0.0, result.invested, 0.0)
        assertEquals(0.0, result.current, 0.0)
        assertEquals(0.0, result.absChange, 0.0)
        assertEquals(0.0, result.pctChange, 0.0)
    }

    @Test
    fun `single asset with a gain`() {
        val result = PortfolioGainLoss.compute(
            listOf(asset(buyPrice = 100.0, quantity = 2.0, currentValue = 300.0))
        )

        assertEquals(200.0, result.invested, 0.0)
        assertEquals(300.0, result.current, 0.0)
        assertEquals(100.0, result.absChange, 0.0)
        assertEquals(50.0, result.pctChange, 0.0001)
    }

    @Test
    fun `single asset with a loss`() {
        val result = PortfolioGainLoss.compute(
            listOf(asset(buyPrice = 100.0, quantity = 1.0, currentValue = 60.0))
        )

        assertEquals(100.0, result.invested, 0.0)
        assertEquals(-40.0, result.absChange, 0.0)
        assertEquals(-40.0, result.pctChange, 0.0001)
    }

    @Test
    fun `asset with no buy price is treated as break-even`() {
        val result = PortfolioGainLoss.compute(
            listOf(asset(buyPrice = 0.0, quantity = 5.0, currentValue = 500.0))
        )

        assertEquals(500.0, result.invested, 0.0)
        assertEquals(500.0, result.current, 0.0)
        assertEquals(0.0, result.absChange, 0.0)
        assertEquals(0.0, result.pctChange, 0.0)
    }

    @Test
    fun `mixed portfolio - known and unknown cost basis combine correctly`() {
        val result = PortfolioGainLoss.compute(
            listOf(
                asset(buyPrice = 100.0, quantity = 1.0, currentValue = 150.0), // +50 gain
                asset(buyPrice = 0.0, quantity = 1.0, currentValue = 200.0)    // break-even
            )
        )

        assertEquals(300.0, result.invested, 0.0)  // 100 + 200
        assertEquals(350.0, result.current, 0.0)   // 150 + 200
        assertEquals(50.0, result.absChange, 0.0)
        assertEquals(50.0 / 300.0 * 100.0, result.pctChange, 0.0001)
    }

    @Test
    fun `computePerAsset matches compute for a single-asset list`() {
        val singleAsset = asset(buyPrice = 100.0, quantity = 2.0, currentValue = 180.0)

        val perAsset = PortfolioGainLoss.computePerAsset(singleAsset)
        val portfolio = PortfolioGainLoss.compute(listOf(singleAsset))

        assertEquals(portfolio.invested, perAsset.invested, 0.0)
        assertEquals(portfolio.current, perAsset.current, 0.0)
        assertEquals(portfolio.absChange, perAsset.absChange, 0.0)
        assertEquals(portfolio.pctChange, perAsset.pctChange, 0.0001)
    }

    // ── cash / bank have no cost basis ───────────────────────────────────────

    private fun typed(type: AssetType, buyPrice: Double, currentValue: Double, quantity: Double = 1.0) =
        NetWorthAssetEntity(
            name = "X", assetType = type, quantity = quantity, buyPrice = buyPrice, currentValue = currentValue
        )

    @Test
    fun `a bank balance with a placeholder buy price is break-even, not a phantom gain`() {
        // Seen in real data: buyPrice = 1.0 on a 1,25,000 balance made the "gain" 1,24,999 (+12,499,900%).
        val result = PortfolioGainLoss.computePerAsset(typed(AssetType.BANK, buyPrice = 1.0, currentValue = 125_000.0))

        assertEquals(125_000.0, result.invested, 0.0)
        assertEquals(0.0, result.absChange, 0.0)
        assertEquals(0.0, result.pctChange, 0.0)
    }

    @Test
    fun `cash is break-even regardless of buy price`() {
        val result = PortfolioGainLoss.computePerAsset(typed(AssetType.CASH, buyPrice = 500.0, currentValue = 50_000.0))

        assertEquals(0.0, result.absChange, 0.0)
    }

    @Test
    fun `portfolio total does not count a cash buy price as cost`() {
        val result = PortfolioGainLoss.compute(
            listOf(
                asset(buyPrice = 100.0, quantity = 1.0, currentValue = 150.0),   // +50
                typed(AssetType.CASH, buyPrice = 1.0, currentValue = 50_000.0)   // must add 0, not +49,999
            )
        )

        assertEquals(50_100.0, result.invested, 0.0)
        assertEquals(50_150.0, result.current, 0.0)
        assertEquals(50.0, result.absChange, 0.0)
    }

    @Test
    fun `only cash and bank lack a cost basis`() {
        assertEquals(false, PortfolioGainLoss.hasCostBasis(typed(AssetType.CASH, 1.0, 1.0)))
        assertEquals(false, PortfolioGainLoss.hasCostBasis(typed(AssetType.BANK, 1.0, 1.0)))
        assertEquals(true, PortfolioGainLoss.hasCostBasis(typed(AssetType.MF, 1.0, 1.0)))
        assertEquals(true, PortfolioGainLoss.hasCostBasis(typed(AssetType.STOCK_US, 1.0, 1.0)))
    }
}
