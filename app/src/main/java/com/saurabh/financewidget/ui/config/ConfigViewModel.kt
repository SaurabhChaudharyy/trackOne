package com.saurabh.financewidget.ui.config

import androidx.lifecycle.*
import com.saurabh.financewidget.data.database.WatchlistEntity
import com.saurabh.financewidget.data.model.YahooSearchResult
import com.saurabh.financewidget.data.repository.StockRepository
import com.saurabh.financewidget.utils.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConfigViewModel @Inject constructor(
    private val repository: StockRepository
) : ViewModel() {

    val watchlist: LiveData<List<WatchlistEntity>> = repository.getWatchlist()

    private val _searchResults = MutableLiveData<Resource<List<YahooSearchResult>>>()
    val searchResults: LiveData<Resource<List<YahooSearchResult>>> = _searchResults

    private val _addState = MutableLiveData<Resource<Unit>?>()
    val addState: LiveData<Resource<Unit>?> = _addState

    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        if (query.length < 2) {
            _searchResults.value = Resource.Success(emptyList())
            return
        }

        searchJob = viewModelScope.launch {
            delay(400)
            _searchResults.value = Resource.Loading()
            _searchResults.value = repository.searchStocks(query)
        }
    }

    fun addToWatchlist(symbol: String, displayName: String, groupId: Long = StockRepository.DEFAULT_GROUP_ID) {
        viewModelScope.launch {
            _addState.value = Resource.Loading()
            try {
                repository.addToWatchlist(symbol, displayName, groupId)
                _addState.value = Resource.Success(Unit)
            } catch (e: Exception) {
                _addState.value = Resource.Error(e.message ?: "Failed to add stock")
            }
        }
    }

    suspend fun addToWatchlistSync(symbol: String, displayName: String, groupId: Long = StockRepository.DEFAULT_GROUP_ID) {
        repository.addToWatchlist(symbol, displayName, groupId)
    }

    suspend fun hasAnyStocks(): Boolean = repository.getWatchlistSync().isNotEmpty()

    fun removeFromWatchlist(symbol: String, groupId: Long = StockRepository.DEFAULT_GROUP_ID) {
        viewModelScope.launch {
            repository.removeFromWatchlist(symbol, groupId)
        }
    }

    fun clearAddState() {
        _addState.value = null
    }
}
