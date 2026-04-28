package com.saurabh.financewidget.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.financewidget.data.database.AssetType
import com.saurabh.financewidget.data.database.NetWorthDao
import com.saurabh.financewidget.data.database.StockEntity
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

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: StockRepository,
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

    // ── Loading state ────────────────────────────────────────────────────
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    init {
        fetchAll()
    }

    fun fetchAll() {
        viewModelScope.launch {
            _isLoading.value = true
            fetchIndexes()
            fetchTopMovers()
            computePortfolioSummary()
            _isLoading.value = false
        }
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
     * Assets with buyPrice == 0 are counted as break-even (no artificial loss/gain).
     * Posts null if no assets have a buy price (so the card is hidden).
     */
    private suspend fun computePortfolioSummary() {
        val assets = netWorthDao.getAllAssetsSync()
        if (assets.isEmpty()) {
            _portfolioSummary.postValue(null)
            return
        }

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

        _portfolioSummary.postValue(
            PortfolioSummary(
                totalCurrent  = totalCurrent,
                totalInvested = totalInvested,
                absChange     = absChange,
                pctChange     = pct
            )
        )
    }
}
