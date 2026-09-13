package app.trackone.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyConversionTest {

    @Test
    fun `USD price is converted using the given rate`() {
        val result = CurrencyConversion.toInr(priceInNativeCurrency = 10.0, currency = "USD", usdInrRate = 83.0)

        assertEquals(830.0, result, 0.0001)
    }

    @Test
    fun `USX price is treated the same as USD`() {
        val result = CurrencyConversion.toInr(priceInNativeCurrency = 10.0, currency = "USX", usdInrRate = 83.0)

        assertEquals(830.0, result, 0.0001)
    }

    @Test
    fun `INR price passes through unconverted`() {
        val result = CurrencyConversion.toInr(priceInNativeCurrency = 250.0, currency = "INR", usdInrRate = 83.0)

        assertEquals(250.0, result, 0.0001)
    }

    @Test
    fun `troy ounce price converts to per-gram price`() {
        val result = CurrencyConversion.troyOunceToGramPrice(pricePerTroyOunceInr = 311.035)

        assertEquals(10.0, result, 0.0001)
    }
}
