package app.trackone.data.repository

import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.model.YahooChart
import app.trackone.data.model.YahooChartResponse
import app.trackone.data.model.YahooChartResult
import app.trackone.data.model.YahooIndicators
import app.trackone.data.model.YahooMeta
import app.trackone.data.model.YahooQuote
import app.trackone.utils.ChartRange
import app.trackone.utils.PortfolioHistory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class PortfolioHistoryRepositoryTest {

    private lateinit var api: YahooFinanceApiService
    private lateinit var netWorth: NetWorthRepository
    private lateinit var repo: PortfolioHistoryRepository

    // Three consecutive IST days, expressed as the epoch seconds of a bar on each.
    private val day1 = 1_790_000_000L
    private val day2 = day1 + 86_400
    private val day3 = day2 + 86_400
    private val now = day3 * 1000 + 3_600_000        // an hour into day 3 (ms)

    @Before
    fun setUp() {
        api = mockk()
        netWorth = mockk()
        coEvery { netWorth.fetchUsdInrRate() } returns 80.0
        coEvery { netWorth.fetchRateToInr("USD") } returns 80.0
        repo = PortfolioHistoryRepository(api, netWorth)
    }

    private fun chart(currency: String, times: List<Long>, closes: List<Double?>): Response<YahooChartResponse> =
        Response.success(
            YahooChartResponse(
                chart = YahooChart(
                    result = listOf(
                        YahooChartResult(
                            meta = YahooMeta(currency = currency),
                            timestamps = times,
                            indicators = YahooIndicators(listOf(YahooQuote(null, null, null, closes, null)))
                        )
                    ),
                    error = null
                )
            )
        )

    private fun stub(symbol: String, response: Response<YahooChartResponse>) {
        coEvery { api.getChartData(symbol, "1d", "1mo", any()) } returns response
    }

    private fun failure(): Response<YahooChartResponse> = Response.error(404, "".toResponseBody(null))

    private fun asset(name: String, type: AssetType, qty: Double, value: Double, buy: Double = 0.0, id: Long = 0) =
        NetWorthAssetEntity(id = id, name = name, assetType = type, quantity = qty, buyPrice = buy, currentValue = value)

    @Test
    fun `an INR stock is valued at quantity times each day's close, plus flat cash`() = runTest {
        stub("USDINR=X", failure())
        stub("TCS.NS", chart("INR", listOf(day1, day2, day3), listOf(100.0, 110.0, 120.0)))

        val points = repo.history(
            listOf(
                asset("TCS", AssetType.STOCK_IN, qty = 10.0, value = 1300.0),
                asset("Savings", AssetType.BANK, qty = 1.0, value = 5000.0)
            ),
            ChartRange.MONTH, now
        )

        // 10 x close + 5000 flat; the last point is replaced by the live total (1300 + 5000).
        assertEquals(listOf(6000.0, 6100.0, 6300.0), points.map { it.current })
    }

    @Test
    fun `a USD stock is converted with that day's USD to INR rate`() = runTest {
        stub("USDINR=X", chart("INR", listOf(day1, day2), listOf(80.0, 90.0)))
        stub("AAPL", chart("USD", listOf(day1, day2), listOf(100.0, 100.0)))

        val points = repo.history(listOf(asset("AAPL", AssetType.STOCK_US, qty = 1.0, value = 9000.0)), ChartRange.MONTH, now)

        // day1: 100 USD x 80 = 8000; day2: 100 x 90 = 9000; then the live point for day 3
        assertEquals(listOf(8000.0, 9000.0, 9000.0), points.map { it.current })
    }

    @Test
    fun `a pence-quoted stock is converted with that day's GBP to INR rate`() = runTest {
        stub("USDINR=X", failure())
        stub("TSCO.L", chart("GBp", listOf(day1, day2), listOf(500.0, 500.0)))
        stub("GBPINR=X", chart("INR", listOf(day1, day2), listOf(100.0, 110.0)))

        val points = repo.history(listOf(asset("TSCO.L", AssetType.STOCK_US, qty = 1.0, value = 560.0)), ChartRange.MONTH, now)

        // 500p = £5; day1 x 100 = 500, day2 x 110 = 550; then the live point
        assertEquals(listOf(500.0, 550.0, 560.0), points.map { it.current })
    }

    @Test
    fun `a foreign-currency holding with no rate at all stays flat instead of counting pence as rupees`() = runTest {
        stub("USDINR=X", failure())
        stub("TCS.NS", chart("INR", listOf(day1, day2), listOf(100.0, 200.0)))
        stub("TSCO.L", chart("GBp", listOf(day1, day2), listOf(500.0, 900.0)))
        stub("GBPINR=X", failure())
        coEvery { netWorth.fetchRateToInr("GBP") } returns null

        val points = repo.history(
            listOf(
                asset("TCS", AssetType.STOCK_IN, qty = 1.0, value = 200.0),
                asset("TSCO.L", AssetType.STOCK_US, qty = 1.0, value = 560.0)
            ),
            ChartRange.MONTH, now
        )

        assertEquals(listOf(660.0, 760.0, 760.0), points.map { it.current })
    }

    @Test
    fun `a holding whose lookup fails stays flat instead of inventing a price`() = runTest {
        stub("USDINR=X", failure())
        stub("TCS.NS", chart("INR", listOf(day1, day2), listOf(100.0, 200.0)))
        stub("RELIANCE INDUSTRIES.NS", failure())    // a company NAME, not a ticker

        val points = repo.history(
            listOf(
                asset("TCS", AssetType.STOCK_IN, qty = 1.0, value = 200.0),
                asset("Reliance Industries", AssetType.STOCK_IN, qty = 1.0, value = 3000.0)
            ),
            ChartRange.MONTH, now
        )

        assertEquals(listOf(3100.0, 3200.0, 3200.0), points.map { it.current })
    }

    @Test
    fun `when no holding has any price history there is no chart at all`() = runTest {
        stub("USDINR=X", failure())
        stub("BAD.NS", failure())

        assertTrue(repo.history(listOf(asset("BAD", AssetType.STOCK_IN, 1.0, 100.0), asset("Cash", AssetType.CASH, 1.0, 500.0)), ChartRange.MONTH, now).isEmpty())
        assertTrue(repo.history(listOf(asset("Cash", AssetType.CASH, 1.0, 500.0)), ChartRange.MONTH, now).isEmpty())
    }

    @Test
    fun `the last point sits on today so the chart ends on the header number`() = runTest {
        stub("USDINR=X", failure())
        stub("TCS.NS", chart("INR", listOf(day1, day2), listOf(100.0, 110.0)))   // no bar for day 3 yet

        val points = repo.history(listOf(asset("TCS", AssetType.STOCK_IN, 1.0, 999.0)), ChartRange.MONTH, now)

        assertEquals(PortfolioHistory.epochDayIstToMillis(PortfolioHistory.epochDayIst(day3)), points.last().timestamp)
        assertEquals(999.0, points.last().current, 0.0)
    }

    @Test
    fun `a second build reuses the cached series instead of hitting the network again`() = runTest {
        stub("USDINR=X", failure())
        stub("TCS.NS", chart("INR", listOf(day1, day2), listOf(100.0, 110.0)))
        val holdings = listOf(asset("TCS", AssetType.STOCK_IN, 1.0, 110.0))

        repo.history(holdings, ChartRange.MONTH, now)
        repo.history(holdings.map { it.copy(currentValue = 111.0) }, ChartRange.MONTH, now + 60_000)   // a price refresh, 1 min later

        coVerify(exactly = 1) { api.getChartData("TCS.NS", "1d", "1mo", any()) }
    }

    @Test
    fun `null closes and zero prices in the feed are skipped`() = runTest {
        stub("USDINR=X", failure())
        stub("TCS.NS", chart("INR", listOf(day1, day2, day3), listOf(100.0, null, 0.0)))

        val points = repo.history(listOf(asset("TCS", AssetType.STOCK_IN, 1.0, 100.0)), ChartRange.MONTH, now)

        assertEquals(listOf(100.0, 100.0), points.map { it.current })   // day1 close, then the live point for day 3
    }

    private fun stub(symbol: String, interval: String, range: String, response: Response<YahooChartResponse>) {
        coEvery { api.getChartData(symbol, interval, range, any()) } returns response
    }

    @Test
    fun `1D keeps every intraday bar instead of collapsing them into one day`() = runTest {
        val open = day3
        val bars = listOf(open, open + 300, open + 600)          // three 5-minute bars, same IST day
        stub("USDINR=X", "5m", "1d", failure())
        stub("TCS.NS", "5m", "1d", chart("INR", bars, listOf(100.0, 102.0, 101.0)))

        val points = repo.history(listOf(asset("TCS", AssetType.STOCK_IN, 1.0, 101.0)), ChartRange.DAY, now)

        assertEquals(listOf(100.0, 102.0, 101.0), points.map { it.current })
        assertEquals(bars.map { it * 1000 }, points.map { it.timestamp })
    }

    @Test
    fun `1D ends on the live total without adding a point after the last bar`() = runTest {
        val bars = listOf(day1, day1 + 300)                       // a session two days before now
        stub("USDINR=X", "5m", "1d", failure())
        stub("TCS.NS", "5m", "1d", chart("INR", bars, listOf(100.0, 102.0)))

        val points = repo.history(listOf(asset("TCS", AssetType.STOCK_IN, 1.0, 105.0)), ChartRange.DAY, now)

        assertEquals(listOf(100.0, 105.0), points.map { it.current })
        assertEquals((day1 + 300) * 1000, points.last().timestamp)
    }

    @Test
    fun `1D carries a closed market's last price across the other market's bars`() = runTest {
        val indiaBars = listOf(day3, day3 + 300)                  // India trades first
        val usBars = listOf(day3 + 600, day3 + 900)               // then the US opens
        stub("USDINR=X", "5m", "1d", failure())
        stub("TCS.NS", "5m", "1d", chart("INR", indiaBars, listOf(100.0, 110.0)))
        stub("AAPL", "5m", "1d", chart("INR", usBars, listOf(200.0, 210.0)))

        val points = repo.history(
            listOf(asset("TCS", AssetType.STOCK_IN, 1.0, 111.0), asset("AAPL", AssetType.STOCK_US, 1.0, 210.0)),
            ChartRange.DAY, now
        )

        // India's 110 is held while the US trades (US is backfilled with its first bar before it
        // opens); the live total (111 + 210) replaces the last bar.
        assertEquals(listOf(300.0, 310.0, 310.0, 321.0), points.map { it.current })
    }

    private fun stubAll(symbol: String, response: Response<YahooChartResponse>) {
        coEvery { api.getChartData(symbol, "1wk", null, any(), 0L, any()) } returns response
    }

    @Test
    fun `ALL asks for weekly bars from an explicit start instead of range max`() = runTest {
        stubAll("USDINR=X", failure())
        stubAll("TCS.NS", chart("INR", listOf(day1, day2), listOf(100.0, 110.0)))

        val points = repo.history(listOf(asset("TCS", AssetType.STOCK_IN, 1.0, 120.0)), ChartRange.ALL, now)

        coVerify(exactly = 1) { api.getChartData("TCS.NS", "1wk", null, any(), 0L, now / 1000) }
        assertEquals(PortfolioHistory.epochDayIstToMillis(PortfolioHistory.epochDayIst(day1)), points.first().timestamp)
        assertEquals(120.0, points.last().current, 0.0)           // live point for today
    }

    @Test
    fun `ALL starts where most of the portfolio has real prices instead of backfilling the rest`() = runTest {
        // BIG (90% of the value) only lists on day 2; SMALL has traded since day 1. Day 1 would be
        // SMALL's real price plus BIG at a made-up first price, so the chart starts on day 2.
        stubAll("USDINR=X", failure())
        stubAll("BIG.NS", chart("INR", listOf(day2, day3), listOf(900.0, 900.0)))
        stubAll("SMALL.NS", chart("INR", listOf(day1, day2, day3), listOf(100.0, 100.0, 100.0)))

        val points = repo.history(
            listOf(asset("BIG", AssetType.STOCK_IN, 1.0, 900.0), asset("SMALL", AssetType.STOCK_IN, 1.0, 100.0)),
            ChartRange.ALL, now
        )

        assertEquals(PortfolioHistory.epochDayIstToMillis(PortfolioHistory.epochDayIst(day2)), points.first().timestamp)
        assertEquals(listOf(1000.0, 1000.0), points.map { it.current })   // day 2 and day 3 only; day 1 is gone
    }

    @Test
    fun `shorter ranges keep every day they were given`() = runTest {
        stub("USDINR=X", failure())
        stub("BIG.NS", chart("INR", listOf(day2, day3), listOf(900.0, 900.0)))
        stub("SMALL.NS", chart("INR", listOf(day1, day2, day3), listOf(100.0, 100.0, 100.0)))

        val points = repo.history(
            listOf(asset("BIG", AssetType.STOCK_IN, 1.0, 900.0), asset("SMALL", AssetType.STOCK_IN, 1.0, 100.0)),
            ChartRange.MONTH, now
        )

        assertEquals(PortfolioHistory.epochDayIstToMillis(PortfolioHistory.epochDayIst(day1)), points.first().timestamp)
    }

    @Test
    fun `1D series are refetched sooner than daily ones`() = runTest {
        stub("USDINR=X", "5m", "1d", failure())
        stub("TCS.NS", "5m", "1d", chart("INR", listOf(day3, day3 + 300), listOf(100.0, 101.0)))
        val holdings = listOf(asset("TCS", AssetType.STOCK_IN, 1.0, 101.0))

        repo.history(holdings, ChartRange.DAY, now)
        repo.history(holdings, ChartRange.DAY, now + 60_000)          // within the intraday window: cached
        repo.history(holdings, ChartRange.DAY, now + 6 * 60_000)      // past it: fetched again

        coVerify(exactly = 2) { api.getChartData("TCS.NS", "5m", "1d", any()) }
    }
}
