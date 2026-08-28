package app.trackone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trackone.data.repository.CloudBackupRepository
import app.trackone.data.repository.LocalDataRepository
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
    /** Local-only wipe (Settings' "Clear Local Data") — never touches Firestore. */
    object Clearing : CloudBackupUiState()
    data class Success(val title: String, val message: String) : CloudBackupUiState()
    data class Error(val message: String) : CloudBackupUiState()
}

/**
 * Owns backup/restore to Firestore, the "last backed up" timestamp shown in Settings, and
 * clearing on-device data for a fresh start. The clear path is entirely local ([LocalDataRepository]
 * never calls Firestore) so it's safe for a signed-out user too, and doesn't affect any cloud backup.
 */
@HiltViewModel
class CloudBackupViewModel @Inject constructor(
    private val cloudBackupRepository: CloudBackupRepository,
    private val netWorthRepository: NetWorthRepository,
    private val localDataRepository: LocalDataRepository
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
                        title = "Backup complete",
                        message = "Backed up ${stats.assets} assets, ${stats.watchlistItems} watchlist items, " +
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
                    // Refresh live prices for the restored assets — forced, since the user
                    // explicitly asked for a restore and expects current prices, not whatever
                    // was last cached before the passive-refresh interval elapses.
                    _state.value = CloudBackupUiState.FetchingPrices
                    netWorthRepository.refreshNetWorthAssets(force = true)

                    _state.value = CloudBackupUiState.Success(
                        title = "Restore complete",
                        message = "Restored ${stats.assets} assets, ${stats.watchlistItems} watchlist items, " +
                        "and ${stats.watchlistGroups} groups"
                    )
                },
                onFailure = { e ->
                    _state.value = CloudBackupUiState.Error(e.message ?: "Restore failed")
                }
            )
        }
    }

    fun clearLocalData() {
        viewModelScope.launch {
            _state.value = CloudBackupUiState.Clearing
            val result = localDataRepository.clearAllLocalData()
            result.fold(
                onSuccess = { stats ->
                    _state.value = CloudBackupUiState.Success(
                        title = "Local data cleared",
                        message = "Cleared ${stats.assets} assets, ${stats.transactions} transactions, " +
                        "and ${stats.watchlistItems} watchlist items from this device"
                    )
                },
                onFailure = { e ->
                    _state.value = CloudBackupUiState.Error(e.message ?: "Couldn't clear local data")
                }
            )
        }
    }

    fun resetState() {
        _state.value = CloudBackupUiState.Idle
    }
}
