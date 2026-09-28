package app.trackone.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.viewModelScope
import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.database.NetWorthDao
import app.trackone.data.database.StockEntity
import app.trackone.data.repository.ChartSnapshotStore
import app.trackone.data.repository.NetWorthRepository
import app.trackone.data.repository.PortfolioHistoryRepository
import app.trackone.data.repository.StockRepository
import app.trackone.utils.ChartRange
import app.trackone.utils.PortfolioGainLoss
import app.trackone.utils.Resource
import app.trackone.utils.SymbolUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
 * [invested] is 0 when the user has no buy price recorded. [currentVal] is the holding's value
 * in INR, or 0 when that isn't known (see [buildTopMover]).
 */
data class TopMover(
    val stock: StockEntity,
    val label: String,      // what to show as the title — see buildTopMover
    val invested: Double,   // buyPrice * quantity (0 if no buy price)
    val currentVal: Double, // the holding's INR value (0 if unknown)
    val qty: Double,
    /** ₹ per gram for gold/silver (the unit the holding is measured in); null for everything else. */
    val unitPriceInr: Double?
)

/**
 * The holding's value comes from the stored INR [NetWorthAssetEntity.currentValue], not from
 * `stock.currentPrice * quantity`: a quote is in its own currency and unit (gold/silver futures
 * are USD per troy OUNCE, while the holding is in GRAMS), so that product was both the wrong
 * currency and the wrong unit. A row still labelled with a foreign currency hasn't been
 * converted yet, so it reports 0 (unknown) rather than a number carrying the wrong symbol.
 */
internal fun buildTopMover(asset: NetWorthAssetEntity, stock: StockEntity): TopMover = TopMover(
    stock      = stock,
    label      = when (asset.assetType) {
        AssetType.GOLD   -> "Gold"
        AssetType.SILVER -> "Silver"
        else             -> SymbolUtils.displaySymbol(stock.symbol)
    },
    invested   = if (asset.buyPrice > 0.0) asset.buyPrice * asset.quantity else 0.0,
    currentVal = if (asset.currency == "INR") asset.currentValue else 0.0,
    qty        = asset.quantity,
    unitPriceInr = when {
        asset.assetType != AssetType.GOLD && asset.assetType != AssetType.SILVER -> null
        asset.currency != "INR" || asset.quantity <= 0.0 -> null
        else -> asset.currentValue / asset.quantity
    }
)

/**
 * Whether Home holds [latest] behind its "Portfolio updated" banner instead of showing it: only
 * when the sole difference from what's on screen ([shown]) is prices a background refresh wrote
 * ([backgroundWrite], holding id to value) and the total moved by more than ₹1. An edit, an
 * import, a rename or a refresh the user asked for shows at once.
 */
