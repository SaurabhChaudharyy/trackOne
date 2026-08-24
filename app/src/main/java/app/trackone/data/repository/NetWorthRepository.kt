package app.trackone.data.repository

import android.content.Context
import android.util.Log
import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthDao
import app.trackone.utils.Resource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetWorthRepository @Inject constructor(
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
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

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
                    AssetType.GOLD   -> "GC=F"
                    AssetType.SILVER -> "SI=F"
                    else             -> symbol.trim().uppercase()
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
     */
    suspend fun refreshNetWorthAssets(): Unit = withContext(Dispatchers.IO) {
        try {
            val assets = netWorthDao.getAllAssetsSync()
            val fetchableTypes = setOf(
                AssetType.STOCK_IN, AssetType.STOCK_US, AssetType.CRYPTO,
                AssetType.GOLD, AssetType.SILVER
            )

            // Fetch USDINR once for the whole batch — reused below for both live-price
            // conversion and buyPrice conversion, instead of being re-fetched per asset.
            val usdInrRate = fetchUsdInrRate()

            for (asset in assets) {
                if (asset.assetType !in fetchableTypes || asset.name.isBlank()) continue

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

                    netWorthDao.updateAsset(
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
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshNetWorthAssets: batch failed", e)
        }
    }
}
