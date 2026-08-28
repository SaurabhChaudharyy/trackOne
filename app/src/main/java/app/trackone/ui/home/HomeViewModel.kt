package app.trackone.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.database.NetWorthDao
import app.trackone.data.database.StockEntity
import app.trackone.data.repository.NetWorthRepository
import app.trackone.data.repository.StockRepository
import app.trackone.utils.Resource
import app.trackone.utils.SymbolUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

    companion object {
        /** How long to let the Home screen finish its initial layout/animation before the
         *  passive live-price refresh (which does a network call per asset) starts competing
         *  for CPU/IO. */
        private const val STARTUP_REFRESH_DELAY_MS = 1200L
    }

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

    // ── Reactive net worth observer ─────────────────────────────────────
    // Room invalidates this LiveData on ANY write to networth_assets — a manual edit,
    // a broker CSV import, or (critically) Settings' Clear Local Data / Restore from
    // Cloud, which happen on a totally different screen/ViewModel with no direct link
    // back here. Without this, Home only ever recomputed on init or swipe-to-refresh,
    // so those actions left it showing stale numbers until the user manually refreshed.
    private val netWorthAssetsLiveData: LiveData<List<NetWorthAssetEntity>> = netWorthDao.getAllAssets()
    private val netWorthAssetsObserver = Observer<List<NetWorthAssetEntity>> { assets ->
        _portfolioSummary.value = buildPortfolioSummary(assets)
        _portfolioChartData.value = buildPortfolioChartData(assets)
    }

    init {
        // Recompute the summary/chart whenever the underlying table changes, for as
        // long as this ViewModel is alive — not just while the Fragment's view exists.
        netWorthAssetsLiveData.observeForever(netWorthAssetsObserver)
        // 1️⃣ Show cached data immediately (instant — no network)
        viewModelScope.launch { loadCachedPortfolio() }
        // 2️⃣ Refresh live prices in background; emit banner event if values changed.
        // Delayed slightly so this doesn't compete with the cached data above for CPU/IO
        // while the screen is still laying out and animating in — a network refresh landing
        // mid-launch was the main cause of values visibly changing right after opening the app.
        viewModelScope.launch { delay(STARTUP_REFRESH_DELAY_MS); refreshLivePricesQuietly() }
        // 3️⃣ Fetch indexes + top movers (also background)
        viewModelScope.launch { fetchIndexes(); fetchTopMovers() }
    }

    override fun onCleared() {
        super.onCleared()
        netWorthAssetsLiveData.removeObserver(netWorthAssetsObserver)
    }

    // ───────────────────────────────────────────────────────────────

    /**
     * Called on swipe-to-refresh. Shows loading indicator, re-fetches everything,
     * and applies the live values directly (no banner — user explicitly requested it).
     *
     * The spinner is meant to track real work, not a fixed timer — dismissing it early while
     * a refresh is still in flight would just bring back the "values change after the spinner
     * already said done" problem. So instead of shortening how long it shows, this shortens
     * how long the refresh actually takes: net worth, indexes, and top movers don't depend on
     * each other's results, so they now run concurrently instead of one after another — the
     * spinner's total duration becomes the slowest of the three, not their sum.
     */
    fun fetchAll() {
        viewModelScope.launch {
            _isLoading.value = true
            coroutineScope {
                // Forced — the user explicitly pulled to refresh, so this must actually run
                // rather than being skipped by the passive-refresh throttle.
                launch { netWorthRepository.refreshNetWorthAssets(force = true) }
                launch { fetchIndexes() }
                launch { fetchTopMovers() }
            }
            // Both read the DB values netWorthRepository just wrote above, so these must
            // wait for the whole coroutineScope block (and therefore all three) to finish.
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
     *
     * Fetched concurrently rather than one at a time — sequentially, a large portfolio meant
     * dozens of network round trips in a row (up to two HTTP calls each) just to populate this
     * one section, which is most of what "extensive loading" on Home launch actually was.
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

        val results = coroutineScope {
            assets.map { asset ->
                async {
                    val symbol = when (asset.assetType) {
                        AssetType.GOLD     -> "GC=F"
                        AssetType.SILVER   -> "SI=F"
                        // Same NSE-suffix fix as NetWorthRepository — a bare broker-imported
                        // ticker like "IEX" would otherwise resolve to an unrelated US company.
                        AssetType.STOCK_IN -> SymbolUtils.normaliseIndianSymbol(asset.name)
                        else               -> asset.name
                    }
                    val result = repository.fetchAndCacheStock(symbol)
                    if (result is Resource.Success) {
                        val stock = result.data
                        TopMover(
                            stock      = stock,
                            invested   = if (asset.buyPrice > 0.0) asset.buyPrice * asset.quantity else 0.0,
                            currentVal = stock.currentPrice * asset.quantity,
                            qty        = asset.quantity
                        )
                    } else null
                }
            }.awaitAll()
        }.filterNotNull()

        // Sort by absolute % change descending, take top 5
        val top5 = results.sortedByDescending { kotlin.math.abs(it.stock.changePercent) }.take(5)
        _topMovers.postValue(top5)
    }

    /**
     * Computes the portfolio P&L summary from all assets.
     * Assets with buyPrice == 0 are counted as break-even.
     * Returns null if there are no assets.
     */
    /** Pure — no DB access — so both the reactive observer and the suspend callers can share it. */
    private fun buildPortfolioSummary(assets: List<NetWorthAssetEntity>): PortfolioSummary? {
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

    private suspend fun buildPortfolioSummary(): PortfolioSummary? =
        buildPortfolioSummary(netWorthDao.getAllAssetsSync())

    private suspend fun computePortfolioSummary() {
        _portfolioSummary.postValue(buildPortfolioSummary())
    }

    /**
     * Builds a time-series of cumulative portfolio value vs invested cost-basis.
     * Assets are sorted by addedAt timestamp; each distinct calendar-day boundary
     * becomes a data point showing cumulative invested + current values up to that day.
     * We append today as the final "live" point using current market values.
     */
    /** Pure — no DB access — so both the reactive observer and the suspend callers can share it. */
    private fun buildPortfolioChartData(assets: List<NetWorthAssetEntity>): List<PortfolioChartPoint> {
        if (assets.size < 2) return emptyList()

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

        return points
    }

    private suspend fun computePortfolioChartData() {
        _portfolioChartData.postValue(buildPortfolioChartData(netWorthDao.getAllAssetsSync()))
    }
}
