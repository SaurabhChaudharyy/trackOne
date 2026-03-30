package com.saurabh.financewidget.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.financewidget.data.repository.BackupRepository
import com.saurabh.financewidget.data.repository.BackupResult
import com.saurabh.financewidget.data.repository.BrokerCsvRepository
import com.saurabh.financewidget.data.repository.CsvImportResult
import com.saurabh.financewidget.data.repository.ImportResult
import com.saurabh.financewidget.data.repository.NetWorthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class BackupUiState {
    object Idle : BackupUiState()
    object Loading : BackupUiState()
    /** Shown after CSV rows are parsed — while live prices are being fetched */
    object FetchingPrices : BackupUiState()
    data class WatchlistExportSuccess(val message: String) : BackupUiState()
    data class WatchlistImportSuccess(val count: Int) : BackupUiState()
    data class AssetsExportSuccess(val message: String) : BackupUiState()
    data class CsvImportSuccess(val imported: Int, val skipped: Int) : BackupUiState()
    data class Error(val message: String) : BackupUiState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    private val brokerCsvRepository: BrokerCsvRepository,
    private val netWorthRepository: NetWorthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    fun exportWatchlist(uri: Uri) {
        viewModelScope.launch {
            _state.value = BackupUiState.Loading
            _state.value = when (val result = backupRepository.exportWatchlistToUri(uri)) {
                is BackupResult.Success -> BackupUiState.WatchlistExportSuccess(result.message)
                is BackupResult.Failure -> BackupUiState.Error(result.reason)
            }
        }
    }

    fun importWatchlist(uri: Uri) {
        viewModelScope.launch {
            _state.value = BackupUiState.Loading
            _state.value = when (val result = backupRepository.importWatchlistFromUri(uri)) {
                is ImportResult.Success -> BackupUiState.WatchlistImportSuccess(result.summary.watchlistRestored)
                is ImportResult.Failure -> BackupUiState.Error(result.reason)
            }
        }
    }

    fun exportAssets(uri: Uri) {
        viewModelScope.launch {
            _state.value = BackupUiState.Loading
            _state.value = when (val result = backupRepository.exportAssetsToUri(uri)) {
                is BackupResult.Success -> BackupUiState.AssetsExportSuccess(result.message)
                is BackupResult.Failure -> BackupUiState.Error(result.reason)
            }
        }
    }

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
