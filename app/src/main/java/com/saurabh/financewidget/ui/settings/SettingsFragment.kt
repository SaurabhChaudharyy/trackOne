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

    // ── Watchlist launchers ─────────────────────────────────────────

    private val exportWatchlistLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) viewModel.exportWatchlist(uri)
    }

    private val importWatchlistLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) showWatchlistImportConfirmationDialog(uri)
    }

    // ── Stocks & Investments launchers ──────────────────────────────

    private val exportAssetsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) viewModel.exportAssets(uri)
    }

    private val importAssetsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) showAssetsImportConfirmationDialog(uri)
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
        val timestamp: () -> String = {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        }

        binding.cardExportWatchlist.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            exportWatchlistLauncher.launch("trackone_watchlist_${timestamp()}.json")
        }

        binding.cardImportWatchlist.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            importWatchlistLauncher.launch(BackupRepository.IMPORT_MIME_TYPES)
        }

        binding.cardExportAssets.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            exportAssetsLauncher.launch("trackone_investments_${timestamp()}.json")
        }

        binding.cardImportAssets.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            importAssetsLauncher.launch(BackupRepository.IMPORT_MIME_TYPES)
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

            is BackupUiState.WatchlistExportSuccess -> {
                setLoadingVisible(false)
                setCardsEnabled(true)
                showSuccessDialog(
                    title = "Watchlist exported",
                    message = state.message +
                        "\n\nKeep the file safe — you can restore it anytime via Import Watchlist."
                )
                viewModel.resetState()
            }

            is BackupUiState.WatchlistImportSuccess -> {
                setLoadingVisible(false)
                setCardsEnabled(true)
                showSuccessDialog(
                    title = "Watchlist imported",
                    message = "Restored ${state.count} watchlist symbol(s).\n\n" +
                        "Your stocks & investments were not affected."
                )
                viewModel.resetState()
            }

            is BackupUiState.AssetsExportSuccess -> {
                setLoadingVisible(false)
                setCardsEnabled(true)
                showSuccessDialog(
                    title = "Investments exported",
                    message = state.message +
                        "\n\nKeep the file safe — you can restore it anytime via Import Stocks & Investments."
                )
                viewModel.resetState()
            }

            is BackupUiState.AssetsImportSuccess -> {
                setLoadingVisible(false)
                setCardsEnabled(true)
                showSuccessDialog(
                    title = "Investments imported",
                    message = "Restored ${state.count} investment(s).\n\n" +
                        "Stock prices will refresh automatically.\n" +
                        "Your watchlist was not affected."
                )
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

    // ── Confirmation dialogs ────────────────────────────────────────

    private fun showWatchlistImportConfirmationDialog(uri: Uri) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Replace your watchlist?")
            .setMessage(
                "Importing will permanently delete your current watchlist and replace it with " +
                "the symbols from the backup file.\n\n" +
                "Your stocks & investments will NOT be affected.\n\n" +
                "This cannot be undone. Continue?"
            )
            .setPositiveButton("Import") { dialog, _ ->
                dialog.dismiss()
                viewModel.importWatchlist(uri)
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showAssetsImportConfirmationDialog(uri: Uri) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Replace your investments?")
            .setMessage(
                "Importing will permanently delete all your current stocks & investments " +
                "and replace them with the data from the backup file.\n\n" +
                "Your watchlist will NOT be affected.\n\n" +
                "This cannot be undone. Continue?"
            )
            .setPositiveButton("Import") { dialog, _ ->
                dialog.dismiss()
                viewModel.importAssets(uri)
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ── Utility ────────────────────────────────────────────────────

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
        if (visible) binding.tvLoadingLabel.text = "Working…"
    }

    private fun setCardsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1f else 0.5f
        binding.cardExportWatchlist.isEnabled = enabled
        binding.cardExportWatchlist.alpha = alpha
        binding.cardImportWatchlist.isEnabled = enabled
        binding.cardImportWatchlist.alpha = alpha
        binding.cardExportAssets.isEnabled = enabled
        binding.cardExportAssets.alpha = alpha
        binding.cardImportAssets.isEnabled = enabled
        binding.cardImportAssets.alpha = alpha
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
