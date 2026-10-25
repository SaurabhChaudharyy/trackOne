package app.trackone.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun `formatChange adds a plus sign for non-negative values`() {
        assertEquals("+1.50", FormatUtils.formatChange(1.5))
        assertEquals("+0.00", FormatUtils.formatChange(0.0))
    }

    @Test
    fun `formatChange has no sign prefix for negative values`() {
        assertEquals("-2.35", FormatUtils.formatChange(-2.35))
    }

    @Test
    fun `formatChangePercent adds percent sign and plus for gains`() {
        assertEquals("+5.00%", FormatUtils.formatChangePercent(5.0))
    }

    @Test
    fun `formatChangePercent for losses has no plus sign`() {
        assertEquals("-3.20%", FormatUtils.formatChangePercent(-3.2))
    }

    @Test
    fun `formatMovePercent leads with the direction arrow`() {
        assertEquals("↗ +4.16%", FormatUtils.formatMovePercent(4.16))
        assertEquals("↘ -3.89%", FormatUtils.formatMovePercent(-3.89))
        assertEquals("↗ +0.00%", FormatUtils.formatMovePercent(0.0))
    }

    @Test
    fun `formatPrice uses each currency's own symbol, not a dollar sign for everything`() {
        assertEquals("$100.00", FormatUtils.formatPrice(100.0, "USD"))
        assertEquals("CA$32.19", FormatUtils.formatPrice(32.19, "CAD"))
        assertEquals("£1.23", FormatUtils.formatPrice(1.23, "GBP"))
        assertEquals("€10.00", FormatUtils.formatPrice(10.0, "EUR"))
    }

    @Test
    fun `formatPrice shows a pence quote as pence, not as dollars`() {
        // Yahoo quotes London stocks in pence (GBp): 480.50 is £4.805, never $480.50.
        assertEquals("480.50p", FormatUtils.formatPrice(480.5, "GBp"))
        assertEquals("480.50p", FormatUtils.formatPrice(480.5, "GBX"))
    }

    @Test
    fun `formatPrice falls back to the code for a currency it doesn't know`() {
        assertEquals("XYZ 10.00", FormatUtils.formatPrice(10.0, "XYZ"))
    }

    @Test
    fun `formatVolume abbreviates billions, millions, thousands`() {
        assertEquals("1.50B", FormatUtils.formatVolume(1_500_000_000))
        assertEquals("2.00M", FormatUtils.formatVolume(2_000_000))
        assertEquals("1.5K", FormatUtils.formatVolume(1_500))
        assertEquals("500", FormatUtils.formatVolume(500))
    }

    @Test
    fun `formatMarketCap abbreviates trillions, billions, millions`() {
        assertEquals("1.00T", FormatUtils.formatMarketCap(1_000_000_000_000.0))
        assertEquals("2.50B", FormatUtils.formatMarketCap(2_500_000_000.0))
        assertEquals("3.00M", FormatUtils.formatMarketCap(3_000_000.0))
        assertEquals("500", FormatUtils.formatMarketCap(500.0))
    }

    @Test
    fun `formatPrice for INR uses two decimal places`() {
        val formatted = FormatUtils.formatPrice(1234.5, "INR")
        assertTrue(formatted.contains("1,234.50") || formatted.contains("1234.50"))
    }

    @Test
    fun `formatPrice for USD sub-dollar values uses more precision`() {
        val formatted = FormatUtils.formatPrice(0.1234, "USD")
        // sub-$1 prices get up to 4 fraction digits so small-value assets aren't rounded to $0.12
        assertTrue(formatted.contains("0.1234") || formatted.contains("0.123"))
    }

    @Test
    fun `formatLastUpdated says Just now for a very recent timestamp`() {
        val result = FormatUtils.formatLastUpdated(System.currentTimeMillis() - 5_000)
        assertEquals("Just now", result)
    }

    @Test
    fun `formatLastUpdated shows minutes for timestamps under an hour old`() {
        val result = FormatUtils.formatLastUpdated(System.currentTimeMillis() - 5 * 60_000)
        assertEquals("5m ago", result)
    }

    @Test
    fun `formatLastUpdated shows hours for timestamps under a day old`() {
        val result = FormatUtils.formatLastUpdated(System.currentTimeMillis() - 3 * 3_600_000)
        assertEquals("3h ago", result)
    }

    // ── formatCompactInr ─────────────────────────────────────────────────────

    @Test
    fun `compact INR uses lakh and crore units`() {
        assertEquals("₹6L", FormatUtils.formatCompactInr(600_000.0))
        assertEquals("₹6.5L", FormatUtils.formatCompactInr(650_000.0))
        assertEquals("₹2Cr", FormatUtils.formatCompactInr(20_000_000.0))
        assertEquals("₹1.25Cr", FormatUtils.formatCompactInr(12_500_000.0))
    }

    @Test
    fun `compact INR uses K below a lakh and plain digits below a thousand`() {
        assertEquals("₹50K", FormatUtils.formatCompactInr(50_000.0))
        assertEquals("₹1.5K", FormatUtils.formatCompactInr(1_500.0))
        assertEquals("₹999", FormatUtils.formatCompactInr(999.0))
        assertEquals("₹0", FormatUtils.formatCompactInr(0.0))
    }

    @Test
    fun `compact INR keeps the sign of a negative value`() {
        assertEquals("-₹2.5L", FormatUtils.formatCompactInr(-250_000.0))
    }
}
