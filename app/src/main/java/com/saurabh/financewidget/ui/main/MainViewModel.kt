package com.saurabh.financewidget.ui.main

import androidx.lifecycle.*
import com.saurabh.financewidget.data.database.StockEntity
import com.saurabh.financewidget.data.database.WatchlistEntity
import com.saurabh.financewidget.data.database.WatchlistGroupEntity
import com.saurabh.financewidget.data.repository.NetWorthRepository
import com.saurabh.financewidget.data.repository.StockRepository
import com.saurabh.financewidget.utils.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: StockRepository,
    private val netWorthRepository: NetWorthRepository
) : ViewModel() {

    // ── All watchlist groups ──────────────────────────────────────────────────

    val watchlistGroups: LiveData<List<WatchlistGroupEntity>> = repository.getWatchlistGroups()

    // ── Active group selection ────────────────────────────────────────────────

    private val _activeGroupId = MutableLiveData<Long>(StockRepository.DEFAULT_GROUP_ID)
    val activeGroupId: LiveData<Long> = _activeGroupId

    /**
     * Stocks for the currently selected group.
     * Switches the source LiveData each time [_activeGroupId] changes.
     *
     * We suppress empty intermediate emissions that occur during a group
     * switch (Room briefly fires an empty list before the new query
     * resolves). If the incoming list is empty but the previous list was
     * non-empty, we hold the previous value until real data arrives —
     * this eliminates the flicker / "white flash" on tab switches.
     */
    val watchlistStocks: LiveData<List<StockEntity>> =
        _activeGroupId.switchMap { groupId ->
            repository.getWatchlistByGroup(groupId).switchMap { groupItems ->
                val symbols = groupItems.map { it.symbol }.toSet()
                repository.getWatchlistStocks().map { allStocks ->
                    allStocks.filter { it.symbol in symbols }
                        .sortedBy { stock -> groupItems.indexOfFirst { it.symbol == stock.symbol } }
                }
            }
        }.distinctUntilChanged()

    /** Raw WatchlistEntity rows for the active group (used for drag-reorder). */
    val activeGroupWatchlist: LiveData<List<WatchlistEntity>> =
        _activeGroupId.switchMap { repository.getWatchlistByGroup(it) }

    // ── Refresh state ─────────────────────────────────────────────────────────

    private val _refreshState = MutableLiveData<Resource<Unit>>()
    val refreshState: LiveData<Resource<Unit>> = _refreshState

    private val _isRefreshing = MutableLiveData<Boolean>(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch {
            repository.ensureDefaultGroup()
            refresh()
        }
    }

    // ── Group operations ──────────────────────────────────────────────────────

    fun selectGroup(groupId: Long) {
        _activeGroupId.value = groupId
    }

    fun createWatchlistGroup(name: String, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val newId = repository.createWatchlistGroup(name)
            onCreated(newId)
            selectGroup(newId)
        }
    }

    fun renameWatchlistGroup(id: Long, newName: String) {
        viewModelScope.launch { repository.renameWatchlistGroup(id, newName) }
    }

    fun deleteWatchlistGroup(id: Long) {
        viewModelScope.launch {
            repository.deleteWatchlistGroup(id)
            // Fall back to the default group if we deleted the active one
            if (_activeGroupId.value == id) {
                val remaining = watchlistGroups.value
                val fallback = remaining?.firstOrNull { it.id != id }?.id
                    ?: StockRepository.DEFAULT_GROUP_ID
                _activeGroupId.value = fallback
            }
        }
    }

    // ── Stock operations ──────────────────────────────────────────────────────

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _refreshState.value = Resource.Loading()

            val watchlistJob = async { repository.refreshWatchlistStocks() }
            val netWorthJob  = async { netWorthRepository.refreshNetWorthAssets() }

            val result = watchlistJob.await()
            netWorthJob.await()

            _refreshState.value = result
            _isRefreshing.value = false
        }
    }

    fun removeFromWatchlist(symbol: String) {
        val groupId = _activeGroupId.value ?: StockRepository.DEFAULT_GROUP_ID
        viewModelScope.launch { repository.removeFromWatchlist(symbol, groupId) }
    }

    fun addToWatchlist(symbol: String, displayName: String) {
        val groupId = _activeGroupId.value ?: StockRepository.DEFAULT_GROUP_ID
        viewModelScope.launch { repository.addToWatchlist(symbol, displayName, groupId) }
    }

    fun reorderWatchlist(items: List<WatchlistEntity>) {
        val groupId = _activeGroupId.value ?: StockRepository.DEFAULT_GROUP_ID
        viewModelScope.launch { repository.updateWatchlistOrder(items, groupId) }
    }
}
