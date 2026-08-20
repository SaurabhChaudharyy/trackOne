package app.trackone.ui.main

import androidx.lifecycle.*
import app.trackone.data.database.StockEntity
import app.trackone.data.database.WatchlistEntity
import app.trackone.data.database.WatchlistGroupEntity
import app.trackone.data.repository.NetWorthRepository
import app.trackone.data.repository.StockRepository
import app.trackone.utils.Resource
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

    /** Raw WatchlistEntity rows for the active group — internal only, used by [reorderWatchlist]
     *  to preserve each item's groupId when translating reordered symbols back into entities. */
    private val activeGroupWatchlist: LiveData<List<WatchlistEntity>> =
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

    fun deleteWatchlistGroup(id: Long, onResult: (deleted: Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val deleted = repository.deleteWatchlistGroup(id)
            // Fall back to the default group if we deleted the active one
            if (deleted && _activeGroupId.value == id) {
                val remaining = watchlistGroups.value
                val fallback = remaining?.firstOrNull { it.id != id }?.id
                    ?: StockRepository.DEFAULT_GROUP_ID
                _activeGroupId.value = fallback
            }
            onResult(deleted)
        }
    }

    // ── Stock operations ──────────────────────────────────────────────────────

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _refreshState.value = Resource.Loading()

            val watchlistJob = async { repository.refreshWatchlistStocks() }
            val netWorthJob  = launch { netWorthRepository.refreshNetWorthAssets() }

            val result = watchlistJob.await()
            netWorthJob.join()

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

    /**
     * Reorders the active group's watchlist to match [orderedSymbols]. Callers pass symbols
     * only — this owns translating them into [WatchlistEntity] rows (preserving each item's
     * groupId from the current snapshot) so the UI layer never has to construct entities itself.
     */
    fun reorderWatchlist(orderedSymbols: List<String>) {
        val groupId = _activeGroupId.value ?: StockRepository.DEFAULT_GROUP_ID
        val currentItems = activeGroupWatchlist.value ?: return
        val reordered = orderedSymbols.mapIndexed { index, symbol ->
            val original = currentItems.firstOrNull { it.symbol == symbol }
            WatchlistEntity(
                symbol = symbol,
                displayName = original?.displayName ?: symbol,
                position = index,
                groupId = original?.groupId ?: groupId
            )
        }
        viewModelScope.launch { repository.updateWatchlistOrder(reordered, groupId) }
    }
}
