package app.trackone.data.repository

import app.trackone.data.api.Freshness
import app.trackone.data.api.RateLimitBackoff
import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.PriceHistoryDao
import app.trackone.data.database.StockDao
import app.trackone.data.database.StockEntity
import app.trackone.data.database.WatchlistDao
import app.trackone.data.database.WatchlistGroupDao
import app.trackone.data.model.YahooChart
import app.trackone.data.model.YahooChartResponse
import app.trackone.data.model.YahooChartResult
import app.trackone.data.model.YahooMeta
import app.trackone.data.model.YahooPeriod
import app.trackone.data.model.YahooTradingPeriods
import app.trackone.utils.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * What a quote fetch skips and what it saves: the saved quote answers when it is fresh enough for the
 * caller (see [Freshness]), and every quote that is fetched keeps Yahoo's session times for later.
 */
class StockRepositoryFreshnessTest {

    private val open = 1_792_122_300L
    private val close = 1_792_144_800L

    private lateinit var api: YahooFinanceApiService
    private lateinit var stockDao: StockDao
    private lateinit var repo: StockRepository
    private val saved = java.util.concurrent.ConcurrentHashMap<String, StockEntity>()

    @Before
    fun setUp() {
        api = mockk()
        stockDao = mockk(relaxed = true)
        coEvery { stockDao.getStock(any()) } coAnswers { saved[firstArg()] }
        coEvery { stockDao.insertStock(any()) } coAnswers { val s = firstArg<StockEntity>(); saved[s.symbol] = s }
        val watchlistDao = mockk<WatchlistDao>(relaxed = true)
        coEvery { watchlistDao.isInWatchlist(any()) } returns 0
        repo = StockRepository(
            api, stockDao, mockk<WatchlistGroupDao>(relaxed = true), watchlistDao,
            mockk<PriceHistoryDao>(relaxed = true), RateLimitBackoff { 0L }
        )
    }

    private fun quote(price: Double, withSession: Boolean = true): Response<YahooChartResponse> = Response.success(
        YahooChartResponse(
            chart = YahooChart(
                result = listOf(
                    YahooChartResult(
                        meta = YahooMeta(
                            symbol = "^NSEI", regularMarketPrice = price, previousClose = price,
                            currentTradingPeriod = if (!withSession) null else YahooTradingPeriods(
                                pre = YahooPeriod(start = open, end = open, gmtoffset = 19_800),
                                regular = YahooPeriod(start = open, end = close, gmtoffset = 19_800),
                                post = YahooPeriod(start = close, end = close, gmtoffset = 19_800)
                            )
                        ),
                        timestamps = null, indicators = null
                    )
                ),
                error = null
            )
        )
    )

    private fun savedQuote(
        symbol: String, lastUpdatedMs: Long, withSession: Boolean = false,
        sessionOpen: Long = open, sessionClose: Long = close
    ) {
        saved[symbol] = StockEntity(
            symbol, symbol, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, lastUpdated = lastUpdatedMs,
            sessionStart = if (withSession) sessionOpen else 0, sessionEnd = if (withSession) sessionClose else 0,
            sessionPreStart = if (withSession) sessionOpen else 0, sessionPostEnd = if (withSession) sessionClose else 0,
            sessionGmtOffset = if (withSession) 19_800 else 0
        )
    }

    @Test
    fun `a fresh enough saved quote answers without a request`() = runTest {
        savedQuote("^NSEI", System.currentTimeMillis() - 60_000)

        val result = repo.fetchAndCacheStock("^NSEI", Freshness.PASSIVE)

        assertTrue(result is Resource.Success)
        coVerify(exactly = 0) { api.getQuote(any(), any(), any(), any()) }
    }

    @Test
    fun `by default a quote is always fetched, as every screen that needs the live price expects`() = runTest {
        savedQuote("^NSEI", System.currentTimeMillis() - 1_000)
        coEvery { api.getQuote(any(), any(), any(), any()) } returns quote(10.0)

        repo.fetchAndCacheStock("^NSEI")

        coVerify(exactly = 1) { api.getQuote("^NSEI", any(), any(), any()) }
    }

    @Test
    fun `a quote too old for the caller is fetched and replaces the saved one`() = runTest {
        savedQuote("^NSEI", System.currentTimeMillis() - 10 * 60_000)
        coEvery { api.getQuote(any(), any(), any(), any()) } returns quote(99.0)

        val result = repo.fetchAndCacheStock("^NSEI", Freshness.PASSIVE)

        assertEquals(99.0, (result as Resource.Success).data.currentPrice, 0.0)
        assertEquals(99.0, saved["^NSEI"]!!.currentPrice, 0.0)
    }

    @Test
    fun `a closing price saved after the close answers a passive load whatever its age`() = runTest {
        // The repository judges "now" against the real clock, so the session is built around it: it
        // closed an hour ago and the quote was saved 30 minutes after the close, whatever day this runs.
        val closedAt = System.currentTimeMillis() / 1_000 - 3_600
        savedQuote(
            "^NSEI", (closedAt + 30 * 60) * 1_000, withSession = true,
            sessionOpen = closedAt - 22_500, sessionClose = closedAt
        )

        val result = repo.fetchAndCacheStock("^NSEI", Freshness.PASSIVE)

        assertTrue(result is Resource.Success)
        coVerify(exactly = 0) { api.getQuote(any(), any(), any(), any()) }
    }

    @Test
    fun `the session times Yahoo sends are saved with the quote`() = runTest {
        coEvery { api.getQuote(any(), any(), any(), any()) } returns quote(10.0)

        repo.fetchAndCacheStock("^NSEI")

        val stock = saved["^NSEI"]!!
        assertEquals(open, stock.sessionStart)
        assertEquals(close, stock.sessionEnd)
        assertEquals(open, stock.sessionPreStart)
        assertEquals(close, stock.sessionPostEnd)
        assertEquals(19_800, stock.sessionGmtOffset)
    }

    @Test
    fun `a response without session times saves zeros rather than failing`() = runTest {
        val slot = slot<StockEntity>()
        coEvery { stockDao.insertStock(capture(slot)) } returns Unit
        coEvery { api.getQuote(any(), any(), any(), any()) } returns quote(10.0, withSession = false)

        val result = repo.fetchAndCacheStock("^NSEI")

        assertTrue(result is Resource.Success)
        assertEquals(0L, slot.captured.sessionStart)
        assertEquals(0L, slot.captured.sessionEnd)
    }
}
