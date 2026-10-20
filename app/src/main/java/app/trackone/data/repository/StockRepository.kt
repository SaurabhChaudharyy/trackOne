package app.trackone.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import app.trackone.data.api.Freshness
import app.trackone.data.api.QuoteFreshness
import app.trackone.data.api.RateLimitBackoff
import app.trackone.data.api.RefreshPolicy
import app.trackone.data.api.RequestLimits
import androidx.lifecycle.LiveData
import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.*
import app.trackone.data.model.YahooSearchResult
import app.trackone.utils.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StockRepository @Inject constructor(
    private val apiService: YahooFinanceApiService,
    private val stockDao: StockDao,
    private val watchlistGroupDao: WatchlistGroupDao,
    private val watchlistDao: WatchlistDao,
    private val priceHistoryDao: PriceHistoryDao,
    private val backoff: RateLimitBackoff
) {

    companion object {
        /** The Row-ID that the very first group always gets (autoGenerate starts at 1). */
        const val DEFAULT_GROUP_ID = 1L
        const val DEFAULT_GROUP_NAME = "My Watchlist"
    }

    // ── Watchlist group CRUD ──────────────────────────────────────────────────

    fun getWatchlistGroups(): LiveData<List<WatchlistGroupEntity>> =
        watchlistGroupDao.getAllGroups()

    suspend fun getWatchlistGroupsSync(): List<WatchlistGroupEntity> =
        withContext(Dispatchers.IO) { watchlistGroupDao.getAllGroupsSync() }

    /**
     * Ensures the default group exists. Call this once on first launch
     * (idempotent via REPLACE; position 0 so it always sorts first).
     */
    suspend fun ensureDefaultGroup() = withContext(Dispatchers.IO) {
        val existing = watchlistGroupDao.getAllGroupsSync()
        if (existing.isEmpty()) {
            watchlistGroupDao.insertGroup(
                WatchlistGroupEntity(id = DEFAULT_GROUP_ID, name = DEFAULT_GROUP_NAME, position = 0)
            )
        }
    }

    suspend fun createWatchlistGroup(name: String): Long = withContext(Dispatchers.IO) {
        val maxPos = watchlistGroupDao.getMaxPosition() ?: -1
        watchlistGroupDao.insertGroup(
            WatchlistGroupEntity(name = name, position = maxPos + 1)
        )
    }

    suspend fun renameWatchlistGroup(id: Long, newName: String) = withContext(Dispatchers.IO) {
        watchlistGroupDao.renameGroup(id, newName)
    }

    /** Returns false without deleting if [id] is the only remaining group — the app has
     *  nowhere left to put watchlist items and several call sites assume at least one
     *  group always exists. */
    suspend fun deleteWatchlistGroup(id: Long): Boolean = withContext(Dispatchers.IO) {
        if (watchlistGroupDao.getGroupCount() <= 1) return@withContext false
        watchlistGroupDao.deleteAllInGroup(id)
        watchlistGroupDao.deleteGroup(id)
        true
    }

    // ── Stock watchlist (group-scoped) ────────────────────────────────────────

    fun getWatchlistStocks(): LiveData<List<StockEntity>> = stockDao.getWatchlistStocks()

    fun getWatchlist(): LiveData<List<WatchlistEntity>> = watchlistDao.getWatchlist()

    fun getWatchlistByGroup(groupId: Long): LiveData<List<WatchlistEntity>> =
        watchlistDao.getWatchlistByGroup(groupId)

    /** Fetches all symbols for a given group (for live-price refreshes). */
    suspend fun getWatchlistSyncByGroup(groupId: Long): List<WatchlistEntity> =
        withContext(Dispatchers.IO) { watchlistDao.getWatchlistSyncByGroup(groupId) }

    /** Stock data for one group's watchlist only — what the home-screen widget shows,
     *  since each widget instance is scoped to a single watchlist. */
    suspend fun getWatchlistStocksSyncByGroup(groupId: Long): List<StockEntity> =
        withContext(Dispatchers.IO) { stockDao.getWatchlistStocksSyncByGroup(groupId) }

    /**
     * Serializes watchlist refreshes: Splash, the Watchlist screen and the widget can all ask at
     * once, and the second one waits and then finds everything already fresh (see [RefreshPolicy]).
     */
    private val watchlistRefreshMutex = Mutex()

    /**
     * Refreshes every watchlist symbol whose saved quote is not fresh enough for [freshness] — the
     * default is [Freshness.PULL] (skip only what is under [RefreshPolicy.WINDOW_MS] old), so pulling
     * to refresh again and again does not repeat the requests; the app opening itself passes
     * [Freshness.PASSIVE]. [Freshness.ALWAYS] refetches everything. While Yahoo has asked the app to
     * slow down ([backoff]), nothing is fetched and the saved prices stay on screen.
     */
    suspend fun refreshWatchlistStocks(freshness: Freshness = Freshness.PULL): Resource<Unit> =
        watchlistRefreshMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val paused = backoff.remainingMs()
                    if (paused > 0L) {
                        return@withContext Resource.Error(
                            "Yahoo Finance is limiting requests, so these are saved prices. Try again in ${(paused + 999) / 1000}s."
                        )
                    }

                    // Same symbol can appear in multiple groups (composite PK); fetch each symbol
                    // once, concurrently, instead of sequentially re-fetching duplicates. A few at a time:
                    // a long watchlist fired all at once is what gets rate-limited.
                    val symbols = watchlistDao.getWatchlistSync().map { it.symbol }.distinct()
                        .filterNot { QuoteFreshness.isFresh(stockDao.getStock(it), freshness) }
                    val limiter = Semaphore(RequestLimits.MAX_PARALLEL)
                    val errors = coroutineScope {
                        symbols.map { symbol ->
                            // The saved quote is looked at again once the symbol has its turn: someone else (Splash's
                            // index fetch, Home) may have saved it while this one waited for a free slot.
                            async { symbol to limiter.withPermit { fetchAndCacheStock(symbol, freshness) } }
                        }.awaitAll()
                    }.filter { (_, result) -> result is Resource.Error }.map { it.first }

                    if (errors.isEmpty()) Resource.Success(Unit)
                    else Resource.Error("Failed to refresh: ${errors.joinToString()}")
                } catch (e: Exception) {
                    Resource.Error(e.message ?: "Unknown error during refresh")
                }
            }
        }

    /**
     * Requests for a symbol that are already on their way, so a second caller waits for that answer
     * instead of asking Yahoo again: Splash's index fetch and the watchlist refresh both want ^NSEI,
     * and Home's top movers and net worth both want every holding. They run in [fetchScope], not in the
     * caller's: Splash finishes 2.5 s in, and cancelling what it started only made Home ask again.
     */
    private val inFlight = ConcurrentHashMap<String, Deferred<Resource<StockEntity>>>()
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The quote for [symbol], from Yahoo unless the saved one is fresh enough for [freshness]. The
     * default asks Yahoo every time, as a screen showing the live price needs. When Yahoo cannot be
     * reached the saved quote (however old) is returned, so a screen has something to show; use
     * [fetchFreshQuote] when a failure has to be known as one.
     */
    suspend fun fetchAndCacheStock(symbol: String, freshness: Freshness = Freshness.ALWAYS): Resource<StockEntity> =
        when (val result = fetchFreshQuote(symbol, freshness)) {
            is Resource.Error -> fallbackToCache(symbol, result.message.orEmpty())
            else -> result
        }

    /**
     * Like [fetchAndCacheStock] but a failure stays a failure: no saved quote stands in for it. Net
     * worth prices holdings from this, because a stale price written as a live one would also stop
     * its symbol repair from ever noticing a symbol that no longer exists.
     */
    suspend fun fetchFreshQuote(symbol: String, freshness: Freshness = Freshness.ALWAYS): Resource<StockEntity> =
        withContext(Dispatchers.IO) {
            try {
                if (freshness.maxAgeMs > 0L) {
                    val savedQuote = stockDao.getStock(symbol)
                    if (savedQuote != null && QuoteFreshness.isFresh(savedQuote, freshness)) {
                        return@withContext Resource.Success(savedQuote)
                    }
                }
                val request = inFlight.computeIfAbsent(symbol) { key ->
                    fetchScope.async(start = CoroutineStart.LAZY) { requestQuote(key) }
                        .also { started -> started.invokeOnCompletion { inFlight.remove(key, started) } }
                }
                request.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Resource.Error(e.message ?: "Network error")
            }
        }

    private suspend fun requestQuote(symbol: String): Resource<StockEntity> {
        return try {
            val response = apiService.getQuote(symbol, interval = "1d", range = "1d")

            if (response.isSuccessful) {
                val chartResult = response.body()?.chart?.result?.firstOrNull()
                    ?: return Resource.Error("No data returned for $symbol")

                val meta = chartResult.meta
                val inWatchlist = watchlistDao.isInWatchlist(symbol) > 0

                // Yahoo's v7 quote endpoint (market cap, open) now answers 401 to every call, so it
                // is not asked: it doubled the round trips for every quote on launch.
                val marketCap = meta.marketCap.toDouble()
                val openPrice = if (meta.regularMarketOpen != 0.0) meta.regularMarketOpen
                                else meta.effectivePreviousClose

                val stock = StockEntity(
                    symbol = symbol,
                    companyName = meta.displayName,
                    currentPrice = meta.regularMarketPrice,
                    change = meta.change,
                    changePercent = meta.changePercent,
                    highPrice = meta.regularMarketDayHigh,
                    lowPrice = meta.regularMarketDayLow,
                    openPrice = openPrice,
                    previousClose = meta.effectivePreviousClose,
                    marketCap = marketCap,
                    exchange = meta.exchangeName,
                    currency = meta.currency,
                    lastUpdated = System.currentTimeMillis(),
                    isInWatchlist = inWatchlist,
                    sessionPreStart = meta.currentTradingPeriod?.pre?.start ?: 0L,
                    sessionStart = meta.currentTradingPeriod?.regular?.start ?: 0L,
                    sessionEnd = meta.currentTradingPeriod?.regular?.end ?: 0L,
                    sessionPostEnd = meta.currentTradingPeriod?.post?.end ?: 0L,
                    sessionGmtOffset = meta.currentTradingPeriod?.regular?.gmtoffset ?: 0
                )

                stockDao.insertStock(stock)
                Resource.Success(stock)
            } else {
                Resource.Error("HTTP ${response.code()}: ${response.message()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Network error")
        }
    }

    private suspend fun fallbackToCache(symbol: String, errorMsg: String): Resource<StockEntity> {
        val cached = stockDao.getStock(symbol)
        return if (cached != null) Resource.Success(cached)
        else Resource.Error(errorMsg)
    }

    suspend fun fetchPriceHistory(
        symbol: String,
        interval: String,
        range: String
    ): Resource<List<PriceHistoryEntity>> = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "$interval-$range"

            val cacheTtlMs = when {
                range == "1d" || range == "5d" -> 15 * 60 * 1000L
                range == "1mo" || range == "3mo" -> 4 * 60 * 60 * 1000L
                else -> 24 * 60 * 60 * 1000L
            }

            val cached = priceHistoryDao.getPriceHistory(symbol, cacheKey)
            val cacheIsValid = cached.isNotEmpty() &&
                (System.currentTimeMillis() - (cached.last().timestamp * 1000)) < cacheTtlMs
            if (cacheIsValid) return@withContext Resource.Success(cached)

            val response = apiService.getChartData(symbol, interval = interval, range = range)
            if (response.isSuccessful) {
                val result = response.body()?.chart?.result?.firstOrNull()
                val timestamps = result?.timestamps
                val quotes = result?.indicators?.quote?.firstOrNull()

                if (timestamps.isNullOrEmpty() || quotes == null) {
                    return@withContext Resource.Error("No chart data for $symbol ($range)")
                }

                val historyList = timestamps.mapIndexedNotNull { i, ts ->
                    val close = quotes.close?.getOrNull(i) ?: return@mapIndexedNotNull null
                    PriceHistoryEntity(
                        symbol = symbol,
                        timestamp = ts,
                        open = quotes.open?.getOrNull(i) ?: close,
                        high = quotes.high?.getOrNull(i) ?: close,
                        low = quotes.low?.getOrNull(i) ?: close,
                        close = close,
                        volume = quotes.volume?.getOrNull(i) ?: 0L,
                        resolution = cacheKey
                    )
                }

                priceHistoryDao.deletePriceHistory(symbol, cacheKey)
                priceHistoryDao.insertPriceHistory(historyList)
                Resource.Success(historyList)
            } else {
                Resource.Error("Chart API error: ${response.code()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Error fetching chart data")
        }
    }

    suspend fun searchStocks(query: String): Resource<List<YahooSearchResult>> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.searchSymbol(query)
            if (response.isSuccessful) {
                val results = response.body()?.quotes
                    ?.filter { it.isTrackable }
                    ?.take(25)
                    ?: emptyList()
                Resource.Success(results)
            } else {
                Resource.Error("Search failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Search error")
        }
    }

    suspend fun addToWatchlist(symbol: String, displayName: String, groupId: Long = DEFAULT_GROUP_ID) =
        withContext(Dispatchers.IO) {
            val maxPosition = watchlistDao.getMaxPositionInGroup(groupId) ?: -1
            watchlistDao.addToWatchlist(
                WatchlistEntity(
                    symbol = symbol,
                    displayName = displayName,
                    position = maxPosition + 1,
                    groupId = groupId
                )
            )
            fetchAndCacheStock(symbol)
        }

    /** Removes [symbol] from the group and returns when it was first added, for an UNDO to put back. */
    suspend fun removeFromWatchlist(symbol: String, groupId: Long): Long? = withContext(Dispatchers.IO) {
        val addedAt = watchlistDao.getWatchlistSyncByGroup(groupId).firstOrNull { it.symbol == symbol }?.addedAt
        watchlistDao.removeFromWatchlistInGroup(symbol, groupId)
        addedAt
    }

    /**
     * Puts a just-removed stock back at [index] in the group's list (UNDO). [addToWatchlist] always
     * appends, so this renumbers the group instead: stored positions are not trusted to be
     * contiguous, because removals leave gaps. No quote is fetched — the stock row is still cached,
     * and the next refresh updates it like any other.
     */
    suspend fun restoreToWatchlist(
        symbol: String, displayName: String, groupId: Long, index: Int,
        addedAt: Long = System.currentTimeMillis()
    ) =
        withContext(Dispatchers.IO) {
            val others = watchlistDao.getWatchlistSyncByGroup(groupId).filter { it.symbol != symbol }
            val at = index.coerceIn(0, others.size)
            others.forEachIndexed { i, item ->
                watchlistDao.updatePosition(item.symbol, groupId, if (i >= at) i + 1 else i)
            }
            watchlistDao.addToWatchlist(
                WatchlistEntity(symbol = symbol, displayName = displayName, position = at, groupId = groupId, addedAt = addedAt)
            )
        }

    suspend fun isInWatchlist(symbol: String): Boolean = withContext(Dispatchers.IO) {
        watchlistDao.isInWatchlist(symbol) > 0
    }

    suspend fun updateWatchlistOrder(items: List<WatchlistEntity>, groupId: Long) =
        withContext(Dispatchers.IO) {
            items.forEachIndexed { index, item ->
                watchlistDao.updatePosition(item.symbol, groupId, index)
            }
        }

    suspend fun getStockSync(symbol: String): StockEntity? = withContext(Dispatchers.IO) {
        stockDao.getStock(symbol)
    }

    suspend fun getWatchlistSync(): List<StockEntity> = withContext(Dispatchers.IO) {
        stockDao.getWatchlistStocksSync()
    }
}
