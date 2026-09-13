package app.trackone.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
