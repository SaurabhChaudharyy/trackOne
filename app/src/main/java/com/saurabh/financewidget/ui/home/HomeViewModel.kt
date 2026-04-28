package com.saurabh.financewidget.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.financewidget.data.database.AssetType
import com.saurabh.financewidget.data.database.NetWorthDao
import com.saurabh.financewidget.data.database.StockEntity
import com.saurabh.financewidget.data.repository.NetWorthRepository
import com.saurabh.financewidget.data.repository.StockRepository
import com.saurabh.financewidget.utils.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IndexData(
    val price: Double,
    val changePercent: Double,
    val currency: String
)

/**
 * A single top-mover row: live price data + user's position info.
 * [invested] and [currentVal] are 0 when the user has no buy price recorded.
 */
data class TopMover(
    val stock: StockEntity,
    val invested: Double,   // buyPrice * quantity (0 if no buy price)
    val currentVal: Double, // currentPrice * quantity
    val qty: Double
)

/**
 * Aggregated portfolio summary for the Home screen card.
 */
data class PortfolioSummary(
    val totalCurrent: Double,
    val totalInvested: Double,
    val absChange: Double,
    val pctChange: Double
)

/**
 * A single point on the portfolio value chart.
 * [timestamp] is a Unix epoch in millis, [invested] is cumulative cost-basis,
 * [current] is cumulative current market value at that moment.
 */
