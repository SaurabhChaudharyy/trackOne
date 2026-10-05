package app.trackone.data.repository

import app.trackone.data.api.RequestLimits
import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.PriceHistoryDao
import app.trackone.data.database.StockDao
import app.trackone.data.database.WatchlistDao
import app.trackone.data.database.WatchlistEntity
import app.trackone.data.database.WatchlistGroupDao
import app.trackone.data.model.YahooChart
import app.trackone.data.model.YahooChartResponse
import app.trackone.data.model.YahooChartResult
import app.trackone.data.model.YahooMeta
import app.trackone.utils.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

class StockRepositoryRefreshTest {

    private lateinit var api: YahooFinanceApiService
    private lateinit var stockDao: StockDao
    private lateinit var watchlistDao: WatchlistDao
    private lateinit var repo: StockRepository

    @Before
    fun setUp() {
        api = mockk()
        stockDao = mockk(relaxed = true)
        watchlistDao = mockk(relaxed = true)
        coEvery { stockDao.getStock(any()) } returns null
        coEvery { watchlistDao.isInWatchlist(any()) } returns 1
        repo = StockRepository(api, stockDao, mockk<WatchlistGroupDao>(relaxed = true), watchlistDao, mockk<PriceHistoryDao>(relaxed = true))
    }

    private fun quote(price: Double): Response<YahooChartResponse> = Response.success(
        YahooChartResponse(
            chart = YahooChart(
                result = listOf(
                    YahooChartResult(
                        meta = YahooMeta(regularMarketPrice = price, previousClose = price),
                        timestamps = null,
                        indicators = null
                    )
                ),
                error = null
            )
        )
    )

    private fun watching(vararg symbols: String, group: Long = 1L) =
        symbols.mapIndexed { i, s -> WatchlistEntity(symbol = s, displayName = s, position = i, groupId = group) }

    @Test
    fun `a long watchlist is refreshed in parallel but never more than the shared limit at once`() = runTest {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        coEvery { watchlistDao.getWatchlistSync() } returns watching(*(1..20).map { "S$it" }.toTypedArray())
        coEvery { api.getQuote(any(), any(), any(), any()) } coAnswers {
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(40)
            inFlight.decrementAndGet()
            quote(10.0)
        }

        val result = repo.refreshWatchlistStocks()

        assertTrue(result is Resource.Success)
        assertTrue("never in parallel (peak ${peak.get()})", peak.get() > 1)
        assertTrue("more than ${RequestLimits.MAX_PARALLEL} at once (peak ${peak.get()})", peak.get() <= RequestLimits.MAX_PARALLEL)
    }

    @Test
    fun `a symbol that is in several watchlists is fetched once`() = runTest {
        coEvery { watchlistDao.getWatchlistSync() } returns
            watching("AAPL", "TCS.NS", group = 1L) + watching("AAPL", group = 2L)
        coEvery { api.getQuote(any(), any(), any(), any()) } returns quote(10.0)

        repo.refreshWatchlistStocks()

        coVerify(exactly = 1) { api.getQuote("AAPL", any(), any(), any()) }
        coVerify(exactly = 1) { api.getQuote("TCS.NS", any(), any(), any()) }
    }

    @Test
    fun `symbols that fail are reported by name and the others are still refreshed`() = runTest {
        coEvery { watchlistDao.getWatchlistSync() } returns watching("OK1", "BAD", "OK2")
        coEvery { api.getQuote("OK1", any(), any(), any()) } returns quote(1.0)
        coEvery { api.getQuote("OK2", any(), any(), any()) } returns quote(2.0)
        coEvery { api.getQuote("BAD", any(), any(), any()) } returns Response.error(404, "".toResponseBody(null))

        val result = repo.refreshWatchlistStocks()

        assertTrue(result is Resource.Error)
        assertTrue((result as Resource.Error).message!!.contains("BAD"))
        assertEquals(false, result.message!!.contains("OK1"))
        coVerify(exactly = 1) { stockDao.insertStock(match { it.symbol == "OK1" }) }
        coVerify(exactly = 1) { stockDao.insertStock(match { it.symbol == "OK2" }) }
    }
}
