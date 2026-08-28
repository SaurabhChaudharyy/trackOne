package app.trackone.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.AssetType
import app.trackone.data.database.FinanceDatabase
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.database.NetWorthDao
import app.trackone.utils.Resource
import app.trackone.utils.SymbolUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** Serializes refreshes so concurrent callers (e.g. Home and Watchlist both loading at
     *  once on app launch) don't run two full passes over every asset at the same time —
     *  the second caller waits, then sees [lastRefreshAt] already fresh and skips outright. */
    private val refreshMutex = Mutex()
    @Volatile private var lastRefreshAt = 0L

    /** The most recent live USD→INR rate this device successfully fetched, or the hardcoded
     *  [FALLBACK_USD_INR_RATE] if none has ever been cached. */
    private fun lastKnownUsdInrRate(): Double =
        prefs.getFloat(KEY_LAST_USD_INR_RATE, FALLBACK_USD_INR_RATE.toFloat()).toDouble()

    private fun cacheUsdInrRate(rate: Double) {
        prefs.edit().putFloat(KEY_LAST_USD_INR_RATE, rate.toFloat()).apply()
    }

    suspend fun fetchLivePrice(
        symbol: String,
        assetType: AssetType,
        usdInrRate: Double? = null
    ): Resource<Double> =
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
                val priceInr = if (currency == "USD" || currency == "USX") {
                    // Reuse a batch-level rate when the caller already fetched one, instead
                    // of issuing a fresh USDINR=X call per asset.
                    val usdInr = usdInrRate ?: fetchUsdInrRate()
                    priceInNativeCurrency * usdInr
                } else {
                    priceInNativeCurrency
                }

                val finalPrice = when (assetType) {
                    AssetType.GOLD, AssetType.SILVER -> priceInr / 31.1035
                    else                             -> priceInr
                }

                Resource.Success(finalPrice)
            } catch (e: Exception) {
                Resource.Error(e.message ?: "Network error")
            }
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
     * @param onProgress called after each asset is (attempted to be) refreshed, with
     * (assets refreshed so far, total fetchable assets) — lets a caller like the CSV
     * import flow show a percentage instead of an indefinite spinner while this runs
     * its sequential per-asset network calls, which is the slow part of a large import.
     */
    suspend fun refreshNetWorthAssets(
        force: Boolean = false,
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
            val fetchableTypes = setOf(
                AssetType.STOCK_IN, AssetType.STOCK_US, AssetType.CRYPTO,
                AssetType.GOLD, AssetType.SILVER
            )
            val fetchableAssets = assets.filter { it.assetType in fetchableTypes && it.name.isNotBlank() }

            // Fetch USDINR once for the whole batch — reused below for both live-price
            // conversion and buyPrice conversion, instead of being re-fetched per asset.
            val usdInrRate = fetchUsdInrRate()

            // Collected here, not written per-iteration — see the transaction below for why.
            val updatedAssets = mutableListOf<NetWorthAssetEntity>()

            for ((index, asset) in fetchableAssets.withIndex()) {
                val symbol = if (asset.assetType == AssetType.GOLD) "GC=F" else asset.name
                val result = fetchLivePrice(symbol, asset.assetType, usdInrRate)

                if (result is Resource.Success) {
                    val currentPriceInr = result.data   // already in INR
                    val updatedValue    = currentPriceInr * asset.quantity

                    // If the asset's buyPrice is still in USD (first refresh after import),
                    // convert it to INR and flip currency to "INR" so P&L is apples-to-apples.
                    val (updatedBuyPrice, updatedCurrency) = if (asset.currency == "USD") {
                        val buyPriceInr = if (asset.buyPrice > 0) asset.buyPrice * usdInrRate else 0.0
                        Pair(buyPriceInr, "INR")
                    } else {
                        Pair(asset.buyPrice, asset.currency)
                    }

                    updatedAssets.add(
                        asset.copy(
                            currentValue = updatedValue,
                            buyPrice     = updatedBuyPrice,
                            currency     = updatedCurrency,
                            updatedAt    = System.currentTimeMillis()
                        )
                    )
                } else if (result is Resource.Error) {
                    Log.w(TAG, "refreshNetWorthAssets: failed to update ${asset.name}: ${result.message}")
                }

                onProgress(index + 1, fetchableAssets.size)
            }

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
