package app.trackone.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyConversionTest {

    @Test
    fun `USD price is converted using the given rate`() {
        val result = CurrencyConversion.toInr(priceInNativeCurrency = 10.0, currency = "USD", rateToInr = 83.0)

        assertEquals(830.0, result, 0.0001)
    }

    @Test
    fun `USX price is cents - divided by 100 before applying the USD rate`() {
        val result = CurrencyConversion.toInr(priceInNativeCurrency = 10.0, currency = "USX", rateToInr = 83.0)

        assertEquals(8.3, result, 0.0001)
    }

    @Test
    fun `INR price passes through unconverted`() {
        val result = CurrencyConversion.toInr(priceInNativeCurrency = 250.0, currency = "INR", rateToInr = 83.0)

        assertEquals(250.0, result, 0.0001)
    }

    @Test
    fun `GBp pence become pounds before the GBP rate is applied`() {
        // TSCO.L at 480.50p = £4.805; at ₹110/£ that is ₹528.55, not ₹480.50 or ₹52,855.
        assertEquals(528.55, CurrencyConversion.toInr(480.5, "GBp", rateToInr = 110.0), 1e-9)
        assertEquals(528.55, CurrencyConversion.toInr(480.5, "GBX", rateToInr = 110.0), 1e-9)
    }

    @Test
    fun `any other currency is converted with its own rate, not passed through as rupees`() {
        assertEquals(1_931.4, CurrencyConversion.toInr(32.19, "CAD", rateToInr = 60.0), 1e-9)
    }

    @Test
    fun `majorCurrency names the currency whose rate converts a quote`() {
        assertEquals("GBP", CurrencyConversion.majorCurrency("GBp"))
        assertEquals("GBP", CurrencyConversion.majorCurrency("GBX"))
        assertEquals("USD", CurrencyConversion.majorCurrency("USX"))
        assertEquals("ZAR", CurrencyConversion.majorCurrency("ZAc"))
        assertEquals("ILS", CurrencyConversion.majorCurrency("ILA"))
        assertEquals("CAD", CurrencyConversion.majorCurrency("CAD"))
        assertEquals("INR", CurrencyConversion.majorCurrency("INR"))
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
