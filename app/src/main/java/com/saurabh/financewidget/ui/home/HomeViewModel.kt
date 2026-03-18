package com.saurabh.financewidget.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: StockRepository
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

    // ── Top Mover ───────────────────────────────────────────────────────
    private val _topMover = MutableLiveData<StockEntity?>()
    val topMover: LiveData<StockEntity?> = _topMover

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    init {
        fetchAll()
    }

    fun fetchAll() {
        viewModelScope.launch {
            _isLoading.value = true
            fetchIndexes()
            fetchTopMover()
            _isLoading.value = false
        }
    }

    private suspend fun fetchIndexes() {
        // Fetch all 4 indexes concurrently
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

    private suspend fun fetchTopMover() {
        val stocks = repository.getWatchlistSync()
        if (stocks.isEmpty()) {
            _topMover.postValue(null)
            return
        }
        // Pick the stock with the largest absolute % change
        val top = stocks.maxByOrNull { kotlin.math.abs(it.changePercent) }
        _topMover.postValue(top)
    }
}
