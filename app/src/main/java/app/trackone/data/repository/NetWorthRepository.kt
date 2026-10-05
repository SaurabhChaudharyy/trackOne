package app.trackone.data.repository

import app.trackone.data.api.RequestLimits
import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.AssetType
import app.trackone.data.database.FinanceDatabase
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.database.NetWorthDao
import app.trackone.data.database.hasLivePrice
import app.trackone.utils.CurrencyConversion
import app.trackone.utils.Resource
import app.trackone.utils.SymbolUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetWorthRepository @Inject constructor(
    private val database: FinanceDatabase,
    private val netWorthDao: NetWorthDao,
    private val apiService: YahooFinanceApiService,
    private val symbolRepairer: SymbolRepairer,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NetWorthRepository"

        private const val PREFS_NAME = "networth_fx_cache"
        private const val KEY_LAST_USD_INR_RATE = "last_usd_inr_rate"

        /** Absolute last resort — used only when a live quote fails AND no rate has ever
         *  been cached (e.g. very first launch with no network). Everything else degrades
         *  to the last real rate this device actually saw, via [lastKnownUsdInrRate]. */
        private const val FALLBACK_USD_INR_RATE = 83.0

        /** How often a passive (non-[force]d) refresh is allowed to actually hit the network.
         *  Several screens each ask for a refresh as soon as they load (Home, Watchlist, this
         *  repository's own consumers) — without this, a cold app launch fired that whole batch
         *  of per-asset network calls concurrently and again on every tab switch, which is both
         *  wasteful and why portfolio values visibly flickered right after opening the app. */
        private const val MIN_PASSIVE_REFRESH_INTERVAL_MS = 5 * 60 * 1000L

        /** How long a non-USD →INR rate is reused before it is fetched again. */
        private const val FX_RATE_TTL_MS = 10 * 60 * 1000L

        /** Asset types that have a live market price (vs. MF/cash/bank, which are entered by hand). */
        val FETCHABLE_TYPES: Set<AssetType> = AssetType.values().filter { it.hasLivePrice }.toSet()
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** Serializes refreshes so concurrent callers (e.g. Home and Watchlist both loading at
     *  once on app launch) don't run two full passes over every asset at the same time —
     *  the second caller waits, then sees [lastRefreshAt] already fresh and skips outright. */
    private val refreshMutex = Mutex()
    @Volatile private var lastRefreshAt = 0L

    /** Holding id to the value the latest background refresh wrote; empty after one the user asked
     *  for. Home holds exactly these changes behind its "Portfolio updated" banner. */
    @Volatile var lastBackgroundWrite: Map<Long, Double> = emptyMap()
        private set

    /** The most recent live USD→INR rate this device successfully fetched, or the hardcoded
     *  [FALLBACK_USD_INR_RATE] if none has ever been cached. */
    private fun lastKnownUsdInrRate(): Double =
        prefs.getFloat(KEY_LAST_USD_INR_RATE, FALLBACK_USD_INR_RATE.toFloat()).toDouble()

    private fun cacheUsdInrRate(rate: Double) {
        prefs.edit().putFloat(KEY_LAST_USD_INR_RATE, rate.toFloat()).apply()
    }

    /** A live price and yesterday's close, both in INR per unit. [previousCloseInr] is 0.0 when
     *  the quote didn't carry one — callers must treat that as "unknown", not "unchanged". */
    data class LiveQuote(val priceInr: Double, val previousCloseInr: Double)

    suspend fun fetchLivePrice(
        symbol: String,
        assetType: AssetType,
        usdInrRate: Double? = null
    ): Resource<Double> = when (val quote = fetchQuote(symbol, assetType, usdInrRate)) {
        is Resource.Success -> Resource.Success(quote.data.priceInr)
        is Resource.Error   -> Resource.Error(quote.message)
        is Resource.Loading -> Resource.Loading()
    }

    suspend fun fetchQuote(
        symbol: String,
        assetType: AssetType,
        usdInrRate: Double? = null
    ): Resource<LiveQuote> =
        withContext(Dispatchers.IO) {
            try {

                val fetchSymbol = when (assetType) {
                    AssetType.GOLD     -> "GC=F"
                    AssetType.SILVER   -> "SI=F"
                    AssetType.STOCK_IN -> SymbolUtils.normaliseIndianSymbol(symbol)
                    else               -> symbol.trim().uppercase()
                }

                val resp = apiService.getQuote(fetchSymbol)
                if (!resp.isSuccessful) {
                    return@withContext Resource.Error("HTTP ${resp.code()}: ${resp.message()}")
                }

                val meta = resp.body()?.chart?.result?.firstOrNull()?.meta
                    ?: return@withContext Resource.Error("No data for $fetchSymbol")

                val priceInNativeCurrency = meta.regularMarketPrice
                if (priceInNativeCurrency <= 0) {
                    return@withContext Resource.Error("Invalid price returned")
                }

                val currency = meta.currency
                // Reuse a batch-level USD rate when the caller already fetched one, instead
                // of issuing a fresh USDINR=X call per asset. Any other currency gets its own
                // rate; with none, the quote fails rather than counting pence or CAD as rupees.
                val major = CurrencyConversion.majorCurrency(currency)
                val rateToInr = when (major) {
                    "USD" -> usdInrRate ?: fetchUsdInrRate()
                    else  -> fetchRateToInr(major)
                        ?: return@withContext Resource.Error("No $major to INR rate for $fetchSymbol")
                }

                // Price and previous close go through the same conversion so their ratio
                // (the day's move) is unaffected by FX, units or cents-vs-dollars.
                val isMetal = assetType == AssetType.GOLD || assetType == AssetType.SILVER
                val toFinalPrice = { native: Double -> CurrencyConversion.perUnitInr(native, currency, rateToInr, isMetal) }

                Resource.Success(
                    LiveQuote(
                        priceInr = toFinalPrice(priceInNativeCurrency),
                        previousCloseInr = meta.effectivePreviousClose
                            .takeIf { it > 0.0 }?.let(toFinalPrice) ?: 0.0
                    )
                )
            } catch (e: Exception) {
                Resource.Error(e.message ?: "Network error")
            }
        }

    /**
     * Live quotes for every market-priced holding in [assets], keyed by asset id. Holdings whose
     * quote fails are simply absent from the map. One USD→INR fetch is shared across the batch.
     */
    suspend fun fetchQuotesFor(assets: List<NetWorthAssetEntity>): Map<Long, LiveQuote> {
        val fetchable = assets.filter { it.assetType in FETCHABLE_TYPES && it.name.isNotBlank() }
        if (fetchable.isEmpty()) return emptyMap()

        val usdInrRate = fetchUsdInrRate()
        val quotes = mutableMapOf<Long, LiveQuote>()
        for (asset in fetchable) {
            val result = fetchQuote(asset.name, asset.assetType, usdInrRate)
            if (result is Resource.Success) quotes[asset.id] = result.data
        }
        return quotes
    }

    /**
     * Fetches the current USD→INR exchange rate. On success, caches it so future failures
     * degrade to this real rate instead of the hardcoded [FALLBACK_USD_INR_RATE].
     */
    suspend fun fetchUsdInrRate(): Double = try {
        val fxResp = apiService.getQuote("USDINR=X")
        val liveRate = fxResp.body()?.chart?.result?.firstOrNull()?.meta?.regularMarketPrice
        if (liveRate != null && liveRate > 0) {
            cacheUsdInrRate(liveRate)
            liveRate
        } else {
            lastKnownUsdInrRate()
        }
    } catch (e: Exception) { lastKnownUsdInrRate() }

    /** Other currencies' live →INR rates, held briefly so a batch of GBP holdings fetches GBPINR once. */
    private val fxRateCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Double>>()

    /**
     * The live rate converting one unit of [currency] (a major currency: GBP, not GBp) into INR,
     * or null when Yahoo has none. INR is 1 and USD uses [fetchUsdInrRate] with its cached fallback.
     */
    suspend fun fetchRateToInr(currency: String): Double? {
        if (currency == "INR") return 1.0
        if (currency == "USD") return fetchUsdInrRate()
        val now = System.currentTimeMillis()
        fxRateCache[currency]?.let { (at, rate) -> if (now - at < FX_RATE_TTL_MS) return rate }
        val rate = try {
            apiService.getQuote("${currency}INR=X").body()?.chart?.result?.firstOrNull()?.meta?.regularMarketPrice
        } catch (e: Exception) { null }
        return rate?.takeIf { it > 0.0 }?.also { fxRateCache[currency] = now to it }
    }

    /**
     * [asset] with its live price applied. If the row's buyPrice is still in USD (first refresh after
     * an import) it is converted to INR and the currency flipped, so P&L compares like with like.
     */
    /** The result of pricing a batch: each holding that got a live price, and each that didn't. */
    internal class PricedHoldings(val updated: List<NetWorthAssetEntity>, val failed: List<NetWorthAssetEntity>)

    /**
     * Fetches a live price for every holding, a few at a time. One after another, 78 holdings took
     * about 12 seconds, which kept Home's spinner up that long (and made a pull to refresh wait
     * behind the startup one). The result keeps [assets]' order whichever quote answers first, and
     * [onProgress] is called once per holding with a count that only ever goes up.
     */
    internal suspend fun priceHoldings(
        assets: List<NetWorthAssetEntity>,
        usdInrRate: Double,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): PricedHoldings = coroutineScope {
        val limiter = Semaphore(RequestLimits.MAX_PARALLEL)
        val progressLock = Any()
        var done = 0
        val outcomes = assets.map { asset ->
            async {
                val symbol = if (asset.assetType == AssetType.GOLD) "GC=F" else asset.name
                val result = limiter.withPermit { fetchLivePrice(symbol, asset.assetType, usdInrRate) }
                synchronized(progressLock) { onProgress(++done, assets.size) }
                asset to result
            }
        }.awaitAll()

        val updated = mutableListOf<NetWorthAssetEntity>()
        val failed = mutableListOf<NetWorthAssetEntity>()
        for ((asset, result) in outcomes) {
            if (result is Resource.Success) {
                updated.add(withLivePrice(asset, result.data, usdInrRate))
            } else if (result is Resource.Error) {
                Log.w(TAG, "refreshNetWorthAssets: a holding could not be priced: ${result.message}")
                failed.add(asset)
            }
        }
        PricedHoldings(updated, failed)
    }

    private fun withLivePrice(asset: NetWorthAssetEntity, priceInr: Double, usdInrRate: Double): NetWorthAssetEntity {
        val (buyPrice, currency) = if (asset.currency == "USD") {
            val buyPriceInr = if (asset.buyPrice > 0) CurrencyConversion.toInr(asset.buyPrice, asset.currency, usdInrRate) else 0.0
            buyPriceInr to "INR"
        } else {
            asset.buyPrice to asset.currency
        }
        return asset.copy(
            currentValue = priceInr * asset.quantity,
            buyPrice     = buyPrice,
            currency     = currency,
            updatedAt    = System.currentTimeMillis()
        )
    }

    /**
     * Refreshes live prices for all fetchable assets, best-effort: a failure on one asset
     * (or the whole batch) is logged and swallowed, never surfaced to callers — every call
     * site treats this as fire-and-forget, so the interface says so instead of returning
     * an error type nothing reads.
     *
     * Passive callers (a screen refreshing as soon as it loads) are throttled to once every
     * [MIN_PASSIVE_REFRESH_INTERVAL_MS] — pass [force] = true for a refresh the user explicitly
     * asked for (pull-to-refresh, right after a CSV import or cloud restore), which always runs.
     *
     * All the DB writes land in a single transaction at the end, after every network call has
     * finished — not one `updateAsset` per asset as it's fetched. Room invalidates a table's
     * LiveData observers once per transaction, not once per statement: writing asset-by-asset
     * fired [NetWorthDao.getAllAssets] (and everything downstream of it — Home's portfolio
     * summary, and its chart) once per asset, which is what looked like the stock list and
     * chart "reloading" repeatedly on every refresh instead of updating once when it's done.
     *
     * @param userRequested whether the user asked for this refresh. Its prices then show at once
     * on Home; a background one's are held behind the "Portfolio updated" banner. Defaults to
     * [force]; the periodic worker is forced but not requested.
     * @param onProgress called after each asset is (attempted to be) refreshed, with
     * (assets refreshed so far, total fetchable assets) — lets a caller like the CSV
     * import flow show a percentage instead of an indefinite spinner while this runs
     * its sequential per-asset network calls, which is the slow part of a large import.
     */
    suspend fun refreshNetWorthAssets(
        force: Boolean = false,
        userRequested: Boolean = force,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Unit = refreshMutex.withLock {
        // Checked inside the lock: if another caller was already mid-refresh when this one
        // arrived, lastRefreshAt is now fresh and this call can skip instead of duplicating it.
        if (!force && System.currentTimeMillis() - lastRefreshAt < MIN_PASSIVE_REFRESH_INTERVAL_MS) {
            return@withLock
        }

        withContext(Dispatchers.IO) {
        try {
            val assets = netWorthDao.getAllAssetsSync()
            val fetchableAssets = assets.filter { it.assetType in FETCHABLE_TYPES && it.name.isNotBlank() }

            // Fetch USDINR once for the whole batch — reused below for both live-price
            // conversion and buyPrice conversion, instead of being re-fetched per asset.
            val usdInrRate = fetchUsdInrRate()

            // Collected here, not written per-quote — see the transaction below for why.
            val priced = priceHoldings(fetchableAssets, usdInrRate, onProgress)
            val updatedAssets = priced.updated.toMutableList()

            // Holdings whose live price could not be fetched, kept for the symbol repair below.
            val failed = priced.failed

            // A holding stored under a company name (or a wrong symbol) can never be priced. Give the
            // failures one guarded attempt at a rename — and price the renamed ones right away. Only
            // tried when something in this batch DID price, so a dead network doesn't send every row
            // to the resolver.
            if (failed.isNotEmpty() && updatedAssets.isNotEmpty()) {
                for (repair in symbolRepairer.findRepairs(failed, assets)) {
                    Log.i(TAG, "symbol repair: renamed a holding (matched by ${repair.basis})")
                    val renamed = assets.first { it.id == repair.assetId }.copy(name = repair.newName)
                    val price = fetchLivePrice(renamed.name, renamed.assetType, usdInrRate)
                    updatedAssets.add(if (price is Resource.Success) withLivePrice(renamed, price.data, usdInrRate) else renamed)
                }
            }

            // Set before the write, so it's in place when Home's observer sees the new rows.
            lastBackgroundWrite = if (userRequested) emptyMap() else updatedAssets.associate { it.id to it.currentValue }

            // One transaction for the whole batch — network calls are already done by this
            // point, so this is a fast, purely-local write and doesn't hold the DB lock
            // across any I/O.
            if (updatedAssets.isNotEmpty()) {
                database.withTransaction {
                    for (updated in updatedAssets) {
                        netWorthDao.updateAsset(updated)
                    }
                }
            } else {
                Unit
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshNetWorthAssets: batch failed", e)
        }
        }

        lastRefreshAt = System.currentTimeMillis()
    }
}
