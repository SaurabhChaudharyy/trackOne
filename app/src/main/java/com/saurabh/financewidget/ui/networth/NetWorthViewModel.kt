package com.saurabh.financewidget.ui.networth

import androidx.lifecycle.*
import com.saurabh.financewidget.data.api.YahooFinanceApiService
import com.saurabh.financewidget.data.database.AssetType
import com.saurabh.financewidget.data.database.NetWorthAssetEntity
import com.saurabh.financewidget.data.database.NetWorthDao
import com.saurabh.financewidget.utils.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class NetWorthViewModel @Inject constructor(
    private val netWorthDao: NetWorthDao,
    private val apiService: YahooFinanceApiService
) : ViewModel() {

    val allAssets: LiveData<List<NetWorthAssetEntity>> = netWorthDao.getAllAssets()
    val totalNetWorth: LiveData<Double?> = netWorthDao.getTotalNetWorth()

    fun addAsset(asset: NetWorthAssetEntity) = viewModelScope.launch {
        netWorthDao.insertAsset(asset)
    }

    fun updateAsset(asset: NetWorthAssetEntity) = viewModelScope.launch {
        netWorthDao.updateAsset(asset)
    }

    fun deleteAsset(asset: NetWorthAssetEntity) = viewModelScope.launch {
        netWorthDao.deleteAsset(asset)
    }

    /** Aggregated current value per asset type */
    val assetSummary: LiveData<Map<AssetType, Double>> = allAssets.map { assets ->
        assets.groupBy { it.assetType }
            .mapValues { (_, list) -> list.sumOf { it.currentValue } }
    }

    /**
     * Fetches the current price (in INR) for a symbol from Yahoo Finance.
     *
     * - Stocks/Crypto  → uses symbol directly (e.g. RELIANCE.NS, BTC-INR)
     * - Gold           → uses GC=F (gold futures in USD) and converts at USD/INR
     *
     * Returns Resource.Success(pricePerUnit) or Resource.Error(message).
     */
    suspend fun fetchLivePrice(symbol: String, assetType: AssetType): Resource<Double> =
        withContext(Dispatchers.IO) {
            try {
                // For gold we fetch gold futures (GC=F) in USD, then convert
                val fetchSymbol = if (assetType == AssetType.GOLD) "GC=F" else symbol.trim().uppercase()

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

                // For gold, GC=F is priced per troy ounce — convert to per gram
                val finalPrice = if (assetType == AssetType.GOLD) {
                    priceInr / 31.1035 // 1 troy oz = 31.1035 grams
                } else {
                    priceInr
                }

                Resource.Success(finalPrice)
            } catch (e: Exception) {
                Resource.Error(e.message ?: "Network error")
            }
        }
}
