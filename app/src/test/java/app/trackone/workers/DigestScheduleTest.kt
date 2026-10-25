package app.trackone.workers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

private val IST = ZoneId.of("Asia/Kolkata")
private val NEW_YORK = ZoneId.of("America/New_York")

private fun at(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant()

class DigestScheduleTest {

    // ── the Indian digest: an hour after the 3:30pm close ────────────────────

    @Test
    fun `indian digest is 4_30pm IST on a weekday`() {
        val next = DigestMarket.INDIA.nextDigest(at(IST, 2026, 10, 21, 10, 0))   // Wed morning

        assertEquals(at(IST, 2026, 10, 21, 16, 30), next.toInstant())
    }

    @Test
    fun `after the digest has gone the next one is the following weekday`() {
        val next = DigestMarket.INDIA.nextDigest(at(IST, 2026, 10, 21, 17, 0))

        assertEquals(at(IST, 2026, 10, 22, 16, 30), next.toInstant())
    }

    @Test
    fun `exactly at the digest time the next one is tomorrow, not now`() {
        val next = DigestMarket.INDIA.nextDigest(at(IST, 2026, 10, 21, 16, 30))

        assertEquals(at(IST, 2026, 10, 22, 16, 30), next.toInstant())
    }

    @Test
    fun `weekends are skipped, Friday's digest is followed by Monday's`() {
        val fridayEvening = at(IST, 2026, 10, 23, 18, 0)      // Fri 23 Oct 2026
        val saturday = at(IST, 2026, 10, 24, 9, 0)

        val monday = at(IST, 2026, 10, 26, 16, 30)
        assertEquals(monday, DigestMarket.INDIA.nextDigest(fridayEvening).toInstant())
        assertEquals(monday, DigestMarket.INDIA.nextDigest(saturday).toInstant())
    }

    // ── the US digest: an hour after the 4pm New York close ──────────────────

    @Test
    fun `us digest is 5pm New York time`() {
        val next = DigestMarket.US.nextDigest(at(NEW_YORK, 2026, 10, 21, 9, 0))

        assertEquals(at(NEW_YORK, 2026, 10, 21, 17, 0), next.toInstant())
    }

    @Test
    fun `us digest follows daylight saving, so it moves an hour later in India for the winter`() {
        // 21 Oct 2026 is EDT (UTC-4): 5pm = 2:30am IST next day. 4 Nov 2026 is EST (UTC-5): 5pm = 3:30am IST.
        val summer = DigestMarket.US.nextDigest(at(NEW_YORK, 2026, 10, 21, 9, 0)).withZoneSameInstant(IST)
        val winter = DigestMarket.US.nextDigest(at(NEW_YORK, 2026, 11, 4, 9, 0)).withZoneSameInstant(IST)

        assertEquals(2 to 30, summer.hour to summer.minute)
        assertEquals(3 to 30, winter.hour to winter.minute)
    }

    @Test
    fun `us weekend is judged in New York, so Friday's digest still lands on an Indian Saturday`() {
        val fridayAfternoon = at(NEW_YORK, 2026, 10, 23, 12, 0)

        val next = DigestMarket.US.nextDigest(fridayAfternoon)

        assertEquals(at(NEW_YORK, 2026, 10, 23, 17, 0), next.toInstant())
        assertEquals(java.time.DayOfWeek.SATURDAY, next.withZoneSameInstant(IST).dayOfWeek)
        // and after it, the next one is Monday's
        assertEquals(at(NEW_YORK, 2026, 10, 26, 17, 0), DigestMarket.US.nextDigest(next.toInstant()).toInstant())
    }

    @Test
    fun `the next digest is always in the future`() {
        val now = at(IST, 2026, 12, 31, 23, 59)

        DigestMarket.entries.forEach { assertTrue(it.nextDigest(now).toInstant().isAfter(now)) }
    }

    // ── what Settings shows ──────────────────────────────────────────────────

    @Test
    fun `settings shows the digest times as they fall on the phone`() {
        val now = at(IST, 2026, 10, 21, 10, 0)

        assertEquals("4:30 PM", DigestMarket.INDIA.nextDigestTimeLabel(now, IST, Locale.US))
        assertEquals("2:30 AM", DigestMarket.US.nextDigestTimeLabel(now, IST, Locale.US))
    }

    // ── holidays ─────────────────────────────────────────────────────────────

    @Test
    fun `a session that ended an hour ago is today's`() {
        assertTrue(marketTradedToday(sessionEndSec = 1_000_000L, nowSec = 1_000_000L + 3_600L))
    }

    @Test
    fun `a session that ended yesterday means the market did not trade today`() {
        assertFalse(marketTradedToday(sessionEndSec = 1_000_000L, nowSec = 1_000_000L + 24 * 3_600L))
    }

    @Test
    fun `an unknown or upcoming session end does not stop the digest`() {
        assertTrue(marketTradedToday(sessionEndSec = 0L, nowSec = 1_000_000L))
        assertTrue(marketTradedToday(sessionEndSec = 1_050_000L, nowSec = 1_000_000L))
    }

    // ── keys ─────────────────────────────────────────────────────────────────

    @Test
    fun `each market has its own work name and is found by its key`() {
        assertEquals(DigestMarket.entries.size, DigestMarket.entries.map { it.workName }.toSet().size)
        DigestMarket.entries.forEach { assertEquals(it, DigestMarket.fromKey(it.key)) }
        assertEquals(null, DigestMarket.fromKey("mars"))
        assertEquals(null, DigestMarket.fromKey(null))
    }
}
