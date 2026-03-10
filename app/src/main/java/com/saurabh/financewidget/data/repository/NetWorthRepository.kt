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
                // For gold/silver we fetch futures in USD, then convert to INR per gram
                val fetchSymbol = when (assetType) {
                    AssetType.GOLD   -> "GC=F"  // Gold futures (USD/troy oz)
                    AssetType.SILVER -> "SI=F"  // Silver futures (USD/troy oz)
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

                // Convert to INR if currency is USD
                val currency = meta.currency
                val priceInr = if (currency == "USD" || currency == "USX") {
                    val fxResp = apiService.getQuote("USDINR=X")
                    val usdInr = fxResp.body()?.chart?.result?.firstOrNull()?.meta?.regularMarketPrice ?: 83.0
                    priceInNativeCurrency * usdInr
                } else {
                    priceInNativeCurrency
                }

                // For gold and silver, prices are per troy ounce — convert to per gram
                val finalPrice = when (assetType) {
                    AssetType.GOLD, AssetType.SILVER -> priceInr / 31.1035 // 1 troy oz = 31.1035 g
                    else                             -> priceInr
                }

                Resource.Success(finalPrice)
            } catch (e: Exception) {
                Resource.Error(e.message ?: "Network error")
            }
        }

    suspend fun refreshNetWorthAssets(): Resource<Unit> = withContext(Dispatchers.IO) {
        try {
            val assets = netWorthDao.getAllAssetsSync()
            val fetchableTypes = setOf(AssetType.STOCK_IN, AssetType.STOCK_US, AssetType.CRYPTO, AssetType.GOLD, AssetType.SILVER)

            var hasErrors = false
            for (asset in assets) {
                if (asset.assetType in fetchableTypes && asset.name.isNotBlank()) {
                    val symbol = if (asset.assetType == AssetType.GOLD) "GC=F" else asset.name
                    val result = fetchLivePrice(symbol, asset.assetType)
                    if (result is Resource.Success) {
                        val currentPrice = result.data
                        val updatedValue = currentPrice * asset.quantity
                        netWorthDao.updateAsset(asset.copy(currentValue = updatedValue))
                    } else {
                        hasErrors = true
                    }
                }
            }

            if (hasErrors) {
                Resource.Error("Failed to update some assets")
            } else {
                Resource.Success(Unit)
            }
        } catch (e: Exception) {
            Resource.Error(e.message ?: "Unknown error during net worth refresh")
        }
    }
}
