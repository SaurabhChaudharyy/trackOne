package com.saurabh.financewidget.ui.detail

import androidx.lifecycle.*
import com.saurabh.financewidget.data.database.PriceHistoryEntity
import com.saurabh.financewidget.data.database.StockEntity
import com.saurabh.financewidget.data.repository.StockRepository
import com.saurabh.financewidget.utils.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Yahoo Finance interval/range pairs for each timeframe button.
 * Key = display label, Value = Pair(interval, range)
 */
val TIMEFRAME_OPTIONS = linkedMapOf(
    "1D"  to Pair("5m",  "1d"),
    "1W"  to Pair("60m", "5d"),
    "1M"  to Pair("1d",  "1mo"),
    "3M"  to Pair("1d",  "3mo"),
    "1Y"  to Pair("1wk", "1y"),
    "5Y"  to Pair("1mo", "5y")
)

@HiltViewModel
class StockDetailViewModel @Inject constructor(
    private val repository: StockRepository
) : ViewModel() {

    private val _stock = MutableLiveData<StockEntity?>()
    val stock: LiveData<StockEntity?> = _stock

    private val _priceHistory = MutableLiveData<Resource<List<PriceHistoryEntity>>>()
    val priceHistory: LiveData<Resource<List<PriceHistoryEntity>>> = _priceHistory

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var currentSymbol: String = ""

    fun loadStock(symbol: String) {
        currentSymbol = symbol
        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.fetchAndCacheStock(symbol)
            when (result) {
                is Resource.Success -> {
                    _stock.value = result.data
                    _error.value = null
                }
                is Resource.Error -> {
                    _stock.value = repository.getStockSync(symbol)
                    _error.value = result.message
                }
                else -> {}
            }
            _isLoading.value = false
            loadPriceHistory("1D")
        }
    }

    /**
     * @param label One of "1D", "1W", "1M", "3M", "1Y", "5Y"
     */
    fun loadPriceHistory(label: String) {
        val (interval, range) = TIMEFRAME_OPTIONS[label] ?: Pair("1wk", "1y")
        viewModelScope.launch {
            _priceHistory.value = Resource.Loading()
            _priceHistory.value = repository.fetchPriceHistory(currentSymbol, interval, range)
        }
    }

    fun refresh() {
        if (currentSymbol.isNotEmpty()) loadStock(currentSymbol)
    }
}
