package app.trackone.utils

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
}
