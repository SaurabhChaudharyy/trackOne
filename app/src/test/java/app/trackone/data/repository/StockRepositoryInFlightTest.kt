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
import app.trackone.utils.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * One request per symbol at a time, however many screens ask. Splash's index fetch and the watchlist
 * refresh both want ^NSEI, Home's top movers and net worth both want every holding, and Splash's own
 * requests were cancelled when it finished 2.5 s in and then repeated by Home. A caller waits for the
 * request already on its way, and that request outlives the caller that started it.
 */
class StockRepositoryInFlightTest {

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

    private fun quote(symbol: String, price: Double): Response<YahooChartResponse> = Response.success(
        YahooChartResponse(
            chart = YahooChart(
                result = listOf(
                    YahooChartResult(
                        meta = YahooMeta(symbol = symbol, regularMarketPrice = price, previousClose = price),
                        timestamps = null, indicators = null
                    )
                ),
                error = null
            )
        )
    )

    /** Holds every getQuote until [gate] is opened, and says when the first one has arrived. */
    private fun holdQuotes(gate: CompletableDeferred<Unit>, arrived: CompletableDeferred<Unit>, price: Double = 10.0) {
        coEvery { api.getQuote(any(), any(), any(), any()) } coAnswers {
            arrived.complete(Unit)
            gate.await()
            quote(firstArg(), price)
        }
    }

    /** Real time for the other callers to reach the request that is already waiting on [gate]. */
    private suspend fun letCallersQueue() = withContext(Dispatchers.Default) { delay(300) }

    @Test
    fun `callers asking for the same symbol at once share one request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        holdQuotes(gate, CompletableDeferred())

        val callers = List(3) { async { repo.fetchAndCacheStock("^NSEI") } }
        letCallersQueue()
        gate.complete(Unit)
        val results = callers.awaitAll()

        assertTrue(results.all { it is Resource.Success })
        coVerify(exactly = 1) { api.getQuote("^NSEI", any(), any(), any()) }
    }

    @Test
    fun `different symbols are still requested separately`() = runTest {
        val gate = CompletableDeferred<Unit>()
        holdQuotes(gate, CompletableDeferred())

        val callers = listOf("^NSEI", "^GSPC").map { s -> async { repo.fetchAndCacheStock(s) } }
        letCallersQueue()
        gate.complete(Unit)
        callers.awaitAll()

        coVerify(exactly = 1) { api.getQuote("^NSEI", any(), any(), any()) }
        coVerify(exactly = 1) { api.getQuote("^GSPC", any(), any(), any()) }
    }

    @Test
    fun `a request goes on without the caller that started it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val arrived = CompletableDeferred<Unit>()
        holdQuotes(gate, arrived, price = 42.0)

        val splash = launch { repo.fetchAndCacheStock("^NSEI") }
        arrived.await()
        splash.cancelAndJoin()                       // Splash finishes while Yahoo is still answering

        val home = async { repo.fetchAndCacheStock("^NSEI") }
        letCallersQueue()
        gate.complete(Unit)
        val result = home.await()

        assertEquals(42.0, (result as Resource.Success).data.currentPrice, 0.0)
        assertEquals(42.0, saved["^NSEI"]!!.currentPrice, 0.0)
        coVerify(exactly = 1) { api.getQuote("^NSEI", any(), any(), any()) }
    }

    @Test
    fun `a request that has finished is not reused, the next caller asks again`() = runTest {
        coEvery { api.getQuote(any(), any(), any(), any()) } returns quote("^NSEI", 10.0)

        repo.fetchAndCacheStock("^NSEI")
        repo.fetchAndCacheStock("^NSEI")

        coVerify(exactly = 2) { api.getQuote("^NSEI", any(), any(), any()) }
    }

    @Test
    fun `a failed request is not kept, the next caller can try again`() = runTest {
        coEvery { api.getQuote(any(), any(), any(), any()) } throws java.io.IOException("offline")
        repo.fetchAndCacheStock("^NSEI")

        coEvery { api.getQuote(any(), any(), any(), any()) } returns quote("^NSEI", 7.0)
        val result = repo.fetchAndCacheStock("^NSEI")

        assertEquals(7.0, (result as Resource.Success).data.currentPrice, 0.0)
    }

    @Test
    fun `fetchFreshQuote reports a failure instead of falling back to a saved quote`() = runTest {
        saved["^NSEI"] = StockEntity("^NSEI", "Nifty", 1.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, lastUpdated = 0L)
        coEvery { api.getQuote(any(), any(), any(), any()) } returns
            Response.error(429, "".toResponseBody(null))

        val strict = repo.fetchFreshQuote("^NSEI")
        val lenient = repo.fetchAndCacheStock("^NSEI")

        assertTrue("net worth must see the failure", strict is Resource.Error)
        assertTrue("screens keep showing the saved quote", lenient is Resource.Success)
    }

    @Test
    fun `fetchFreshQuote uses a saved quote that is still fresh for the caller`() = runTest {
        saved["^NSEI"] = StockEntity("^NSEI", "Nifty", 5.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, lastUpdated = System.currentTimeMillis() - 60_000)

        val result = repo.fetchFreshQuote("^NSEI", Freshness.PASSIVE)

        assertEquals(5.0, (result as Resource.Success).data.currentPrice, 0.0)
        coVerify(exactly = 0) { api.getQuote(any(), any(), any(), any()) }
    }
}
