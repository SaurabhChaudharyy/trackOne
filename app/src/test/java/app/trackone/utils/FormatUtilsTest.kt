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
}
