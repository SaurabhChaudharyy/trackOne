package com.saurabh.financewidget.data.repository

import com.saurabh.financewidget.data.api.YahooFinanceApiService
import com.saurabh.financewidget.data.database.AssetType
import com.saurabh.financewidget.data.database.NetWorthDao
import com.saurabh.financewidget.utils.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetWorthRepository @Inject constructor(
    private val netWorthDao: NetWorthDao,
    private val apiService: YahooFinanceApiService
) {
    suspend fun fetchLivePrice(symbol: String, assetType: AssetType): Resource<Double> =
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
                    val fxResp = apiService.getQuote("USDINR=X")
                    val usdInr = fxResp.body()?.chart?.result?.firstOrNull()?.meta?.regularMarketPrice ?: 83.0
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
     * Fetches the current USD→INR exchange rate.
     * Falls back to 85.0 if the network call fails.
     */
    suspend fun fetchUsdInrRate(): Double = try {
        val fxResp = apiService.getQuote("USDINR=X")
        fxResp.body()?.chart?.result?.firstOrNull()?.meta?.regularMarketPrice ?: 85.0
    } catch (e: Exception) { 85.0 }

    suspend fun refreshNetWorthAssets(): Resource<Unit> = withContext(Dispatchers.IO) {
        try {
            val assets = netWorthDao.getAllAssetsSync()
            val fetchableTypes = setOf(
                AssetType.STOCK_IN, AssetType.STOCK_US, AssetType.CRYPTO,
                AssetType.GOLD, AssetType.SILVER
            )

            // Fetch USDINR once for the whole batch — used to also convert buyPrice for USD assets.
            val usdInrRate = fetchUsdInrRate()

            var hasErrors = false
            for (asset in assets) {
                if (asset.assetType !in fetchableTypes || asset.name.isBlank()) continue

                val symbol = if (asset.assetType == AssetType.GOLD) "GC=F" else asset.name
                val result = fetchLivePrice(symbol, asset.assetType)

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
                } else {
                    hasErrors = true
                }
            }

            if (hasErrors) Resource.Error("Failed to update some assets")
            else           Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Unknown error during net worth refresh")
        }
    }
}
