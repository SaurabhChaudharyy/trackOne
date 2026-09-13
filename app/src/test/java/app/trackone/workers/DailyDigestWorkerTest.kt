package app.trackone.workers

import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

private fun asset(name: String, buyPrice: Double, quantity: Double, currentValue: Double) =
    NetWorthAssetEntity(
        name = name,
        assetType = AssetType.STOCK_IN,
        quantity = quantity,
        buyPrice = buyPrice,
        currentValue = currentValue
    )

class DailyDigestWorkerTest {

    // ── computeDigestContent ─────────────────────────────────────────────────

    @Test
    fun `single asset has a best but no worst`() {
        val content = computeDigestContent(listOf(asset("INFY", 100.0, 1.0, 150.0)))

        assertEquals("INFY", content.best?.first)
        assertNull(content.worst)
    }

    @Test
    fun `two assets are ranked into best and worst by pct change`() {
        val winner = asset("WINNER", 100.0, 1.0, 200.0)  // +100%
        val loser = asset("LOSER", 100.0, 1.0, 50.0)      // -50%

        val content = computeDigestContent(listOf(winner, loser))

        assertEquals("WINNER", content.best?.first)
        assertEquals("LOSER", content.worst?.first)
    }

    @Test
    fun `assets with a blank name are excluded from ranking`() {
        val blank = asset("", 100.0, 1.0, 500.0)
        val named = asset("ONLY", 100.0, 1.0, 150.0)

        val content = computeDigestContent(listOf(blank, named))

        assertEquals("ONLY", content.best?.first)
        assertNull(content.worst)
    }

    @Test
    fun `portfolio pct change matches the overall PortfolioGainLoss calculation`() {
        val content = computeDigestContent(
            listOf(asset("A", 100.0, 1.0, 150.0), asset("B", 100.0, 1.0, 50.0))
        )
        // invested 200, current 200 -> 0% overall even though individual holdings moved
        assertEquals(0.0, content.portfolioPctChange, 0.0001)
    }

    @Test
    fun `empty asset list produces no best or worst`() {
        val content = computeDigestContent(emptyList())

        assertNull(content.best)
        assertNull(content.worst)
        assertEquals(0.0, content.portfolioPctChange, 0.0)
    }

    // ── millisUntilNextDigestTime ────────────────────────────────────────────

    @Test
    fun `before digest time today schedules for later today`() {
        val now = LocalDateTime.of(2026, 9, 13, 10, 0)
        val delay = DailyDigestWorker.millisUntilNextDigestTime(now)

        val expected = java.time.Duration.between(now, LocalDateTime.of(2026, 9, 13, 20, 30)).toMillis()
        assertEquals(expected, delay)
    }

    @Test
    fun `after digest time today schedules for tomorrow`() {
        val now = LocalDateTime.of(2026, 9, 13, 21, 0)
        val delay = DailyDigestWorker.millisUntilNextDigestTime(now)

        val expected = java.time.Duration.between(now, LocalDateTime.of(2026, 9, 14, 20, 30)).toMillis()
        assertEquals(expected, delay)
    }

    @Test
    fun `exactly at digest time rolls to tomorrow, not fires immediately`() {
        val now = LocalDateTime.of(2026, 9, 13, 20, 30)
        val delay = DailyDigestWorker.millisUntilNextDigestTime(now)

        val expected = java.time.Duration.between(now, LocalDateTime.of(2026, 9, 14, 20, 30)).toMillis()
        assertEquals(expected, delay)
    }

    @Test
    fun `delay is always positive`() {
        val delay = DailyDigestWorker.millisUntilNextDigestTime(LocalDateTime.of(2026, 12, 31, 23, 59))
        assertTrue(delay > 0)
    }
}
