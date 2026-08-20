package app.trackone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trackone.data.repository.CloudSyncRepository
import app.trackone.data.repository.NetWorthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CloudSyncUiState {
    object Idle : CloudSyncUiState()
    object SyncingUp : CloudSyncUiState()
    object SyncingDown : CloudSyncUiState()
    /** Shown after restore completes — while live prices are being fetched for restored assets. */
    object FetchingPrices : CloudSyncUiState()
    data class Success(val message: String) : CloudSyncUiState()
    data class Error(val message: String) : CloudSyncUiState()
}

/** Owns backup/restore to Firestore and the "last synced" timestamp shown in Settings. */
@HiltViewModel
class CloudSyncViewModel @Inject constructor(
    private val cloudSyncRepository: CloudSyncRepository,
    private val netWorthRepository: NetWorthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<CloudSyncUiState>(CloudSyncUiState.Idle)
    val state: StateFlow<CloudSyncUiState> = _state.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Long?>(null)
    val lastSyncTime: StateFlow<Long?> = _lastSyncTime.asStateFlow()

    /** Called by the Fragment when [AuthViewModel.authState] transitions to SignedIn. */
    fun refreshLastSyncTime() {
        viewModelScope.launch { _lastSyncTime.value = cloudSyncRepository.getLastSyncTime() }
    }

    /** Called by the Fragment when [AuthViewModel.authState] transitions to SignedOut. */
    fun clearLastSyncTime() {
        _lastSyncTime.value = null
    }

    fun backupToCloud() {
        viewModelScope.launch {
            _state.value = CloudSyncUiState.SyncingUp
            val result = cloudSyncRepository.backupToCloud()
            result.fold(
                onSuccess = { stats ->
                    _lastSyncTime.value = System.currentTimeMillis()
                    _state.value = CloudSyncUiState.Success(
                        "Backed up ${stats.assets} assets, ${stats.watchlistItems} watchlist items, " +
                        "and ${stats.watchlistGroups} groups"
                    )
                },
                onFailure = { e ->
                    _state.value = CloudSyncUiState.Error(e.message ?: "Backup failed")
                }
            )
        }
    }

    fun restoreFromCloud() {
        viewModelScope.launch {
            _state.value = CloudSyncUiState.SyncingDown

            val restoreResult = cloudSyncRepository.restoreFromCloud()
            restoreResult.fold(
                onSuccess = { stats ->
                    // Refresh live prices for the restored assets
                    _state.value = CloudSyncUiState.FetchingPrices
                    netWorthRepository.refreshNetWorthAssets()

                    _state.value = CloudSyncUiState.Success(
                        "Restored ${stats.assets} assets, ${stats.watchlistItems} watchlist items, " +
                        "and ${stats.watchlistGroups} groups"
                    )
                },
                onFailure = { e ->
                    _state.value = CloudSyncUiState.Error(e.message ?: "Restore failed")
                }
            )
        }
    }

    fun resetState() {
        _state.value = CloudSyncUiState.Idle
    }
}
