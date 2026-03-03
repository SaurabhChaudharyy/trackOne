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
    private val watchlistDao: WatchlistDao,
    private val priceHistoryDao: PriceHistoryDao
) {

    fun getWatchlistStocks(): LiveData<List<StockEntity>> = stockDao.getWatchlistStocks()

    fun getWatchlist(): LiveData<List<WatchlistEntity>> = watchlistDao.getWatchlist()

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

                // marketCap is rarely populated in the chart endpoint — supplement with a
                // quote endpoint call which always includes it.
                // Also use it to get a reliable regularMarketOpen (often 0 in chart meta for indices).
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

                // Only use prev close as a last resort for open (avoids showing same value twice)
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

    /**
     * Fetches OHLCV candle data for charting.
     *
     * Yahoo Finance interval/range combinations:
     *  1D  view → interval="5m",  range="1d"
     *  1W  view → interval="60m", range="5d"
     *  1M  view → interval="1d",  range="1mo"
     *  3M  view → interval="1d",  range="3mo"
     *  1Y  view → interval="1wk", range="1y"
     *  5Y  view → interval="1mo", range="5y"
     */
    suspend fun fetchPriceHistory(
        symbol: String,
        interval: String,
        range: String
    ): Resource<List<PriceHistoryEntity>> = withContext(Dispatchers.IO) {
        try {
            val cacheKey = "$interval-$range"

            // Cache TTLs: intraday data expires quickly; daily/weekly data can live longer
            val cacheTtlMs = when {
                range == "1d" || range == "5d" -> 15 * 60 * 1000L      // 15 min
                range == "1mo" || range == "3mo" -> 4 * 60 * 60 * 1000L // 4 hours
                else -> 24 * 60 * 60 * 1000L                            // 24 hours for 1y/5y
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

    suspend fun addToWatchlist(symbol: String, displayName: String) = withContext(Dispatchers.IO) {
        val maxPosition = watchlistDao.getMaxPosition() ?: -1
        watchlistDao.addToWatchlist(
            WatchlistEntity(symbol = symbol, displayName = displayName, position = maxPosition + 1)
        )
        fetchAndCacheStock(symbol)
    }

    suspend fun removeFromWatchlist(symbol: String) = withContext(Dispatchers.IO) {
        watchlistDao.removeFromWatchlist(symbol)
    }

    suspend fun isInWatchlist(symbol: String): Boolean = withContext(Dispatchers.IO) {
        watchlistDao.isInWatchlist(symbol) > 0
    }

    suspend fun updateWatchlistOrder(items: List<WatchlistEntity>) = withContext(Dispatchers.IO) {
        items.forEachIndexed { index, item ->
            watchlistDao.updatePosition(item.symbol, index)
        }
    }

    suspend fun getStockSync(symbol: String): StockEntity? = withContext(Dispatchers.IO) {
        stockDao.getStock(symbol)
    }

    suspend fun getWatchlistSync(): List<StockEntity> = withContext(Dispatchers.IO) {
        stockDao.getWatchlistStocksSync()
    }
}
