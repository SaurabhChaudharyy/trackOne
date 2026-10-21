package app.trackone.utils

import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private fun holding(
    name: String = "TEST",
    type: AssetType = AssetType.STOCK_IN,
    quantity: Double = 1.0,
    buyPrice: Double = 0.0,
    currentValue: Double,
    previousClosePrice: Double = 0.0
) = NetWorthAssetEntity(
    name = name,
    assetType = type,
    quantity = quantity,
    buyPrice = buyPrice,
    currentValue = currentValue,
    previousClosePrice = previousClosePrice
)

class HoldingRowStatsTest {

    // ── today's change ────────────────────────────────────────────────────────

    @Test
    fun `today's change is value now minus previous close times quantity`() {
        val change = HoldingRowStats.todayChange(holding(quantity = 2.0, currentValue = 206.0, previousClosePrice = 100.0))!!

        assertEquals(6.0, change.absChange, 0.0001)
        assertEquals(3.0, change.pctChange, 0.0001)
    }

    @Test
    fun `today's change can be negative`() {
        val change = HoldingRowStats.todayChange(holding(quantity = 3.0, currentValue = 291.0, previousClosePrice = 100.0))!!

        assertEquals(-9.0, change.absChange, 0.0001)
        assertEquals(-3.0, change.pctChange, 0.0001)
    }

    @Test
    fun `no previous close means unknown, not unchanged`() {
        assertNull(HoldingRowStats.todayChange(holding(currentValue = 500.0, previousClosePrice = 0.0)))
    }

    @Test
    fun `holdings priced by hand have no today's change`() {
        assertNull(HoldingRowStats.todayChange(holding(type = AssetType.MF, currentValue = 500.0, previousClosePrice = 90.0)))
        assertNull(HoldingRowStats.todayChange(holding(type = AssetType.CASH, currentValue = 500.0, previousClosePrice = 90.0)))
    }

    @Test
    fun `today's change follows an edited quantity`() {
        // 2 units bought, then the user edits it to 4: the per-unit close still gives the right move.
        val change = HoldingRowStats.todayChange(holding(quantity = 4.0, currentValue = 404.0, previousClosePrice = 100.0))!!

        assertEquals(4.0, change.absChange, 0.0001)
    }

    // ── average price ─────────────────────────────────────────────────────────

    @Test
    fun `average price is the buy price for an invested holding`() {
        assertEquals(1478.80, HoldingRowStats.averagePrice(holding(buyPrice = 1478.80, currentValue = 1.0))!!, 0.0001)
    }

    @Test
    fun `no buy price, no average`() {
        assertNull(HoldingRowStats.averagePrice(holding(buyPrice = 0.0, currentValue = 1.0)))
    }

    @Test
    fun `cash has no average even with a placeholder buy price`() {
        assertNull(HoldingRowStats.averagePrice(holding(type = AssetType.CASH, buyPrice = 1.0, currentValue = 50_000.0)))
    }

    // ── detail line ───────────────────────────────────────────────────────────

    private val plain: (Double) -> String = { "%.2f".format(it) }

    @Test
    fun `detail line reads quantity, average and last price`() {
        val line = HoldingRowStats.detailLine(
            holding(quantity = 601.0, buyPrice = 273.67, currentValue = 601 * 261.20), plain
        )

        assertEquals("601 \u00b7 avg 273.67 \u00b7 ltp 261.20", line)
    }

    @Test
    fun `detail line leaves out the average when there is no cost basis`() {
        val line = HoldingRowStats.detailLine(holding(quantity = 2.0, buyPrice = 0.0, currentValue = 500.0), plain)

        assertEquals("2 \u00b7 ltp 250.00", line)
    }

    @Test
    fun `fractional quantities keep their decimals and metals are in grams`() {
        assertEquals(
            "0.5000 \u00b7 ltp 100.00",
            HoldingRowStats.detailLine(holding(type = AssetType.CRYPTO, quantity = 0.5, currentValue = 50.0), plain)
        )
        assertEquals(
            "5g \u00b7 ltp 10.00",
            HoldingRowStats.detailLine(holding(type = AssetType.GOLD, quantity = 5.0, currentValue = 50.0), plain)
        )
    }

    @Test
    fun `holdings priced by hand have no detail line`() {
        assertNull(HoldingRowStats.detailLine(holding(type = AssetType.MF, quantity = 10.0, currentValue = 500.0), plain))
        assertNull(HoldingRowStats.detailLine(holding(quantity = 0.0, currentValue = 500.0), plain))
    }

    // ── sorting ───────────────────────────────────────────────────────────────

    private val big = holding(name = "BIG", quantity = 1.0, buyPrice = 100.0, currentValue = 1000.0, previousClosePrice = 1000.0)
    private val winner = holding(name = "WIN", quantity = 1.0, buyPrice = 100.0, currentValue = 300.0, previousClosePrice = 290.0)
    private val loser = holding(name = "LOSE", quantity = 1.0, buyPrice = 100.0, currentValue = 50.0, previousClosePrice = 60.0)
    private val manual = holding(name = "MANUAL", type = AssetType.MF, quantity = 1.0, buyPrice = 0.0, currentValue = 500.0)

    @Test
    fun `value sort puts the biggest holding first`() {
        val sorted = HoldingSort.VALUE.sort(listOf(loser, manual, big, winner))

        assertEquals(listOf("BIG", "MANUAL", "WIN", "LOSE"), sorted.map { it.name })
    }

    @Test
    fun `P&L sort ranks by return percent with the unknown last`() {
        val sorted = HoldingSort.PNL_PERCENT.sort(listOf(loser, manual, big, winner))

        // WIN +200%, BIG +900%, LOSE -50%; MANUAL has no cost basis so it is break-even (0%).
        assertEquals(listOf("BIG", "WIN", "MANUAL", "LOSE"), sorted.map { it.name })
    }

    @Test
    fun `today sort ranks by day move and puts holdings with no day move last`() {
        val sorted = HoldingSort.TODAY.sort(listOf(manual, loser, winner, big))

        // WIN +3.45%, BIG 0%, LOSE -16.7%; MANUAL has no day move at all.
        assertEquals(listOf("WIN", "BIG", "LOSE", "MANUAL"), sorted.map { it.name })
    }

    @Test
    fun `sort cycles value then P&L then today then back`() {
        assertEquals(HoldingSort.PNL_PERCENT, HoldingSort.VALUE.next())
        assertEquals(HoldingSort.TODAY, HoldingSort.PNL_PERCENT.next())
        assertEquals(HoldingSort.VALUE, HoldingSort.TODAY.next())
    }

    @Test
    fun `an unreadable stored sort falls back to value`() {
        assertEquals(HoldingSort.VALUE, HoldingSort.fromName("nonsense"))
        assertEquals(HoldingSort.VALUE, HoldingSort.fromName(null))
        assertNotNull(HoldingSort.fromName("TODAY"))
    }
}
