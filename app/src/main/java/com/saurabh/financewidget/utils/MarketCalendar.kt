package com.saurabh.financewidget.utils

import java.util.Calendar
import java.util.TimeZone

/**
 * MarketCalendar — provides today's notable market events.
 *
 * Coverage:
 *  - NSE/BSE public holidays (hardcoded for the next 12-month window)
 *  - NSE weekly F&O expiry: every Thursday (or Wednesday if Thursday is a holiday)
 *  - NSE monthly F&O expiry: last Thursday of each month
 *  - NYSE monthly options expiry: third Friday of each month
 *
 * Returns an empty list when there are no events today, so the UI card can hide itself.
 */
object MarketCalendar {

    data class MarketEvent(
        val market: String,   // e.g. "NSE" or "NYSE"
        val label: String     // e.g. "Weekly F&O Expiry" or "Market Holiday — Diwali"
    )

    // ── NSE/BSE holidays (yyyy/M/d) — update annually ───────────────────────
    // Source: NSE trading holiday list
    private val nseHolidays: Set<Triple<Int, Int, Int>> = setOf(
        // 2025
        Triple(2025, 1, 26),   // Republic Day
        Triple(2025, 2, 26),   // Mahashivratri
        Triple(2025, 3, 14),   // Holi
        Triple(2025, 3, 31),   // Id-Ul-Fitr (Ramzan Eid)
        Triple(2025, 4, 14),   // Dr. Ambedkar Jayanti
        Triple(2025, 4, 18),   // Good Friday
        Triple(2025, 5, 1),    // Maharashtra Day
        Triple(2025, 8, 15),   // Independence Day
        Triple(2025, 8, 27),   // Ganesh Chaturthi
        Triple(2025, 10, 2),   // Gandhi Jayanti
        Triple(2025, 10, 2),   // Dussehra
        Triple(2025, 10, 21),  // Diwali Laxmi Puja (Muhurat Trading day - special)
        Triple(2025, 11, 5),   // Prakash Gurpurb (Guru Nanak Jayanti)
        Triple(2025, 12, 25),  // Christmas
        // 2026
        Triple(2026, 1, 26),   // Republic Day
        Triple(2026, 3, 3),    // Mahashivratri
        Triple(2026, 3, 20),   // Holi
        Triple(2026, 4, 3),    // Good Friday
        Triple(2026, 4, 14),   // Dr. Ambedkar Jayanti / Baisakhi
        Triple(2026, 5, 1),    // Maharashtra Day
        Triple(2026, 8, 15),   // Independence Day
        Triple(2026, 10, 2),   // Gandhi Jayanti
        Triple(2026, 11, 14),  // Diwali Laxmi Puja
        Triple(2026, 11, 25),  // Guru Nanak Jayanti
        Triple(2026, 12, 25),  // Christmas
    )

    // ── NYSE holidays (yyyy/M/d) ─────────────────────────────────────────────
    private val nyseHolidays: Set<Triple<Int, Int, Int>> = setOf(
        // 2025
        Triple(2025, 1, 1),    // New Year's Day
        Triple(2025, 1, 20),   // MLK Day
        Triple(2025, 2, 17),   // Presidents' Day
        Triple(2025, 4, 18),   // Good Friday
        Triple(2025, 5, 26),   // Memorial Day
        Triple(2025, 6, 19),   // Juneteenth
        Triple(2025, 7, 4),    // Independence Day
        Triple(2025, 9, 1),    // Labor Day
        Triple(2025, 11, 27),  // Thanksgiving
        Triple(2025, 12, 25),  // Christmas
        // 2026
        Triple(2026, 1, 1),    // New Year's Day
        Triple(2026, 1, 19),   // MLK Day
        Triple(2026, 2, 16),   // Presidents' Day
        Triple(2026, 4, 3),    // Good Friday
        Triple(2026, 5, 25),   // Memorial Day
        Triple(2026, 6, 19),   // Juneteenth
        Triple(2026, 7, 4),    // Independence Day (observed Fri July 3)
        Triple(2026, 9, 7),    // Labor Day
        Triple(2026, 11, 26),  // Thanksgiving
        Triple(2026, 12, 25),  // Christmas
    )

    // ── Diwali (special half-day Muhurat Trading) ────────────────────────────
    private val diwaliDates: Set<Triple<Int, Int, Int>> = setOf(
        Triple(2025, 10, 20),  // Diwali — special 1hr evening session
        Triple(2026, 11, 8),
    )

    /**
     * Returns all market events for today's date.
     * Call on the main thread — pure computation, no I/O.
     */
    fun getTodayEvents(): List<MarketEvent> {
        val events = mutableListOf<MarketEvent>()

        val istCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val y = istCal.get(Calendar.YEAR)
        val m = istCal.get(Calendar.MONTH) + 1  // 1-based
        val d = istCal.get(Calendar.DAY_OF_MONTH)
        val todayKey = Triple(y, m, d)

        // ── NSE holiday ──────────────────────────────────────────────────────
        if (nseHolidays.contains(todayKey)) {
            events.add(MarketEvent("NSE", "Market Holiday"))
        }

        // ── Diwali Muhurat ───────────────────────────────────────────────────
        if (diwaliDates.contains(todayKey)) {
            events.add(MarketEvent("NSE", "Diwali Muhurat Trading (1hr evening session)"))
        }

        // ── NSE weekly F&O expiry (every Thursday, skip NSE holidays) ─────────
        val dayOfWeek = istCal.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek == Calendar.THURSDAY && !nseHolidays.contains(todayKey)) {
            val isMonthlyExpiry = isLastThursdayOfMonth(istCal)
            if (isMonthlyExpiry) {
                events.add(MarketEvent("NSE", "Monthly F&O Expiry"))
            } else {
                events.add(MarketEvent("NSE", "Weekly F&O Expiry"))
            }
        }

        // ── NYSE holiday ─────────────────────────────────────────────────────
        // Use US Eastern time for NYSE holiday check
        val etCal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
        val ey = etCal.get(Calendar.YEAR)
        val em = etCal.get(Calendar.MONTH) + 1
        val ed = etCal.get(Calendar.DAY_OF_MONTH)
        val nyseKey = Triple(ey, em, ed)

        if (nyseHolidays.contains(nyseKey)) {
            events.add(MarketEvent("NYSE", "Market Holiday"))
        }

        // ── NYSE monthly options expiry (3rd Friday of month) ────────────────
        val etDayOfWeek = etCal.get(Calendar.DAY_OF_WEEK)
        if (etDayOfWeek == Calendar.FRIDAY && !nyseHolidays.contains(nyseKey)) {
            if (isThirdFridayOfMonth(etCal)) {
                events.add(MarketEvent("NYSE", "Monthly Options Expiry (OPEX)"))
            }
        }

        return events
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** True if [cal] is the last Thursday of its month. */
    private fun isLastThursdayOfMonth(cal: Calendar): Boolean {
        val clone = cal.clone() as Calendar
        clone.add(Calendar.DAY_OF_MONTH, 7)
        return clone.get(Calendar.MONTH) != cal.get(Calendar.MONTH)
    }

    /** True if [cal] is the 3rd Friday of its month. */
    private fun isThirdFridayOfMonth(cal: Calendar): Boolean {
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        // 3rd Friday is between the 15th and 21st (inclusive)
        return dayOfMonth in 15..21
    }
}
