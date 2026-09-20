package app.trackone.workers

import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.repository.NetWorthRepository.LiveQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

private fun asset(name: String, buyPrice: Double, quantity: Double, currentValue: Double, id: Long = 0) =
    NetWorthAssetEntity(
        id = id,
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

    @Test
    fun `cash and bank balances are never ranked as best or worst performers`() {
        // Real data: a bank row with a placeholder buyPrice ranked as "+12,499,900%".
        fun typed(name: String, type: AssetType, buy: Double, value: Double) =
            NetWorthAssetEntity(name = name, assetType = type, quantity = 1.0, buyPrice = buy, currentValue = value)

        val content = computeDigestContent(
            listOf(
                typed("HDFC Savings", AssetType.BANK, 1.0, 125_000.0),
                typed("Cash", AssetType.CASH, 1.0, 50_000.0),
                typed("INFY", AssetType.STOCK_IN, 100.0, 150.0)
            )
        )

        assertEquals("INFY", content.best?.first)
        assertNull(content.worst)   // only one rankable holding
    }

    // ── formatWeeklyBody ─────────────────────────────────────────────────────

    private fun body(vararg assets: NetWorthAssetEntity) = formatWeeklyBody(computeDigestContent(assets.toList()))

    @Test
    fun `digest leads with the rupee amount and pct of total return`() {
        // invested 100000, current 155930 -> +55,930 (+55.93%)
        val text = body(asset("A", 1000.0, 100.0, 155930.0))

        assertTrue(text, text.startsWith("Total return +₹55,930 (+55.93%)."))
    }

    @Test
    fun `lakh and crore amounts are shown compactly`() {
        assertTrue(body(asset("A", 1000.0, 1000.0, 1420000.0)).contains("+₹4.20L"))
        assertTrue(body(asset("A", 1000.0, 1000.0, 21000000.0)).contains("+₹2.00Cr"))
    }

    @Test
    fun `a loss carries the sign before the rupee symbol`() {
        val text = body(asset("A", 1000.0, 100.0, 90000.0))

        assertTrue(text, text.startsWith("Total return -₹10,000 (-10.00%)."))
    }

    @Test
    fun `best and worst percentages use thousands separators, dropping decimals when huge`() {
        val text = body(
            asset("MOON", 1.0, 1.0, 249.2387),   // +24823.87%
            asset("DUD", 100.0, 1.0, 26.51)      // -73.49%
        )

        assertTrue(text, text.contains("Best: MOON +24,824%."))
        assertTrue(text, text.contains("Worst: DUD -73.49%."))
    }

    @Test
    fun `single holding produces no worst clause`() {
        val text = body(asset("ONLY", 100.0, 1.0, 150.0))

        assertTrue(text, text.contains("Best: ONLY +50.00%."))
        assertTrue(text, !text.contains("Worst"))
    }

    // ── daily: today's move ──────────────────────────────────────────────────

    @Test
    fun `daily move is measured against the previous close, not the buy price`() {
        // 10 shares, closed yesterday at 100, now 103 -> +30 (+3.00%), whatever was paid for them
        val assets = listOf(asset("INFY", buyPrice = 5.0, quantity = 10.0, currentValue = 1030.0, id = 1))
        val content = computeDailyDigest(assets, mapOf(1L to LiveQuote(priceInr = 103.0, previousCloseInr = 100.0)))!!

        assertEquals(30.0, content.totalAbsChange, 0.0001)
        assertEquals(3.0, content.totalPctChange, 0.0001)
    }

    @Test
    fun `daily percentage is weighted by position size across holdings`() {
        val assets = listOf(
            asset("BIG", 1.0, 10.0, 0.0, id = 1),   // 1000 -> 1010: +10
            asset("SMALL", 1.0, 1.0, 0.0, id = 2)   // 100  -> 90:   -10
        )
        val content = computeDailyDigest(
            assets,
            mapOf(1L to LiveQuote(101.0, 100.0), 2L to LiveQuote(90.0, 100.0))
        )!!

        assertEquals(0.0, content.totalAbsChange, 0.0001)
        assertEquals("BIG", content.best?.name)
        assertEquals("SMALL", content.worst?.name)
        assertEquals(-10.0, content.worst!!.pctChange, 0.0001)
    }

    @Test
    fun `holdings without a usable quote are left out, not counted as unchanged`() {
        val assets = listOf(
            asset("HAS", 1.0, 1.0, 0.0, id = 1),
            asset("FAILED", 1.0, 1.0, 0.0, id = 2),        // quote fetch failed -> absent
            asset("NOPREV", 1.0, 1.0, 0.0, id = 3),        // quote but no previous close
            asset("FUND", 1.0, 1.0, 5000.0, id = 4)        // MF: never has a quote
        )
        val content = computeDailyDigest(
            assets,
            mapOf(1L to LiveQuote(110.0, 100.0), 3L to LiveQuote(50.0, 0.0))
        )!!

        // only HAS counts: +10 on 100 = +10%, not diluted by the other three
        assertEquals(10.0, content.totalPctChange, 0.0001)
        assertNull(content.worst)
    }

    @Test
    fun `daily digest is null when nothing could be measured`() {
        val assets = listOf(asset("A", 1.0, 1.0, 100.0, id = 1))

        assertNull(computeDailyDigest(assets, emptyMap()))
        assertNull(computeDailyDigest(assets, mapOf(1L to LiveQuote(100.0, 0.0))))
        assertNull(computeDailyDigest(emptyList(), emptyMap()))
    }

    @Test
    fun `daily body reads as today's move with top and lagging holdings`() {
        val assets = listOf(
            asset("UP", 1.0, 100.0, 0.0, id = 1),
            asset("DOWN", 1.0, 100.0, 0.0, id = 2)
        )
        val content = computeDailyDigest(
            assets,
            mapOf(1L to LiveQuote(103.0, 100.0), 2L to LiveQuote(98.0, 100.0))
        )!!

        assertEquals(
            "Today +₹100 (+0.50%). Top: UP +3.00%. Lagging: DOWN -2.00%.",
            formatDailyBody(content)
        )
    }

    @Test
    fun `a flat day reads as zero, not as a missing value`() {
        val content = computeDailyDigest(
            listOf(asset("A", 1.0, 5.0, 0.0, id = 1)),
            mapOf(1L to LiveQuote(100.0, 100.0))
        )!!

        assertTrue(formatDailyBody(content).startsWith("Today +₹0 (+0.00%)."))
    }

    // ── weekly schedule ──────────────────────────────────────────────────────

    @Test
    fun `weekly summary fires on the coming Sunday at 10am`() {
        val wednesday = LocalDateTime.of(2026, 9, 16, 12, 0)   // Wed 16 Sep 2026
        val delay = WeeklySummaryWorker.millisUntilNextSummaryTime(wednesday)

        val expected = java.time.Duration.between(wednesday, LocalDateTime.of(2026, 9, 20, 10, 0)).toMillis()
        assertEquals(expected, delay)
    }

    @Test
    fun `on Sunday before 10am the summary fires today`() {
        val now = LocalDateTime.of(2026, 9, 20, 8, 0)
        val expected = java.time.Duration.between(now, LocalDateTime.of(2026, 9, 20, 10, 0)).toMillis()

        assertEquals(expected, WeeklySummaryWorker.millisUntilNextSummaryTime(now))
    }

    @Test
    fun `on Sunday after 10am the summary rolls to next week`() {
        val now = LocalDateTime.of(2026, 9, 20, 11, 0)
        val expected = java.time.Duration.between(now, LocalDateTime.of(2026, 9, 27, 10, 0)).toMillis()

        assertEquals(expected, WeeklySummaryWorker.millisUntilNextSummaryTime(now))
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
