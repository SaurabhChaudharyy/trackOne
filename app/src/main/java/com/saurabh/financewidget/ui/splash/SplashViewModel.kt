package com.saurabh.financewidget.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.financewidget.data.repository.StockRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Coordinates background data pre-fetching during the splash screen.
 *
 * Fetches the 4 market indexes and the watchlist prices in parallel so that
 * when [MainActivity] / [HomeFragment] open, the data is already cached in
 * the Room database and renders immediately without a loading spinner.
 *
 * [isReady] emits `true` the moment all fetches are done (success or error).
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val repository: StockRepository
) : ViewModel() {

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    private val indexSymbols = listOf("^NSEI", "^BSESN", "^GSPC", "^IXIC")

    /**
     * Launches concurrent fetches for indexes + watchlist stocks.
     * Called once from [SplashActivity]; safe to call multiple times
     * (subsequent calls are no-ops if already running).
     */
    fun startPrefetch() {
        if (_isReady.value) return
        viewModelScope.launch {
            // Ensure the default watchlist group exists before any UI is shown
            runCatching { repository.ensureDefaultGroup() }

            // Fetch all indexes concurrently
            val indexJobs = indexSymbols.map { symbol ->
                launch {
                    runCatching { repository.fetchAndCacheStock(symbol) }
                }
            }

            // Also refresh the watchlist in parallel
            val watchlistJob = launch {
                runCatching { repository.refreshWatchlistStocks() }
            }

            indexJobs.forEach { it.join() }
            watchlistJob.join()

            _isReady.value = true
        }
    }
}
