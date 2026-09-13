package app.trackone.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

private fun ist(year: Int, month: Int, day: Int): Calendar =
    Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
        clear()
        set(year, month - 1, day, 12, 0)
    }

private fun et(year: Int, month: Int, day: Int): Calendar =
    Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
        clear()
        set(year, month - 1, day, 12, 0)
    }

class MarketCalendarTest {

    @Test
    fun `an ordinary weekday with no events returns an empty list`() {
        // 2026-09-16 is a Wednesday, not a holiday/expiry/Diwali day, and not a 3rd Friday.
        val events = MarketCalendar.getTodayEvents(ist(2026, 9, 16), et(2026, 9, 16))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `NSE holiday is reported`() {
        // Independence Day 2026, from the hardcoded holiday list.
        val events = MarketCalendar.getTodayEvents(ist(2026, 8, 15), et(2026, 8, 15))
        assertTrue(events.any { it.market == "NSE" && it.label == "Market Holiday" })
    }

    @Test
    fun `NYSE holiday is reported independently of NSE`() {
        // MLK Day 2026 (Jan 19, a Monday) is an NYSE holiday, not an NSE one, and not a
        // Thursday/Friday — so it can't accidentally also trigger an NSE/NYSE expiry event.
        val events = MarketCalendar.getTodayEvents(ist(2026, 1, 19), et(2026, 1, 19))
        assertTrue(events.any { it.market == "NYSE" && it.label == "Market Holiday" })
        assertTrue(events.none { it.market == "NSE" })
    }

    @Test
    fun `an ordinary Thursday is a weekly F&O expiry`() {
        // 2026-09-17 is a Thursday, not the last Thursday of September 2026 (which is the 24th).
        val events = MarketCalendar.getTodayEvents(ist(2026, 9, 17), et(2026, 9, 17))
        assertTrue(events.any { it.market == "NSE" && it.label == "Weekly F&O Expiry" })
    }

    @Test
    fun `the last Thursday of the month is a monthly expiry, not weekly`() {
        // 2026-09-24 is the last Thursday of September 2026.
        val events = MarketCalendar.getTodayEvents(ist(2026, 9, 24), et(2026, 9, 24))
        assertTrue(events.any { it.market == "NSE" && it.label == "Monthly F&O Expiry" })
        assertTrue(events.none { it.label == "Weekly F&O Expiry" })
    }

    @Test
    fun `Diwali Muhurat trading is reported on its special date`() {
        val events = MarketCalendar.getTodayEvents(ist(2026, 11, 8), et(2026, 11, 8))
        assertTrue(events.any { it.market == "NSE" && it.label.contains("Diwali") })
    }

    @Test
    fun `third Friday of the month is NYSE monthly options expiry`() {
        // 2026-09-18 is the 3rd Friday of September 2026.
        val events = MarketCalendar.getTodayEvents(ist(2026, 9, 18), et(2026, 9, 18))
        assertTrue(events.any { it.market == "NYSE" && it.label.contains("OPEX") })
    }

    @Test
    fun `a Friday outside the 15th-21st window is not an options expiry`() {
        // 2026-09-25 is a Friday but not the 3rd Friday.
        val events = MarketCalendar.getTodayEvents(ist(2026, 9, 25), et(2026, 9, 25))
        assertTrue(events.none { it.label.contains("OPEX") })
    }
}
