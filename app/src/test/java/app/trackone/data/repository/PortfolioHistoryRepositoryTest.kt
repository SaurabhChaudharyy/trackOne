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
}
