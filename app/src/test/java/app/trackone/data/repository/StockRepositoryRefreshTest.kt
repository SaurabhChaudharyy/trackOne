package app.trackone.data.repository

import app.trackone.data.api.Freshness
import app.trackone.data.api.RateLimitBackoff
import app.trackone.data.api.RefreshPolicy
import app.trackone.data.api.RequestLimits
import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.PriceHistoryDao
import app.trackone.data.database.StockEntity
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
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private var backoffNow = 0L
    private val backoff = RateLimitBackoff { backoffNow }
    /** What the fake stock table holds, so a second refresh can see what the first one wrote. */
    private val cached = java.util.concurrent.ConcurrentHashMap<String, StockEntity>()

    @Before
    fun setUp() {
        api = mockk()
        stockDao = mockk(relaxed = true)
        watchlistDao = mockk(relaxed = true)
        coEvery { stockDao.getStock(any()) } coAnswers { cached[firstArg()] }
        coEvery { stockDao.insertStock(any()) } coAnswers { val s = firstArg<StockEntity>(); cached[s.symbol] = s }
        coEvery { watchlistDao.isInWatchlist(any()) } returns 1
        repo = StockRepository(api, stockDao, mockk<WatchlistGroupDao>(relaxed = true), watchlistDao, mockk<PriceHistoryDao>(relaxed = true), backoff)
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
    fun `a symbol another caller saved while it waited for its turn is not fetched again`() = runTest {
        // One more symbol than may be in flight: one of them waits for a free slot (which one is up to the
        // IO pool). By the time it has one, Splash (say) has saved its quote, and asking Yahoo again
        // would be a duplicate.
        val symbols = (1..RequestLimits.MAX_PARALLEL + 1).map { "S$it" }
        val requested = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { watchlistDao.getWatchlistSync() } returns watching(*symbols.toTypedArray())
        coEvery { api.getQuote(any(), any(), any(), any()) } coAnswers {
            requested.add(firstArg())
            gate.await()
            quote(10.0)
        }

        val refresh = async { repo.refreshWatchlistStocks() }
        withContext(Dispatchers.Default) { delay(500) }          // the slots are full and the last symbol is waiting
        val queued = symbols.single { it !in requested }
        cached[queued] = StockEntity(queued, queued, 5.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, lastUpdated = System.currentTimeMillis())
        gate.complete(Unit)
        refresh.await()

        coVerify(exactly = 0) { api.getQuote(queued, any(), any(), any()) }
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

    private fun cachedQuote(symbol: String, ageMs: Long) {
        cached[symbol] = StockEntity(symbol, symbol, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, lastUpdated = System.currentTimeMillis() - ageMs)
    }

    @Test
    fun `a symbol quoted within the window is not fetched again`() = runTest {
        coEvery { watchlistDao.getWatchlistSync() } returns watching("FRESH", "OLD")
        cachedQuote("FRESH", ageMs = 5_000)
        cachedQuote("OLD", ageMs = 10 * 60_000)
        coEvery { api.getQuote(any(), any(), any(), any()) } returns quote(10.0)

        val result = repo.refreshWatchlistStocks()

        assertTrue(result is Resource.Success)
        coVerify(exactly = 0) { api.getQuote("FRESH", any(), any(), any()) }
        coVerify(exactly = 1) { api.getQuote("OLD", any(), any(), any()) }
    }

    @Test
    fun `refreshing again straight away makes no requests`() = runTest {
        coEvery { watchlistDao.getWatchlistSync() } returns watching("A", "B", "C")
        coEvery { api.getQuote(any(), any(), any(), any()) } returns quote(10.0)

        repeat(5) { repo.refreshWatchlistStocks() }

        coVerify(exactly = 3) { api.getQuote(any(), any(), any(), any()) }     // one round, not five
    }

    @Test
    fun `refreshes that overlap share one round of requests`() = runTest {
        coEvery { watchlistDao.getWatchlistSync() } returns watching("A", "B", "C")
        coEvery { api.getQuote(any(), any(), any(), any()) } coAnswers { delay(100); quote(10.0) }

        val results = (1..4).map { async { repo.refreshWatchlistStocks() } }.map { it.await() }

        assertTrue(results.all { it is Resource.Success })
        coVerify(exactly = 3) { api.getQuote(any(), any(), any(), any()) }
    }

    @Test
    fun `a failed fetch does not use up the window, so the next refresh retries it`() = runTest {
        coEvery { watchlistDao.getWatchlistSync() } returns watching("BAD")
        coEvery { api.getQuote("BAD", any(), any(), any()) } returns Response.error(500, "".toResponseBody(null))

        repo.refreshWatchlistStocks()
        repo.refreshWatchlistStocks()

        coVerify(exactly = 2) { api.getQuote("BAD", any(), any(), any()) }
    }

    @Test
    fun `a window of zero fetches everything`() = runTest {
        coEvery { watchlistDao.getWatchlistSync() } returns watching("A")
        cachedQuote("A", ageMs = 1_000)
        coEvery { api.getQuote(any(), any(), any(), any()) } returns quote(10.0)

        repo.refreshWatchlistStocks(Freshness.ALWAYS)

        coVerify(exactly = 1) { api.getQuote("A", any(), any(), any()) }
    }

    @Test
    fun `while Yahoo is limiting requests nothing is fetched and the reason is given`() = runTest {
        coEvery { watchlistDao.getWatchlistSync() } returns watching("A", "B")
        backoff.onResponse(429, null)

        val result = repo.refreshWatchlistStocks()

        assertTrue(result is Resource.Error)
        assertTrue((result as Resource.Error).message!!.contains("limiting", ignoreCase = true))
        coVerify(exactly = 0) { api.getQuote(any(), any(), any(), any()) }
    }

    @Test
    fun `the window shared with net worth is thirty seconds`() {
        assertEquals(30_000L, RefreshPolicy.WINDOW_MS)
    }
}
