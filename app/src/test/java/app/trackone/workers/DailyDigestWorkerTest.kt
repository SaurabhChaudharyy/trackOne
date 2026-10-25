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

    // ── formatWeeklyMessage ─────────────────────────────────────────────────────

    private fun body(vararg assets: NetWorthAssetEntity) = formatWeeklyMessage(computeDigestContent(assets.toList())).body

    @Test
    fun `digest leads with the rupee amount and pct of total return`() {
        // invested 100000, current 155930 -> +55,930 (+55.93%)
        val text = body(asset("A", 1000.0, 100.0, 155930.0))

        assertTrue(text, text.startsWith("Total return \u2197 +55.93% (+₹55,930)"))
    }

    @Test
    fun `lakh and crore amounts are shown compactly`() {
        assertTrue(body(asset("A", 1000.0, 1000.0, 1420000.0)).contains("+₹4.20L"))
        assertTrue(body(asset("A", 1000.0, 1000.0, 21000000.0)).contains("+₹2.00Cr"))
    }

    @Test
    fun `a loss carries the sign before the rupee symbol`() {
        val text = body(asset("A", 1000.0, 100.0, 90000.0))

        assertTrue(text, text.startsWith("Total return \u2198 -10.00% (-₹10,000)"))
    }

    @Test
    fun `best and worst percentages use thousands separators, dropping decimals when huge`() {
        val text = body(
            asset("MOON", 1.0, 1.0, 249.2387),   // +24823.87%
            asset("DUD", 100.0, 1.0, 26.51)      // -73.49%
        )

        assertTrue(text, text.contains("Best: MOON \u2197 +24,824%"))
        assertTrue(text, text.contains("Worst: DUD \u2198 -73.49%"))
    }

    @Test
    fun `single holding produces no worst clause`() {
        val text = body(asset("ONLY", 100.0, 1.0, 150.0))

        assertTrue(text, text.contains("Best: ONLY \u2197 +50.00%"))
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

    private val up = "\u2197"
    private val down = "\u2198"

    @Test
    fun `market digest leads with the portfolio, then the indices on one line, then best and worst`() {
        val assets = listOf(
            asset("UP", 1.0, 100.0, 0.0, id = 1),
            asset("DOWN", 1.0, 100.0, 0.0, id = 2)
        )
        val content = computeDailyDigest(
            assets,
            mapOf(1L to LiveQuote(103.0, 100.0), 2L to LiveQuote(98.0, 100.0))
        )!!

        val message = formatMarketDigest(
            DigestMarket.INDIA,
            listOf(IndexMove("Nifty 50", -0.95), IndexMove("Sensex", 0.12)),
            content
        )

        assertEquals("Indian market closed", message.title)
        assertEquals(
            listOf(
                "Your Indian stocks $up +0.50% (+₹100)",
                "Nifty 50 $down -0.95%  \u00b7  Sensex $up +0.12%",
                "Top: UP $up +3.00%",
                "Lagging: DOWN $down -2.00%"
            ),
            message.lines
        )
    }

    @Test
    fun `market digest without holdings is just the indices`() {
        val message = formatMarketDigest(DigestMarket.US, listOf(IndexMove("S&P 500", 1.5)), null)

        assertEquals("US market closed", message.title)
        assertEquals(listOf("S&P 500 $up +1.50%"), message.lines)
    }

    @Test
    fun `expanded body has a line per finding and the collapsed summary runs them together`() {
        val content = DailyDigestContent(totalAbsChange = 50.0, totalPctChange = 1.0, best = null, worst = null)
        val message = formatMarketDigest(DigestMarket.US, listOf(IndexMove("S&P 500", 1.5)), content)

        assertEquals("Your US stocks $up +1.00% (+₹50)\nS&P 500 $up +1.50%", message.body)
        assertEquals("Your US stocks $up +1.00% (+₹50)  \u00b7  S&P 500 $up +1.50%", message.summary)
    }

    @Test
    fun `moves use the app's arrows and signs, and a move that rounds to zero is flat`() {
        assertEquals("$up +9.08%", movePct(9.08))
        assertEquals("$down -10.40%", movePct(-10.4))
        assertEquals("\u2192 +0.00%", movePct(0.0))
        assertEquals("\u2192 +0.00%", movePct(-0.004))
        assertEquals("$up +12,346%", movePct(12_345.6))
    }

    @Test
    fun `each move is found for colouring, with its amount, and takes its arrow's direction`() {
        val line = "Your Indian stocks $down -0.95% (-₹5,816)"
        val span = moveSpans(line).single()

        assertEquals("$down -0.95% (-₹5,816)", line.substring(span.start, span.end))
        assertEquals(MoveDirection.LOSS, span.direction)
    }

    @Test
    fun `several moves on one line are each found`() {
        val line = "Nifty 50 $down -0.39%  \u00b7  Sensex $up +0.12%"
        val spans = moveSpans(line)

        assertEquals(listOf("$down -0.39%", "$up +0.12%"), spans.map { line.substring(it.start, it.end) })
        assertEquals(listOf(MoveDirection.LOSS, MoveDirection.GAIN), spans.map { it.direction })
    }

    private fun bolded(text: String) = subjectSpans(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun `what each move is about is bold, and the Top and Lagging labels are not`() {
        assertEquals(listOf("Your Indian stocks"), bolded("Your Indian stocks $down -0.95% (-₹5,816)"))
        assertEquals(listOf("APOLLOHOSP"), bolded("Top: APOLLOHOSP $up +1.65%"))
        assertEquals(listOf("SWIGGY"), bolded("Lagging: SWIGGY $down -16.75%"))
        assertEquals(listOf("MOON"), bolded("Best: MOON $up +24,824%"))
        assertEquals(listOf("Total return"), bolded("Total return $up +55.93% (+₹55,930)"))
    }

    @Test
    fun `each index on a shared line is bold, and so is each subject in the collapsed summary`() {
        assertEquals(listOf("Nifty 50", "Sensex"), bolded("Nifty 50 $down -0.39%  \u00b7  Sensex $up +0.12%"))

        val content = DailyDigestContent(totalAbsChange = 50.0, totalPctChange = 1.0, best = DayMove("UP", 1.0, 3.0), worst = null)
        val message = formatMarketDigest(DigestMarket.US, listOf(IndexMove("S&P 500", 1.5)), content)

        assertEquals(listOf("Your US stocks", "S&P 500", "UP"), bolded(message.summary))
        assertEquals(listOf("Your US stocks", "S&P 500", "UP"), bolded(message.body))
    }

    @Test
    fun `a line with no move has nothing in bold`() {
        assertTrue(subjectSpans("Indian market closed").isEmpty())
    }

    @Test
    fun `a flat day is not coloured as a gain for its plus sign`() {
        val line = "Your US stocks \u2192 +0.00% (+₹0)"

        assertEquals(MoveDirection.FLAT, moveSpans(line).single().direction)
    }

    @Test
    fun `text without a move has nothing to colour, and every digest line's move is found`() {
        assertTrue(moveSpans("Indian market closed").isEmpty())

        val content = computeDailyDigest(
            listOf(asset("UP", 1.0, 100.0, 0.0, id = 1)),
            mapOf(1L to LiveQuote(103.0, 100.0))
        )!!
        val message = formatMarketDigest(DigestMarket.INDIA, listOf(IndexMove("Nifty 50", -1.0)), content)
        message.lines.forEach { assertTrue(it, moveSpans(it).isNotEmpty()) }
    }

    @Test
    fun `a flat day reads as zero, not as a missing value`() {
        val content = computeDailyDigest(
            listOf(asset("A", 1.0, 5.0, 0.0, id = 1)),
            mapOf(1L to LiveQuote(100.0, 100.0))
        )!!

        assertTrue(formatMarketDigest(DigestMarket.INDIA, emptyList(), content).lines.first().startsWith("Your Indian stocks \u2192 +0.00%"))
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
}
