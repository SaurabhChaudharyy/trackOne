package com.saurabh.financewidget.ui.main

import androidx.lifecycle.*
import com.saurabh.financewidget.data.database.StockEntity
import com.saurabh.financewidget.data.database.WatchlistEntity
import com.saurabh.financewidget.data.repository.StockRepository
import com.saurabh.financewidget.utils.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: StockRepository
) : ViewModel() {

    val watchlistStocks: LiveData<List<StockEntity>> = repository.getWatchlistStocks()
    val watchlist: LiveData<List<WatchlistEntity>> = repository.getWatchlist()

    private val _refreshState = MutableLiveData<Resource<Unit>>()
    val refreshState: LiveData<Resource<Unit>> = _refreshState

    private val _isRefreshing = MutableLiveData<Boolean>(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _refreshState.value = Resource.Loading()
            val result = repository.refreshWatchlistStocks()
            _refreshState.value = result
            _isRefreshing.value = false
        }
    }

    fun removeFromWatchlist(symbol: String) {
        viewModelScope.launch {
            repository.removeFromWatchlist(symbol)
        }
    }

    fun addToWatchlist(symbol: String, displayName: String) {
        viewModelScope.launch {
            repository.addToWatchlist(symbol, displayName)
        }
    }

    fun reorderWatchlist(items: List<WatchlistEntity>) {
        viewModelScope.launch {
            repository.updateWatchlistOrder(items)
        }
    }
}
