package app.trackone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trackone.data.repository.CloudBackupRepository
import app.trackone.data.repository.NetWorthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CloudBackupUiState {
    object Idle : CloudBackupUiState()
    object SyncingUp : CloudBackupUiState()
    object SyncingDown : CloudBackupUiState()
    /** Shown after restore completes — while live prices are being fetched for restored assets. */
    object FetchingPrices : CloudBackupUiState()
    data class Success(val message: String) : CloudBackupUiState()
    data class Error(val message: String) : CloudBackupUiState()
}

/** Owns backup/restore to Firestore and the "last backed up" timestamp shown in Settings. */
@HiltViewModel
class CloudBackupViewModel @Inject constructor(
    private val cloudBackupRepository: CloudBackupRepository,
    private val netWorthRepository: NetWorthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<CloudBackupUiState>(CloudBackupUiState.Idle)
    val state: StateFlow<CloudBackupUiState> = _state.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<Long?>(null)
    val lastSyncTime: StateFlow<Long?> = _lastSyncTime.asStateFlow()

    /** Called by the Fragment when [AuthViewModel.authState] transitions to SignedIn. */
    fun refreshLastSyncTime() {
        viewModelScope.launch { _lastSyncTime.value = cloudBackupRepository.getLastSyncTime() }
    }

    /** Called by the Fragment when [AuthViewModel.authState] transitions to SignedOut. */
    fun clearLastSyncTime() {
        _lastSyncTime.value = null
    }

    fun backupToCloud() {
        viewModelScope.launch {
            _state.value = CloudBackupUiState.SyncingUp
            val result = cloudBackupRepository.backupToCloud()
            result.fold(
                onSuccess = { stats ->
                    _lastSyncTime.value = System.currentTimeMillis()
                    _state.value = CloudBackupUiState.Success(
                        "Backed up ${stats.assets} assets, ${stats.watchlistItems} watchlist items, " +
                        "and ${stats.watchlistGroups} groups"
                    )
                },
                onFailure = { e ->
                    _state.value = CloudBackupUiState.Error(e.message ?: "Backup failed")
                }
            )
        }
    }

    fun restoreFromCloud() {
        viewModelScope.launch {
            _state.value = CloudBackupUiState.SyncingDown

            val restoreResult = cloudBackupRepository.restoreFromCloud()
            restoreResult.fold(
                onSuccess = { stats ->
                    // Refresh live prices for the restored assets
                    _state.value = CloudBackupUiState.FetchingPrices
                    netWorthRepository.refreshNetWorthAssets()

                    _state.value = CloudBackupUiState.Success(
                        "Restored ${stats.assets} assets, ${stats.watchlistItems} watchlist items, " +
                        "and ${stats.watchlistGroups} groups"
                    )
                },
                onFailure = { e ->
                    _state.value = CloudBackupUiState.Error(e.message ?: "Restore failed")
                }
            )
        }
    }

    fun resetState() {
        _state.value = CloudBackupUiState.Idle
    }
}
