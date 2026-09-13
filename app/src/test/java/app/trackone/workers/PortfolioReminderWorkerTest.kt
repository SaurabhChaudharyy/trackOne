package app.trackone.workers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class PortfolioReminderWorkerTest {

    // ── nextReminderDateTimeOnOrAfter ────────────────────────────────────────

    @Test
    fun `from the 1st, next reminder is the 1st itself`() {
        val next = PortfolioReminderWorker.nextReminderDateTimeOnOrAfter(LocalDate.of(2026, 9, 1))
        assertEquals(LocalDateTime.of(2026, 9, 1, 10, 0), next)
    }

    @Test
    fun `from the 5th, next reminder is the 15th`() {
        val next = PortfolioReminderWorker.nextReminderDateTimeOnOrAfter(LocalDate.of(2026, 9, 5))
        assertEquals(LocalDateTime.of(2026, 9, 15, 10, 0), next)
    }

    @Test
    fun `from the 16th, next reminder rolls into next month's 1st`() {
        val next = PortfolioReminderWorker.nextReminderDateTimeOnOrAfter(LocalDate.of(2026, 9, 16))
        assertEquals(LocalDateTime.of(2026, 10, 1, 10, 0), next)
    }

    @Test
    fun `December 16th rolls into January 1st of the following year`() {
        val next = PortfolioReminderWorker.nextReminderDateTimeOnOrAfter(LocalDate.of(2026, 12, 16))
        assertEquals(LocalDateTime.of(2027, 1, 1, 10, 0), next)
    }

    @Test
    fun `from the 15th, next reminder is the 15th itself`() {
        val next = PortfolioReminderWorker.nextReminderDateTimeOnOrAfter(LocalDate.of(2026, 9, 15))
        assertEquals(LocalDateTime.of(2026, 9, 15, 10, 0), next)
    }

    // ── millisUntilNextReminderTime ──────────────────────────────────────────

    @Test
    fun `before 10am on the 1st schedules for later that day`() {
        val now = LocalDateTime.of(2026, 9, 1, 8, 0)
        val delay = PortfolioReminderWorker.millisUntilNextReminderTime(now)

        val expected = Duration.between(now, LocalDateTime.of(2026, 9, 1, 10, 0)).toMillis()
        assertEquals(expected, delay)
    }

    @Test
    fun `after 10am on the 1st schedules for the 15th`() {
        val now = LocalDateTime.of(2026, 9, 1, 11, 0)
        val delay = PortfolioReminderWorker.millisUntilNextReminderTime(now)

        val expected = Duration.between(now, LocalDateTime.of(2026, 9, 15, 10, 0)).toMillis()
        assertEquals(expected, delay)
    }

    @Test
    fun `after 10am on the 15th schedules for next month's 1st`() {
        val now = LocalDateTime.of(2026, 9, 15, 11, 0)
        val delay = PortfolioReminderWorker.millisUntilNextReminderTime(now)

        val expected = Duration.between(now, LocalDateTime.of(2026, 10, 1, 10, 0)).toMillis()
        assertEquals(expected, delay)
    }

    @Test
    fun `mid-month, not the 1st or 15th, schedules for the 15th`() {
        val now = LocalDateTime.of(2026, 9, 8, 12, 0)
        val delay = PortfolioReminderWorker.millisUntilNextReminderTime(now)

        val expected = Duration.between(now, LocalDateTime.of(2026, 9, 15, 10, 0)).toMillis()
        assertEquals(expected, delay)
    }

    @Test
    fun `exactly at 10am on a reminder day rolls to the next occurrence, not fires immediately`() {
        val now = LocalDateTime.of(2026, 9, 1, 10, 0)
        val delay = PortfolioReminderWorker.millisUntilNextReminderTime(now)

        val expected = Duration.between(now, LocalDateTime.of(2026, 9, 15, 10, 0)).toMillis()
        assertEquals(expected, delay)
    }

    @Test
    fun `delay is always positive`() {
        val delay = PortfolioReminderWorker.millisUntilNextReminderTime(LocalDateTime.of(2026, 12, 31, 23, 59))
        assertTrue(delay > 0)
    }
}
