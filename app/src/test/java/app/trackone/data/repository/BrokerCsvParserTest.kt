package app.trackone.data.repository

import app.trackone.data.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerCsvParserTest {

    // ── detectFormat ─────────────────────────────────────────────────────────

    @Test
    fun `detects Format A from stock name and isin headers`() {
        val headers = listOf(
            "stock name", "isin", "quantity", "average buy price",
            "buy value", "closing price", "closing value", "unrealised p&l"
        )

        assertEquals(BrokerCsvParser.BrokerFormat.FORMAT_A, BrokerCsvParser.detectFormat(headers))
    }

    @Test
    fun `detects Format B from instrument and qty headers`() {
        val headers = listOf("instrument", "qty.", "avg. cost", "ltp", "invested", "cur. val", "p&l", "net chg.")

        assertEquals(BrokerCsvParser.BrokerFormat.FORMAT_B, BrokerCsvParser.detectFormat(headers))
    }

    @Test
    fun `detects Format C from the exact four-column vested-style header`() {
        val headers = listOf("name", "quantity", "buyprice", "currentvalue")

        assertEquals(BrokerCsvParser.BrokerFormat.FORMAT_C, BrokerCsvParser.detectFormat(headers))
    }

    @Test
    fun `Zerodha header wins over Format A despite also containing isin`() {
        // Real Zerodha Console export: has an "isin" column (which Format A also matches on),
        // but "quantity available" / "previous closing price" must take priority.
        val headers = listOf(
            "", "symbol", "isin", "sector", "quantity available", "quantity discrepant",
            "quantity long term", "quantity pledged (margin)", "quantity pledged (loan)",
            "average price", "previous closing price", "unrealized p&l", "unrealized p&l pct."
        )

        assertEquals(BrokerCsvParser.BrokerFormat.FORMAT_ZERODHA, BrokerCsvParser.detectFormat(headers))
    }

    @Test
    fun `IB positions header wins via datadiscriminator plus cost basis`() {
        val headers = listOf(
            "datadiscriminator", "asset category", "currency", "symbol", "quantity",
            "mult", "cost price", "cost basis", "close price", "value", "unrealized p_l", "code"
        )

        assertEquals(BrokerCsvParser.BrokerFormat.FORMAT_IB_POSITIONS, BrokerCsvParser.detectFormat(headers))
    }

    @Test
    fun `detects Vested trades format from ticker plus order type`() {
        val headers = listOf(
            "date", "time (in utc)", "name", "ticker", "activity", "order type",
            "quantity", "price per share (in usd)", "cash amount (in usd)", "commission charges (in usd)"
        )

        assertEquals(BrokerCsvParser.BrokerFormat.FORMAT_VESTED_TRADES, BrokerCsvParser.detectFormat(headers))
    }

    @Test
    fun `unrecognised header returns null`() {
        val headers = listOf("date", "description", "amount")

        assertNull(BrokerCsvParser.detectFormat(headers))
    }

    // ── parseFormatZerodha ───────────────────────────────────────────────────

    @Test
    fun `parses a Zerodha row, deriving current value from quantity times previous close`() {
        val header = listOf(
            "", "symbol", "isin", "sector", "quantity available", "quantity discrepant",
            "quantity long term", "quantity pledged (margin)", "quantity pledged (loan)",
            "average price", "previous closing price", "unrealized p&l", "unrealized p&l pct."
        )
        val row = listOf(
            "", "INFY", "INE009A01021", "IT", "10", "0", "10", "0", "0", "1500.0", "1600.0", "1000.0", "6.67"
        )

        val holding = BrokerCsvParser.parseFormatZerodha(header, row)

        assertNotNull(holding)
        assertEquals("INFY", holding!!.symbol)
        assertEquals("INE009A01021", holding.isin)
        assertEquals(10.0, holding.quantity, 0.0)
        assertEquals(1500.0, holding.avgBuyPrice, 0.0)
        assertEquals(16000.0, holding.currentValue, 0.0) // 10 * 1600.0
        assertEquals("INR", holding.currency)
    }

    @Test
    fun `Zerodha row missing a required column is skipped, not crashed on`() {
        val header = listOf("symbol", "average price") // missing quantity available / previous closing price
        val row = listOf("INFY", "1500.0")

        assertNull(BrokerCsvParser.parseFormatZerodha(header, row))
    }

    // ── parseFormatIbPositions ───────────────────────────────────────────────

    @Test
    fun `parses an IB open-positions row using header-based column lookup`() {
        val header = listOf(
            "datadiscriminator", "asset category", "currency", "symbol", "quantity",
            "mult", "cost price", "cost basis", "close price", "value", "unrealized p_l", "code"
        )
        val row = listOf(
            "Summary", "Stocks", "USD", "AAPL", "10", "1", "150.0", "1500.0", "180.0", "1800.0", "300.0", ""
        )

        val holding = BrokerCsvParser.parseFormatIbPositions(header, row)

        assertNotNull(holding)
        assertEquals("AAPL", holding!!.symbol)
        assertEquals(10.0, holding.quantity, 0.0)
        assertEquals(150.0, holding.avgBuyPrice, 0.0)
        assertEquals(1800.0, holding.currentValue, 0.0)
        assertEquals("USD", holding.currency)
    }

    @Test
    fun `IB row defaults to USD when the currency column is absent`() {
        val header = listOf("datadiscriminator", "symbol", "quantity", "cost price", "value")
        val row = listOf("Summary", "AAPL", "10", "150.0", "1800.0")

        val holding = BrokerCsvParser.parseFormatIbPositions(header, row)

        assertNotNull(holding)
        assertEquals("USD", holding!!.currency)
    }

    // ── parseFormatA (HDFC Securities / Angel One) ──────────────────────────

    @Test
    fun `parses a Format A row by fixed column position`() {
        // Stock Name | ISIN | Qty | Avg Buy Price | Buy Value | Closing Price | Closing Value | P&L
        val row = listOf("RELIANCE", "INE002A01018", "10", "2400.0", "24000.0", "2500.0", "25000.0", "1000.0")

        val holding = BrokerCsvParser.parseFormatA(row)

        assertNotNull(holding)
        assertEquals("RELIANCE", holding!!.symbol)
        assertEquals("INE002A01018", holding.isin)
        assertEquals(10.0, holding.quantity, 0.0)
        assertEquals(2400.0, holding.avgBuyPrice, 0.0)
        assertEquals(25000.0, holding.currentValue, 0.0)
        assertEquals("INR", holding.currency)
        assertEquals(AssetType.STOCK_IN, holding.assetType)
    }

    @Test
    fun `Format A row with too few columns is skipped`() {
        assertNull(BrokerCsvParser.parseFormatA(listOf("RELIANCE", "INE002A01018")))
    }

    @Test
    fun `Format A row with a blank stock name is skipped`() {
        val row = listOf("", "INE002A01018", "10", "2400.0", "24000.0", "2500.0", "25000.0", "1000.0")
        assertNull(BrokerCsvParser.parseFormatA(row))
    }

    // ── parseFormatB (Zerodha Console CSV / Groww Excel "Holdings" export) ──

    @Test
    fun `parses a Format B row by fixed column position`() {
        // Instrument | Qty. | Avg. cost | LTP | Invested | Cur. val | P&L | Net chg.
        val row = listOf("infy", "5", "1500.0", "1600.0", "7500.0", "8000.0", "500.0", "6.67")

        val holding = BrokerCsvParser.parseFormatB(row)

        assertNotNull(holding)
        assertEquals("INFY", holding!!.symbol) // uppercased
        assertEquals(5.0, holding.quantity, 0.0)
        assertEquals(1500.0, holding.avgBuyPrice, 0.0)
        assertEquals(8000.0, holding.currentValue, 0.0)
        assertEquals("INR", holding.currency)
    }

    @Test
    fun `Format B row with too few columns is skipped`() {
        assertNull(BrokerCsvParser.parseFormatB(listOf("INFY", "5")))
    }

    // ── parseFormatC (Vested / Interactive Brokers — name/quantity/buyPrice/currentValue) ──

    @Test
    fun `parses a Format C row with a plain ticker`() {
        val row = listOf("AMD", "10", "80.0", "900.0")

        val holding = BrokerCsvParser.parseFormatC(row)

        assertNotNull(holding)
        assertEquals("AMD", holding!!.symbol)
        assertEquals(80.0, holding.avgBuyPrice, 0.0)
        assertEquals("USD", holding.currency)
        assertEquals(AssetType.STOCK_US, holding.assetType)
    }

    @Test
    fun `Format C resolves a Vested full company name to its ticker`() {
        val row = listOf("APPLE INC", "10", "150.0", "1800.0")

        val holding = BrokerCsvParser.parseFormatC(row)

        assertNotNull(holding)
        assertEquals("AAPL", holding!!.symbol)
    }

    @Test
    fun `Format C treats a blank buy price as break-even, not a skip`() {
        val row = listOf("AMD", "10", "", "900.0")

        val holding = BrokerCsvParser.parseFormatC(row)

        assertNotNull(holding)
        assertEquals(0.0, holding!!.avgBuyPrice, 0.0)
    }

    // ── aggregateVestedTrades ────────────────────────────────────────────────

    private val vestedTradesHeader = listOf(
        "date", "time (in utc)", "name", "ticker", "activity", "order type",
        "quantity", "price per share (in usd)", "cash amount (in usd)", "commission charges (in usd)"
    )

    private fun vestedTradeRow(
        ticker: String, activity: String, quantity: String, price: String
    ) = BrokerCsvParser.RawRow(
        listOf("2026-01-01", "12:00", "Some Co", ticker, activity, "market", quantity, price, "0", "0")
    )

    @Test
    fun `nets a single buy into one holding`() {
        val (holdings, skipped) = BrokerCsvParser.aggregateVestedTrades(
            vestedTradesHeader, listOf(vestedTradeRow("AAPL", "buy", "10", "150.0"))
        )

        assertEquals(1, holdings.size)
        assertEquals(0, skipped)
        assertEquals("AAPL", holdings[0].symbol)
        assertEquals(10.0, holdings[0].quantity, 0.0)
        assertEquals(150.0, holdings[0].avgBuyPrice, 0.0)
    }

    @Test
    fun `nets multiple buys into a weighted-average cost`() {
        val (holdings, _) = BrokerCsvParser.aggregateVestedTrades(
            vestedTradesHeader,
            listOf(
                vestedTradeRow("AAPL", "buy", "10", "100.0"),  // cost 1000
                vestedTradeRow("AAPL", "buy", "10", "200.0")   // cost 2000
            )
        )

        assertEquals(1, holdings.size)
        assertEquals(20.0, holdings[0].quantity, 0.0)
        assertEquals(150.0, holdings[0].avgBuyPrice, 0.0) // (1000+2000)/20
    }

    @Test
    fun `a fully-exited position (sold back to zero) is dropped, not shown as a phantom holding`() {
        val (holdings, _) = BrokerCsvParser.aggregateVestedTrades(
            vestedTradesHeader,
            listOf(
                vestedTradeRow("AAPL", "buy", "10", "100.0"),
                vestedTradeRow("AAPL", "sell", "10", "150.0")
            )
        )

        assertTrue(holdings.isEmpty())
    }

    @Test
    fun `a partial sell reduces quantity but keeps the position`() {
        val (holdings, _) = BrokerCsvParser.aggregateVestedTrades(
            vestedTradesHeader,
            listOf(
                vestedTradeRow("AAPL", "buy", "10", "100.0"),
                vestedTradeRow("AAPL", "sell", "4", "150.0")
            )
        )

        assertEquals(1, holdings.size)
        assertEquals(6.0, holdings[0].quantity, 0.0)
    }

    @Test
    fun `dividend and other non-buy-sell activity rows are skipped, not counted as errors on the position`() {
        val (holdings, skipped) = BrokerCsvParser.aggregateVestedTrades(
            vestedTradesHeader,
            listOf(
                vestedTradeRow("AAPL", "buy", "10", "100.0"),
                vestedTradeRow("AAPL", "dividend", "0", "0")
            )
        )

        assertEquals(1, holdings.size)
        assertEquals(10.0, holdings[0].quantity, 0.0)
        assertEquals(1, skipped)
    }

    @Test
    fun `missing required columns in the header aborts with everything skipped`() {
        val (holdings, skipped) = BrokerCsvParser.aggregateVestedTrades(
            listOf("date", "name"), // missing ticker/activity/quantity/price columns
            listOf(vestedTradeRow("AAPL", "buy", "10", "100.0"))
        )

        assertTrue(holdings.isEmpty())
        assertEquals(1, skipped)
    }
}
