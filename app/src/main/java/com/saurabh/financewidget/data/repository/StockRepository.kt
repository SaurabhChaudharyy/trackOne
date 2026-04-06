package com.saurabh.financewidget.data.repository

import androidx.lifecycle.LiveData
import com.saurabh.financewidget.data.api.YahooFinanceApiService
import com.saurabh.financewidget.data.database.*
import com.saurabh.financewidget.data.model.YahooSearchResult
import com.saurabh.financewidget.utils.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StockRepository @Inject constructor(
    private val apiService: YahooFinanceApiService,
    private val stockDao: StockDao,
    private val watchlistGroupDao: WatchlistGroupDao,
    private val watchlistDao: WatchlistDao,
    private val priceHistoryDao: PriceHistoryDao
) {

    companion object {
        /** The Row-ID that the very first group always gets (autoGenerate starts at 1). */
        const val DEFAULT_GROUP_ID = 1L
        const val DEFAULT_GROUP_NAME = "My Watchlist"
    }

    // ── Watchlist group CRUD ──────────────────────────────────────────────────

    fun getWatchlistGroups(): LiveData<List<WatchlistGroupEntity>> =
        watchlistGroupDao.getAllGroups()

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

    suspend fun deleteWatchlistGroup(id: Long) = withContext(Dispatchers.IO) {
        watchlistGroupDao.deleteAllInGroup(id)
        watchlistGroupDao.deleteGroup(id)
    }

    // ── Stock watchlist (group-scoped) ────────────────────────────────────────

    fun getWatchlistStocks(): LiveData<List<StockEntity>> = stockDao.getWatchlistStocks()

    fun getWatchlist(): LiveData<List<WatchlistEntity>> = watchlistDao.getWatchlist()

    fun getWatchlistByGroup(groupId: Long): LiveData<List<WatchlistEntity>> =
        watchlistDao.getWatchlistByGroup(groupId)

    /** Fetches all symbols for a given group (for live-price refreshes). */
    suspend fun getWatchlistSyncByGroup(groupId: Long): List<WatchlistEntity> =
        withContext(Dispatchers.IO) { watchlistDao.getWatchlistSyncByGroup(groupId) }

    suspend fun refreshWatchlistStocks(): Resource<Unit> = withContext(Dispatchers.IO) {
        try {
            val watchlist = watchlistDao.getWatchlistSync()
            val errors = mutableListOf<String>()
            watchlist.forEach { item ->
                val result = fetchAndCacheStock(item.symbol)
                if (result is Resource.Error) errors.add(item.symbol)
            }
            if (errors.isEmpty()) Resource.Success(Unit)
            else Resource.Error("Failed to refresh: ${errors.joinToString()}")
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Unknown error during refresh")
        }
    }

    suspend fun fetchAndCacheStock(symbol: String): Resource<StockEntity> = withContext(Dispatchers.IO) {
        try {
            val response = apiService.getQuote(symbol, interval = "1d", range = "1d")

            if (response.isSuccessful) {
                val chartResult = response.body()?.chart?.result?.firstOrNull()
                    ?: return@withContext fallbackToCache(symbol, "No data returned for $symbol")

                val meta = chartResult.meta
                val inWatchlist = watchlistDao.isInWatchlist(symbol) > 0

                var marketCap = meta.marketCap.toDouble()
                var supplementalOpen = meta.regularMarketOpen
                var supplementalPrevClose = meta.effectivePreviousClose
                if (marketCap == 0.0 || supplementalOpen == 0.0) {
                    try {
                        val quoteResp = apiService.getQuoteDetails(symbol)
                        if (quoteResp.isSuccessful) {
                            val item = quoteResp.body()?.quoteResponse?.result?.firstOrNull()
                            if (item != null) {
                                if (marketCap == 0.0) marketCap = item.marketCap.toDouble()
                                if (supplementalOpen == 0.0 && item.regularMarketOpen != 0.0)
                                    supplementalOpen = item.regularMarketOpen
                                if (supplementalPrevClose == 0.0 && item.regularMarketPreviousClose != 0.0)
                                    supplementalPrevClose = item.regularMarketPreviousClose
                            }
                        }
                    } catch (_: Exception) { }
                }

                val openPrice = if (supplementalOpen != 0.0) supplementalOpen
                                else supplementalPrevClose

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
                    isInWatchlist = inWatchlist
                )

                stockDao.insertStock(stock)
                Resource.Success(stock)
            } else {
                fallbackToCache(symbol, "HTTP ${response.code()}: ${response.message()}")
            }
        } catch (e: Exception) {
            fallbackToCache(symbol, e.message ?: "Network error")
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

    suspend fun removeFromWatchlist(symbol: String, groupId: Long) = withContext(Dispatchers.IO) {
        watchlistDao.removeFromWatchlistInGroup(symbol, groupId)
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
