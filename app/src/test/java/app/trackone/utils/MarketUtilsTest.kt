package app.trackone.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

private fun nyCalendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
    Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
        clear()
        set(year, month - 1, day, hour, minute)
    }

private fun istCalendar(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
    Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
        clear()
        set(year, month - 1, day, hour, minute)
    }

class MarketUtilsTest {

    @Test
    fun `US market is open at 10am on a Tuesday`() {
        // 2026-09-15 is a Tuesday
        assertTrue(MarketUtils.isUsMarketOpen(nyCalendar(2026, 9, 15, 10, 0)))
    }

    @Test
    fun `US market is closed before 9_30am`() {
        assertFalse(MarketUtils.isUsMarketOpen(nyCalendar(2026, 9, 15, 9, 0)))
    }

    @Test
    fun `US market is closed after 4pm`() {
        assertFalse(MarketUtils.isUsMarketOpen(nyCalendar(2026, 9, 15, 16, 1)))
    }

    @Test
    fun `US market is closed on Saturday even during trading hours`() {
        // 2026-09-19 is a Saturday
        assertFalse(MarketUtils.isUsMarketOpen(nyCalendar(2026, 9, 19, 10, 0)))
    }

    @Test
    fun `India market is open at 10am on a Tuesday`() {
        assertTrue(MarketUtils.isIndiaMarketOpen(istCalendar(2026, 9, 15, 10, 0)))
    }

    @Test
    fun `India market is closed before 9_15am`() {
        assertFalse(MarketUtils.isIndiaMarketOpen(istCalendar(2026, 9, 15, 9, 0)))
    }

    @Test
    fun `India market is closed after 3_30pm`() {
        assertFalse(MarketUtils.isIndiaMarketOpen(istCalendar(2026, 9, 15, 15, 31)))
    }

    @Test
    fun `India market is closed on Sunday`() {
        // 2026-09-20 is a Sunday
        assertFalse(MarketUtils.isIndiaMarketOpen(istCalendar(2026, 9, 20, 10, 0)))
    }
}
