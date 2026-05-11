package app.trackone.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import app.trackone.data.repository.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Auth state ────────────────────────────────────────────────────────────

sealed class AuthState {
    object Unknown : AuthState()
    object SignedOut : AuthState()
    data class SignedIn(
        val displayName: String?,
        val email: String?,
        val photoUrl: String?
    ) : AuthState()
}

// ── UI state (backup / sync operations) ───────────────────────────────────

sealed class BackupUiState {
    object Idle : BackupUiState()
    object Loading : BackupUiState()
    /** Shown after CSV rows are parsed — while live prices are being fetched */
    object FetchingPrices : BackupUiState()
    object SyncingUp : BackupUiState()
    object SyncingDown : BackupUiState()
    data class CsvImportSuccess(val imported: Int, val skipped: Int) : BackupUiState()
    data class SyncSuccess(val message: String) : BackupUiState()
    data class Error(val message: String) : BackupUiState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val brokerCsvRepository: BrokerCsvRepository,
    private val netWorthRepository: NetWorthRepository,
    private val authRepository: AuthRepository,
    private val cloudSyncRepository: CloudSyncRepository
) : ViewModel() {

    // ── Backup / Sync UI state ────────────────────────────────────────────

    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    // ── Auth state ────────────────────────────────────────────────────────

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // ── Last sync time ────────────────────────────────────────────────────

    private val _lastSyncTime = MutableStateFlow<Long?>(null)
    val lastSyncTime: StateFlow<Long?> = _lastSyncTime.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _authState.value = if (user != null) {
            AuthState.SignedIn(
                displayName = user.displayName,
                email = user.email,
                photoUrl = user.photoUrl?.toString()
            )
        } else {
            AuthState.SignedOut
        }

        // Fetch last sync time if signed in
        if (user != null) {
            viewModelScope.launch {
                _lastSyncTime.value = cloudSyncRepository.getLastSyncTime()
            }
        } else {
            _lastSyncTime.value = null
        }
    }

    init {
        FirebaseAuth.getInstance().addAuthStateListener(authListener)
    }

    override fun onCleared() {
        super.onCleared()
        FirebaseAuth.getInstance().removeAuthStateListener(authListener)
    }

    // ── Auth actions ──────────────────────────────────────────────────────

    fun refreshAuthState() {
        // Trigger the listener manually by re-reading current state
        authListener.onAuthStateChanged(FirebaseAuth.getInstance())
    }

    fun getGoogleSignInClient() = authRepository.getGoogleSignInClient()

    fun handleGoogleSignInResult(idToken: String) {
        viewModelScope.launch {
            _state.value = BackupUiState.Loading
            val result = authRepository.signInWithGoogle(idToken)
            result.fold(
                onSuccess = {
                    refreshAuthState()
                    _state.value = BackupUiState.Idle
                },
                onFailure = { e ->
                    _state.value = BackupUiState.Error(
                        e.message ?: "Google Sign-In failed"
                    )
                }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _authState.value = AuthState.SignedOut
            _lastSyncTime.value = null
            _state.value = BackupUiState.Idle
        }
    }

    // ── Cloud sync actions ────────────────────────────────────────────────

    fun backupToCloud() {
        viewModelScope.launch {
            _state.value = BackupUiState.SyncingUp
            val result = cloudSyncRepository.backupToCloud()
            result.fold(
                onSuccess = { stats ->
                    _lastSyncTime.value = System.currentTimeMillis()
                    _state.value = BackupUiState.SyncSuccess(
                        "Backed up ${stats.assets} assets, ${stats.watchlistItems} watchlist items, " +
                        "and ${stats.watchlistGroups} groups"
                    )
                },
                onFailure = { e ->
                    _state.value = BackupUiState.Error(
                        e.message ?: "Backup failed"
                    )
                }
            )
        }
    }

    fun restoreFromCloud() {
        viewModelScope.launch {
            _state.value = BackupUiState.SyncingDown

            // First restore from cloud
            val restoreResult = cloudSyncRepository.restoreFromCloud()
            restoreResult.fold(
                onSuccess = { stats ->
                    // Then refresh live prices for the restored assets
                    _state.value = BackupUiState.FetchingPrices
                    netWorthRepository.refreshNetWorthAssets()

                    _state.value = BackupUiState.SyncSuccess(
                        "Restored ${stats.assets} assets, ${stats.watchlistItems} watchlist items, " +
                        "and ${stats.watchlistGroups} groups"
                    )
                },
                onFailure = { e ->
                    _state.value = BackupUiState.Error(
                        e.message ?: "Restore failed"
                    )
                }
            )
        }
    }

    // ── Broker CSV import ─────────────────────────────────────────────────

    fun importBrokerCsv(uri: Uri) {
        viewModelScope.launch {
            _state.value = BackupUiState.Loading
            when (val result = brokerCsvRepository.importFromUri(uri)) {
                is CsvImportResult.Failure -> {
                    _state.value = BackupUiState.Error(result.reason)
                }
                is CsvImportResult.Success -> {
                    // CSV parsed — now fetch live prices and convert USD→INR
                    _state.value = BackupUiState.FetchingPrices
                    netWorthRepository.refreshNetWorthAssets()   // errors are silent; prices update best-effort
                    _state.value = BackupUiState.CsvImportSuccess(result.imported, result.skipped)
                }
            }
        }
    }

    fun resetState() {
        _state.value = BackupUiState.Idle
    }
}
