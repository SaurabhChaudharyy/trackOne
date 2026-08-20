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
    object Loading : CsvImportUiState()
    /** Shown after CSV rows are parsed — while live prices are being fetched. */
    object FetchingPrices : CsvImportUiState()
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
            _state.value = CsvImportUiState.Loading
            when (val result = brokerCsvRepository.importFromUri(uri)) {
                is CsvImportResult.Failure -> {
                    _state.value = CsvImportUiState.Error(result.reason)
                }
                is CsvImportResult.Success -> {
                    // CSV parsed — now fetch live prices and convert USD→INR
                    _state.value = CsvImportUiState.FetchingPrices
                    netWorthRepository.refreshNetWorthAssets()
                    _state.value = CsvImportUiState.Success(result.imported, result.skipped)
                }
            }
        }
    }

    fun resetState() {
        _state.value = CsvImportUiState.Idle
    }
}
