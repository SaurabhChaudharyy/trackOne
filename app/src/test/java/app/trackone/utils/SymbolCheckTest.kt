package app.trackone.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolCheckTest {

    @Test
    fun `errors that mean the symbol does not exist are recognised`() {
        assertTrue(SymbolCheck.isNotFound("HTTP 404: "))
        assertTrue(SymbolCheck.isNotFound("HTTP 404: Not Found"))
        assertTrue(SymbolCheck.isNotFound("No data for RELIANCE INDUSTRIES.NS"))
        assertTrue(SymbolCheck.isNotFound("Invalid price returned"))
    }

    @Test
    fun `network trouble and rate limits are not treated as a bad symbol`() {
        // Blocking a valid symbol because the phone was offline would be worse than letting a bad one through.
        assertFalse(SymbolCheck.isNotFound("Unable to resolve host \"query1.finance.yahoo.com\""))
        assertFalse(SymbolCheck.isNotFound("timeout"))
        assertFalse(SymbolCheck.isNotFound("HTTP 429: Too Many Requests"))
        assertFalse(SymbolCheck.isNotFound("HTTP 500: Server Error"))
        assertFalse(SymbolCheck.isNotFound("Network error"))
    }
}
