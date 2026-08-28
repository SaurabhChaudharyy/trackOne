package app.trackone.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trackone.data.repository.BrokerCsvRepository
import app.trackone.data.repository.CsvImportResult
import app.trackone.data.repository.NetWorthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CsvImportUiState {
    object Idle : CsvImportUiState()
    /** [current]/[total] holdings persisted to the DB so far; 0/0 until the file is parsed. */
    data class Loading(val current: Int = 0, val total: Int = 0) : CsvImportUiState()
    /** Shown after CSV rows are parsed — while live prices are being fetched, one asset at a time. */
    data class FetchingPrices(val current: Int = 0, val total: Int = 0) : CsvImportUiState()
    data class Success(val imported: Int, val skipped: Int) : CsvImportUiState()
    data class Error(val message: String) : CsvImportUiState()
}

/** Owns the broker CSV/XLSX import flow, independent of auth and cloud sync. */
@HiltViewModel
class CsvImportViewModel @Inject constructor(
    private val brokerCsvRepository: BrokerCsvRepository,
    private val netWorthRepository: NetWorthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<CsvImportUiState>(CsvImportUiState.Idle)
    val state: StateFlow<CsvImportUiState> = _state.asStateFlow()

    fun importBrokerCsv(uri: Uri) {
        viewModelScope.launch {
            _state.value = CsvImportUiState.Loading()
            val result = brokerCsvRepository.importFromUri(uri) { current, total ->
                _state.value = CsvImportUiState.Loading(current, total)
            }
            when (result) {
                is CsvImportResult.Failure -> {
                    _state.value = CsvImportUiState.Error(result.reason)
                }
                is CsvImportResult.Success -> {
                    // CSV parsed and persisted — now fetch live prices and convert USD→INR.
                    // This is a sequential per-asset network call, so it's usually the
                    // slowest part of a large import — hence its own progress count.
                    // Forced: the newly-imported holdings have never had a live price yet,
                    // so this must run now rather than being skipped by the passive-refresh
                    // throttle.
                    _state.value = CsvImportUiState.FetchingPrices()
                    netWorthRepository.refreshNetWorthAssets(force = true) { current, total ->
                        _state.value = CsvImportUiState.FetchingPrices(current, total)
                    }
                    _state.value = CsvImportUiState.Success(result.imported, result.skipped)
                }
            }
        }
    }

    fun resetState() {
        _state.value = CsvImportUiState.Idle
    }
}
