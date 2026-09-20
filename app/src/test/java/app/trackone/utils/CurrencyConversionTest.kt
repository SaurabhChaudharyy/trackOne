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
    fun `USX price is cents - divided by 100 before applying the USD rate`() {
        val result = CurrencyConversion.toInr(priceInNativeCurrency = 10.0, currency = "USX", usdInrRate = 83.0)

        assertEquals(8.3, result, 0.0001)
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

    @Test
    fun `per-unit INR converts USD shares and leaves INR alone`() {
        assertEquals(16_000.0, CurrencyConversion.perUnitInr(200.0, "USD", 80.0, isMetal = false), 1e-9)
        assertEquals(3_500.0, CurrencyConversion.perUnitInr(3_500.0, "INR", 80.0, isMetal = false), 1e-9)
    }

    @Test
    fun `per-unit INR turns a USD-per-ounce metal quote into rupees per gram`() {
        // 2,000 USD/oz * 80 = 160,000 INR/oz; / 31.1035 g = ~5,144.3 INR/g
        assertEquals(160_000.0 / 31.1035, CurrencyConversion.perUnitInr(2_000.0, "USD", 80.0, isMetal = true), 1e-6)
    }
}