data class PortfolioChartPoint(
    val timestamp: Long,   // millis
    val invested: Double,
    val current: Double
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: StockRepository,
    private val netWorthRepository: NetWorthRepository,
    private val netWorthDao: NetWorthDao
) : ViewModel() {

    // ── Market Indexes ──────────────────────────────────────────────────
    private val _nifty   = MutableLiveData<Resource<IndexData>>()
    val nifty: LiveData<Resource<IndexData>> = _nifty

    private val _sensex  = MutableLiveData<Resource<IndexData>>()
    val sensex: LiveData<Resource<IndexData>> = _sensex

    private val _sp500   = MutableLiveData<Resource<IndexData>>()
    val sp500: LiveData<Resource<IndexData>> = _sp500

    private val _nasdaq  = MutableLiveData<Resource<IndexData>>()
    val nasdaq: LiveData<Resource<IndexData>> = _nasdaq

    // ── Top Movers (up to 5) ────────────────────────────────────────────
    private val _topMovers = MutableLiveData<List<TopMover>>()
    val topMovers: LiveData<List<TopMover>> = _topMovers

    // ── Portfolio Summary ───────────────────────────────────────────────
    private val _portfolioSummary = MutableLiveData<PortfolioSummary?>()
    val portfolioSummary: LiveData<PortfolioSummary?> = _portfolioSummary

    // ── Portfolio Chart data ────────────────────────────────────────────
    private val _portfolioChartData = MutableLiveData<List<PortfolioChartPoint>>(emptyList())
    val portfolioChartData: LiveData<List<PortfolioChartPoint>> = _portfolioChartData

    // ── Portfolio refresh event (fires when live data differs from cached) ─────
    /** Carries the fresh PortfolioSummary so the Fragment can show a "Updated" banner. */
    private val _portfolioRefreshed = MutableLiveData<PortfolioSummary?>()
    val portfolioRefreshed: LiveData<PortfolioSummary?> = _portfolioRefreshed

    // ── Loading state ────────────────────────────────────────────────────
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    init {
        // 1️⃣ Show cached data immediately (instant — no network)
        viewModelScope.launch { loadCachedPortfolio() }
        // 2️⃣ Refresh live prices in background; emit banner event if values changed
        viewModelScope.launch { refreshLivePricesQuietly() }
        // 3️⃣ Fetch indexes + top movers (also background)
        viewModelScope.launch { fetchIndexes(); fetchTopMovers() }
    }

    // ───────────────────────────────────────────────────────────────

    /**
     * Called on swipe-to-refresh. Shows loading indicator, re-fetches everything,
     * and applies the live values directly (no banner — user explicitly requested it).
     */
    fun fetchAll() {
        viewModelScope.launch {
            _isLoading.value = true
            // Apply live prices to DB first, then recompute
            netWorthRepository.refreshNetWorthAssets()
            fetchIndexes()
            fetchTopMovers()
            computePortfolioSummary()
            computePortfolioChartData()
            _isLoading.value = false
        }
    }

    /**
     * Reads asset values from Room cache and immediately posts them to the UI.
     * No network calls — runs in milliseconds.
     */
    private suspend fun loadCachedPortfolio() {
        computePortfolioSummary()
        computePortfolioChartData()
    }

    /**
     * Silently refreshes live prices from the network.
     * If the resulting portfolio value differs from the current cached value by > ₹1,
     * emits [portfolioRefreshed] so the Fragment can show an "Updated" banner.
     * Does NOT auto-apply — the user taps the banner to accept the new value.
     */
    private suspend fun refreshLivePricesQuietly() {
        val cachedSummary = _portfolioSummary.value
        netWorthRepository.refreshNetWorthAssets()  // updates currentValue in DB
        val freshSummary = buildPortfolioSummary() ?: return
        // Emit only if the value changed meaningfully (> ₹1 delta)
        if (cachedSummary == null ||
            kotlin.math.abs(freshSummary.totalCurrent - cachedSummary.totalCurrent) > 1.0) {
            _portfolioRefreshed.postValue(freshSummary)
        }
    }

    /** Called from the Fragment when the user taps the "Updated" banner. */
    fun applyRefreshedPortfolio() {
        val fresh = _portfolioRefreshed.value ?: return
        _portfolioSummary.postValue(fresh)
        _portfolioRefreshed.postValue(null)          // dismiss banner
        // Rebuild chart with the now-updated DB values
        viewModelScope.launch { computePortfolioChartData() }
    }

    private suspend fun fetchIndexes() {
        val symbols = listOf(
            "^NSEI"  to _nifty,
            "^BSESN" to _sensex,
            "^GSPC"  to _sp500,
            "^IXIC"  to _nasdaq
        )
        for ((symbol, liveData) in symbols) {
            liveData.postValue(Resource.Loading())
            val result = repository.fetchAndCacheStock(symbol)
            when (result) {
                is Resource.Success -> {
                    val stock = result.data
                    liveData.postValue(
                        Resource.Success(
                            IndexData(
                                price = stock.currentPrice,
                                changePercent = stock.changePercent,
                                currency = stock.currency
                            )
                        )
                    )
                }
                is Resource.Error -> liveData.postValue(Resource.Error(result.message.orEmpty()))
                else -> {}
            }
        }
    }

    /**
     * Finds up to 3 portfolio holdings with the largest absolute daily % change.
     * Only considers fetchable asset types: Indian stocks, US stocks, crypto, gold, silver.
     * Deduplicates by symbol so we don't double-fetch.
     */
    private suspend fun fetchTopMovers() {
        val fetchableTypes = setOf(
            AssetType.STOCK_IN, AssetType.STOCK_US,
            AssetType.CRYPTO, AssetType.GOLD, AssetType.SILVER
        )
        val assets = netWorthDao.getAllAssetsSync()
            .filter { it.assetType in fetchableTypes }
            .distinctBy { it.name }

        if (assets.isEmpty()) {
            _topMovers.postValue(emptyList())
            return
        }

        val results = mutableListOf<TopMover>()

        for (asset in assets) {
            val symbol = when (asset.assetType) {
                AssetType.GOLD   -> "GC=F"
                AssetType.SILVER -> "SI=F"
                else             -> asset.name
            }
            val result = repository.fetchAndCacheStock(symbol)
            if (result is Resource.Success) {
                val stock = result.data
                val currentVal = stock.currentPrice * asset.quantity
                val invested   = if (asset.buyPrice > 0.0) asset.buyPrice * asset.quantity else 0.0
                results.add(TopMover(
                    stock      = stock,
                    invested   = invested,
                    currentVal = currentVal,
                    qty        = asset.quantity
                ))
            }
        }

        // Sort by absolute % change descending, take top 5
        val top5 = results.sortedByDescending { kotlin.math.abs(it.stock.changePercent) }.take(5)
        _topMovers.postValue(top5)
    }

    /**
     * Computes the portfolio P&L summary from all assets.
     * Assets with buyPrice == 0 are counted as break-even.
     * Returns null if there are no assets.
     */
    private suspend fun buildPortfolioSummary(): PortfolioSummary? {
        val assets = netWorthDao.getAllAssetsSync()
        if (assets.isEmpty()) return null

        var totalInvested = 0.0
        var totalCurrent  = 0.0
        for (asset in assets) {
            if (asset.buyPrice > 0.0) {
                totalInvested += asset.buyPrice * asset.quantity
                totalCurrent  += asset.currentValue
            } else {
                totalInvested += asset.currentValue
                totalCurrent  += asset.currentValue
            }
        }
        val absChange = totalCurrent - totalInvested
        val pct = if (totalInvested > 0.0) (absChange / totalInvested) * 100.0 else 0.0
        return PortfolioSummary(
            totalCurrent  = totalCurrent,
            totalInvested = totalInvested,
            absChange     = absChange,
            pctChange     = pct
        )
    }

    private suspend fun computePortfolioSummary() {
        _portfolioSummary.postValue(buildPortfolioSummary())
    }

    /**
     * Builds a time-series of cumulative portfolio value vs invested cost-basis.
     * Assets are sorted by addedAt timestamp; each distinct calendar-day boundary
     * becomes a data point showing cumulative invested + current values up to that day.
     * We append today as the final "live" point using current market values.
     */
    private suspend fun computePortfolioChartData() {
        val assets = netWorthDao.getAllAssetsSync()
        if (assets.size < 2) {
            _portfolioChartData.postValue(emptyList())
            return
        }

        // Sort by when each asset was added
        val sorted = assets.sortedBy { it.addedAt }

        // Build cumulative points: one point per asset-addition event
        val points = mutableListOf<PortfolioChartPoint>()
        var cumulativeInvested = 0.0
        var cumulativeCurrent  = 0.0

        for (asset in sorted) {
            val invested = if (asset.buyPrice > 0.0) asset.buyPrice * asset.quantity else asset.currentValue
            cumulativeInvested += invested
            cumulativeCurrent  += asset.currentValue
            points.add(
                PortfolioChartPoint(
                    timestamp = asset.addedAt,
                    invested  = cumulativeInvested,
                    current   = cumulativeCurrent
                )
            )
        }

        // Ensure the last point is "now" with fresh current values (covers same-day additions)
        val nowTs = System.currentTimeMillis()
        if (points.isNotEmpty() && nowTs > points.last().timestamp) {
            points.add(
                PortfolioChartPoint(
                    timestamp = nowTs,
                    invested  = points.last().invested,
                    current   = points.last().current
                )
            )
        }

        _portfolioChartData.postValue(points)
    }
}