internal fun holdBehindBanner(
    shown: List<NetWorthAssetEntity>,
    latest: List<NetWorthAssetEntity>,
    backgroundWrite: Map<Long, Double>
): Boolean {
    if (shown.size != latest.size) return false
    val before = shown.associateBy { it.id }
    val onlyBackgroundPrices = latest.all { now ->
        val then = before[now.id] ?: return false
        now.copy(currentValue = then.currentValue, updatedAt = then.updatedAt) == then &&
            (now.currentValue == then.currentValue || backgroundWrite[now.id] == now.currentValue)
    }
    val moved = kotlin.math.abs(PortfolioGainLoss.compute(latest).current - PortfolioGainLoss.compute(shown).current)
    return onlyBackgroundPrices && moved > 1.0
}

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
 * One point on the Home chart: what today's holdings were worth on [timestamp]'s day.
 * [current] is that day's total value in INR; [invested] is today's cost basis (constant).
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
    private val netWorthDao: NetWorthDao,
    private val portfolioHistoryRepository: PortfolioHistoryRepository,
    private val chartSnapshots: ChartSnapshotStore
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
    // distinctUntilChanged — cold start can fire the reactive net-worth observer below more than
    // once in quick succession (Home's own passive refresh plus another screen's, e.g. NetWorth's,
    // both writing to the same table while the app is still loading). Each write is its own single
    // recompute, but without this the Fragment still re-renders (and re-animates the chart) once
    // per recompute even when the recomputed value is byte-for-byte the same as what's on screen —
    // which is what looked like the chart "reloading" repeatedly instead of settling once.
    val portfolioSummary: LiveData<PortfolioSummary?> = _portfolioSummary.distinctUntilChanged()

    // ── Portfolio Chart data ────────────────────────────────────────────
    private val _portfolioChartData = MutableLiveData<List<PortfolioChartPoint>>(emptyList())
    val portfolioChartData: LiveData<List<PortfolioChartPoint>> = _portfolioChartData.distinctUntilChanged()

    private val _chartRange = MutableLiveData(ChartRange.MONTH)
    val chartRange: LiveData<ChartRange> = _chartRange

    private val _chartLoading = MutableLiveData(false)
    val chartLoading: LiveData<Boolean> = _chartLoading

    private var chartJob: Job? = null

    /** The holdings the summary and chart on screen were built from; null until the first load. */
    private var shownAssets: List<NetWorthAssetEntity>? = null
    /** A background price move held behind the "Portfolio updated" banner until the user taps it. */
    private var heldAssets: List<NetWorthAssetEntity>? = null

    fun setChartRange(range: ChartRange) {
        if (range == _chartRange.value) return
        _chartRange.value = range
        rebuildChart(shownAssets.orEmpty())
    }

    /**
     * Rebuilds the chart for the selected range. Each call cancels the one before it, so a quick
     * run of range taps (or a price refresh landing mid-fetch) can't apply a stale result over a
     * newer one. The fetches themselves are cached per symbol+range by the repository.
     */
    private fun rebuildChart(assets: List<NetWorthAssetEntity>) {
        chartJob?.cancel()
        if (assets.isEmpty()) {
            _chartLoading.value = false
            _portfolioChartData.value = emptyList()
            return
        }
        val range = _chartRange.value ?: ChartRange.MONTH
        // First draw of this range: show the last chart saved for it right away, ending on today's
        // total, while the fresh one downloads. A later rebuild of the same range keeps what's drawn.
        if (drawnRange != range) {
            chartSnapshots.load(range)?.let { saved ->
                val total = assets.sumOf { it.currentValue }
                _portfolioChartData.value = saved.dropLast(1) + saved.last().copy(current = total)
                drawnRange = range
            }
        }
        // A chart already drawn for this range refreshes quietly; only an empty one shows loading.
        val quiet = drawnRange == range
        chartJob = viewModelScope.launch {
            _chartLoading.value = !quiet
            val points = portfolioHistoryRepository.history(assets, range)
            _portfolioChartData.value = points
            drawnRange = range
            if (points.size >= 2) chartSnapshots.save(range, points)
            _chartLoading.value = false
        }
    }

    /** The range whose chart is on screen, so a rebuild knows whether a saved snapshot helps. */
    private var drawnRange: ChartRange? = null

    // ── Held background move (shows the "Portfolio updated" banner) ─────
    /** The held move's summary while the banner is up; null otherwise. */
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
    // A background price refresh is the exception: it's held behind the banner (holdBehindBanner),
    // so the total doesn't change under the user.
    private val netWorthAssetsObserver = Observer<List<NetWorthAssetEntity>> { assets ->
        val shown = shownAssets
        if (shown != null && holdBehindBanner(shown, assets, netWorthRepository.lastBackgroundWrite)) {
            heldAssets = assets
            _portfolioRefreshed.value = buildPortfolioSummary(assets)
        } else {
            show(assets)
        }
    }

    init {
        // Recompute the summary/chart whenever the underlying table changes, for as
        // long as this ViewModel is alive — not just while the Fragment's view exists.
        // Its first delivery is the cached data (instant — no network).
        netWorthAssetsLiveData.observeForever(netWorthAssetsObserver)
        // Refresh live prices in background; a move is held behind the banner (see above).
        // Delayed slightly so this doesn't compete with the cached data above for CPU/IO
        // while the screen is still laying out and animating in.
        viewModelScope.launch { delay(STARTUP_REFRESH_DELAY_MS); refreshLivePricesQuietly() }
        // Fetch indexes + top movers (also background)
        viewModelScope.launch { fetchIndexes() }
        viewModelScope.launch { fetchTopMovers() }
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
            // Reads the DB values netWorthRepository just wrote above, so this must wait for
            // the whole coroutineScope block (and therefore all three) to finish. Also applies
            // any move held behind the banner.
            show(netWorthDao.getAllAssetsSync())
            _isLoading.value = false
        }
    }

    /**
     * Silently refreshes live prices from the network. Doesn't touch the screen: when it writes,
     * the observer holds a move of more than ₹1 behind the "Portfolio updated" banner, and the
     * user taps it to apply.
     */
    private suspend fun refreshLivePricesQuietly() {
        netWorthRepository.refreshNetWorthAssets()
    }

    /** Called from the Fragment when the user taps the "Portfolio updated" banner. */
    fun applyRefreshedPortfolio() {
        show(heldAssets ?: return)
    }

    /** Puts [assets] on screen (summary and chart) and drops any held move, which [assets] supersedes. */
    private fun show(assets: List<NetWorthAssetEntity>) {
        shownAssets = assets
        heldAssets = null
        _portfolioRefreshed.value = null
        _portfolioSummary.value = buildPortfolioSummary(assets)
        rebuildChart(assets)
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

        fun symbolOf(asset: NetWorthAssetEntity) = when (asset.assetType) {
            AssetType.GOLD     -> "GC=F"
            AssetType.SILVER   -> "SI=F"
            // Same NSE-suffix fix as NetWorthRepository — a bare broker-imported
            // ticker like "IEX" would otherwise resolve to an unrelated US company.
            AssetType.STOCK_IN -> SymbolUtils.normaliseIndianSymbol(asset.name)
            else               -> asset.name
        }
        fun topFive(movers: List<TopMover>) = movers.sortedByDescending { kotlin.math.abs(it.stock.changePercent) }.take(5)

        // The last quotes saved on this device first, so the cards show at once; live ones replace them.
        val cached = assets.mapNotNull { asset -> repository.getStockSync(symbolOf(asset))?.let { buildTopMover(asset, it) } }
        if (cached.isNotEmpty()) _topMovers.postValue(topFive(cached))

        val results = coroutineScope {
            assets.map { asset ->
                async {
                    val result = repository.fetchAndCacheStock(symbolOf(asset))
                    if (result is Resource.Success) buildTopMover(asset, result.data) else null
                }
            }.awaitAll()
        }.filterNotNull()

        if (results.isNotEmpty() || cached.isEmpty()) _topMovers.postValue(topFive(results))
    }

    /**
     * Computes the portfolio P&L summary from all assets.
     * Assets with buyPrice == 0 are counted as break-even.
     * Returns null if there are no assets.
     */
    private fun buildPortfolioSummary(assets: List<NetWorthAssetEntity>): PortfolioSummary? {
        if (assets.isEmpty()) return null
        val gainLoss = PortfolioGainLoss.compute(assets)
        return PortfolioSummary(
            totalCurrent  = gainLoss.current,
            totalInvested = gainLoss.invested,
            absChange     = gainLoss.absChange,
            pctChange     = gainLoss.pctChange
        )
    }
}
