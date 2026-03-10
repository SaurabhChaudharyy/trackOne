package com.saurabh.financewidget.ui.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.saurabh.financewidget.data.repository.BackupRepository
import com.saurabh.financewidget.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint

import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) {
            return@registerForActivityResult
        }
        viewModel.exportData(uri)
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            return@registerForActivityResult
        }
        showImportConfirmationDialog(uri)
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        observeViewModel()
    }

    private fun setupClickListeners() {
        binding.cardExport.setOnClickListener {
            binding.cardExport.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            exportLauncher.launch("trackone_backup_$timestamp.json")
        }

        binding.cardImport.setOnClickListener {
            binding.cardImport.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            importLauncher.launch(BackupRepository.IMPORT_MIME_TYPES)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> handleState(state) }
            }
        }
    }

    private fun handleState(state: BackupUiState) {
        when (state) {
            is BackupUiState.Idle -> {
                setLoadingVisible(false)
                setCardsEnabled(true)
            }

            is BackupUiState.Loading -> {
                setLoadingVisible(true)
                setCardsEnabled(false)
            }

            is BackupUiState.ExportSuccess -> {
                setLoadingVisible(false)
                setCardsEnabled(true)
                showSuccessDialog(
                    title = "Export complete",
                    message = state.message +
                        "\n\nYour backup has been saved to the location you chose. " +
                        "Keep the file safe — you can restore it anytime via Import Data."
                )
                viewModel.resetState()
            }

            is BackupUiState.ImportSuccess -> {
                setLoadingVisible(false)
                setCardsEnabled(true)
                val detail = buildString {
                    append("Restored successfully:\n\n")
                    append("• ${state.watchlistCount} watchlist symbol(s)\n")
                    append("• ${state.assetCount} net worth asset(s)\n\n")
                    append("Stock prices will refresh automatically.")
                }
                showSuccessDialog(title = "Import complete", message = detail)
                viewModel.resetState()
            }

            is BackupUiState.Error -> {
                setLoadingVisible(false)
                setCardsEnabled(true)
                showErrorDialog(state.message)
                viewModel.resetState()
            }
        }
    }

    /** Warn the user that importing will replace ALL their current data. */
    private fun showImportConfirmationDialog(uri: Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Replace existing data?")
            .setMessage(
                "Importing will permanently delete your current watchlist and all net worth assets, " +
                "then replace them with the data from the backup file.\n\n" +
                "This cannot be undone. Continue?"
            )
            .setPositiveButton("Import") { dialog, _ ->
                dialog.dismiss()
                viewModel.importData(uri)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showSuccessDialog(title: String, message: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Done") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showErrorDialog(message: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Something went wrong")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun setLoadingVisible(visible: Boolean) {
        binding.layoutLoading.isVisible = visible
        if (visible) {
            binding.tvLoadingLabel.text = "Working…"
        }
    }

    private fun setCardsEnabled(enabled: Boolean) {
        binding.cardExport.isEnabled = enabled
        binding.cardImport.isEnabled = enabled
        binding.cardExport.alpha = if (enabled) 1f else 0.5f
        binding.cardImport.alpha = if (enabled) 1f else 0.5f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
