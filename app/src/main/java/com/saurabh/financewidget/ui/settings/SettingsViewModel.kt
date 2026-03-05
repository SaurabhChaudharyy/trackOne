package com.saurabh.financewidget.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.saurabh.financewidget.data.repository.BackupRepository
import com.saurabh.financewidget.data.repository.BackupResult
import com.saurabh.financewidget.data.repository.ImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class BackupUiState {
    object Idle : BackupUiState()
    object Loading : BackupUiState()
    data class ExportSuccess(val message: String) : BackupUiState()
    data class ImportSuccess(val watchlistCount: Int, val assetCount: Int) : BackupUiState()
    data class Error(val message: String) : BackupUiState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val backupRepository: BackupRepository
) : ViewModel() {

    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** Called after the user has picked/created a file URI from the SAF picker. */
    fun exportData(uri: Uri) {
        viewModelScope.launch {
            _state.value = BackupUiState.Loading
            _state.value = when (val result = backupRepository.exportToUri(uri)) {
                is BackupResult.Success -> BackupUiState.ExportSuccess(result.message)
                is BackupResult.Failure -> BackupUiState.Error(result.reason)
            }
        }
    }

    /** Called after the user picks an existing backup file URI from the SAF picker. */
    fun importData(uri: Uri) {
        viewModelScope.launch {
            _state.value = BackupUiState.Loading
            _state.value = when (val result = backupRepository.importFromUri(uri)) {
                is ImportResult.Success -> BackupUiState.ImportSuccess(
                    watchlistCount = result.summary.watchlistRestored,
                    assetCount = result.summary.assetsRestored
                )
                is ImportResult.Failure -> BackupUiState.Error(result.reason)
            }
        }
    }

    /** Reset to idle after a dialog is dismissed. */
    fun resetState() {
        _state.value = BackupUiState.Idle
    }
}
