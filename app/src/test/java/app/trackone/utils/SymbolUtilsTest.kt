package app.trackone.utils

import app.trackone.data.database.AssetType
import org.junit.Assert.assertEquals
import org.junit.Test

class SymbolUtilsTest {

    @Test
    fun `bare symbol gets NS suffix`() {
        assertEquals("INFY.NS", SymbolUtils.normaliseIndianSymbol("INFY"))
    }

    @Test
    fun `lowercase symbol is uppercased and suffixed`() {
        assertEquals("RELIANCE.NS", SymbolUtils.normaliseIndianSymbol("reliance"))
    }

    @Test
    fun `already-suffixed symbol is not double-suffixed`() {
        assertEquals("TCS.NS", SymbolUtils.normaliseIndianSymbol("TCS.NS"))
    }

    @Test
    fun `symbol with a different exchange suffix is left alone`() {
        assertEquals("SOMETHING.BO", SymbolUtils.normaliseIndianSymbol("something.BO"))
    }

    @Test
    fun `whitespace is trimmed before suffixing`() {
        assertEquals("HDFC.NS", SymbolUtils.normaliseIndianSymbol("  hdfc  "))
    }

    @Test
    fun `display symbol drops the index caret and NSE or BSE suffix`() {
        assertEquals("NIFTY", SymbolUtils.displaySymbol("^NIFTY"))
        assertEquals("TCS", SymbolUtils.displaySymbol("TCS.NS"))
        assertEquals("SOMETHING", SymbolUtils.displaySymbol("SOMETHING.BO"))
        assertEquals("AAPL", SymbolUtils.displaySymbol("AAPL"))
    }

    // ── broker series tags ────────────────────────────────────────────────────

    @Test
    fun `a broker's NSE series tag is not part of the Yahoo ticker`() {
        assertEquals("GOLDIETF.NS", SymbolUtils.normaliseIndianSymbol("GOLDIETF-E"))
        assertEquals("IDEA.NS", SymbolUtils.normaliseIndianSymbol("idea-be"))
        assertEquals("XYZ.NS", SymbolUtils.normaliseIndianSymbol("XYZ-BZ"))
        assertEquals("XYZ.NS", SymbolUtils.normaliseIndianSymbol("XYZ-EQ"))
    }

    @Test
    fun `a series tag is also dropped before an existing exchange suffix`() {
        assertEquals("GOLDIETF.NS", SymbolUtils.normaliseIndianSymbol("GOLDIETF-E.NS"))
        assertEquals("GOLDIETF.BO", SymbolUtils.normaliseIndianSymbol("GOLDIETF-E.BO"))
    }

    @Test
    fun `real hyphenated tickers are left alone`() {
        assertEquals("BAJAJ-AUTO.NS", SymbolUtils.normaliseIndianSymbol("BAJAJ-AUTO"))
        assertEquals("MCDOWELL-N.NS", SymbolUtils.normaliseIndianSymbol("MCDOWELL-N"))
        assertEquals("M&M.NS", SymbolUtils.normaliseIndianSymbol("M&M"))
    }

    // ── yahooSymbol: the one name → ticker mapping ────────────────────────────

    @Test
    fun `yahooSymbol maps metals to their futures and Indian stocks to NSE`() {
        assertEquals("GC=F", SymbolUtils.yahooSymbol("Gold", AssetType.GOLD))
        assertEquals("SI=F", SymbolUtils.yahooSymbol("Silver", AssetType.SILVER))
        assertEquals("GOLDIETF.NS", SymbolUtils.yahooSymbol("GOLDIETF-E", AssetType.STOCK_IN))
    }

    @Test
    fun `a US class share written with a space gets Yahoo's hyphen`() {
        assertEquals("BRK-B", SymbolUtils.yahooSymbol("BRK B", AssetType.STOCK_US))
        assertEquals("BRK-B", SymbolUtils.yahooSymbol("  brk   b ", AssetType.STOCK_US))
        assertEquals("BRK-B", SymbolUtils.yahooSymbol("BRK-B", AssetType.STOCK_US))
    }

    @Test
    fun `US and crypto tickers are otherwise just trimmed and upper-cased`() {
        assertEquals("AAPL", SymbolUtils.yahooSymbol(" aapl ", AssetType.STOCK_US))
        assertEquals("BTC-INR", SymbolUtils.yahooSymbol("btc-inr", AssetType.CRYPTO))
        // A listing on another exchange keeps its suffix; only a space is rewritten.
        assertEquals("VOD.L", SymbolUtils.yahooSymbol("vod.l", AssetType.STOCK_US))
    }
}
