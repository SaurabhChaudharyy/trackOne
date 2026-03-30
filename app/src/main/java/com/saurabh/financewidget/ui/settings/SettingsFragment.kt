package com.saurabh.financewidget.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.saurabh.financewidget.data.repository.BackupRepository
import com.saurabh.financewidget.data.repository.BrokerCsvRepository
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

    /** Non-cancelable Activity-level dialog — blocks the entire window (including bottom nav). */
    private var blockingDialog: AlertDialog? = null

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

    private val importBrokerCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) showBrokerCsvImportConfirmationDialog(uri)
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

        binding.cardImportBrokerCsv.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            importBrokerCsvLauncher.launch(BrokerCsvRepository.IMPORT_MIME_TYPES)
        }

        binding.cardContactUs.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf("saurabh@trackone.app"))
                putExtra(Intent.EXTRA_SUBJECT, "[trackOne] Feedback / Bug Report")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Hi,\n\n" +
                    "[Describe your issue or feedback here]\n\n" +
                    "---\n" +
                    "App: trackOne v1.0\n" +
                    "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                    "Android: ${android.os.Build.VERSION.RELEASE}"
                )
            }
            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(intent)
            }
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
                dismissBlockingProgress()
            }

            is BackupUiState.Loading -> {
                showBlockingProgress("Importing…")
            }

            is BackupUiState.FetchingPrices -> {
                // Update the spinner label without recreating the dialog
                showBlockingProgress("Fetching live prices…")
            }

            is BackupUiState.WatchlistExportSuccess -> {
                dismissBlockingProgress()
                viewModel.resetState()   // Reset BEFORE dialog — prevents re-show on tab switch
                showSuccessDialog(
                    title   = "Watchlist exported",
                    message = state.message +
                        "\n\nKeep the file safe — you can restore it anytime via Import Watchlist."
                )
            }

            is BackupUiState.WatchlistImportSuccess -> {
                dismissBlockingProgress()
                viewModel.resetState()
                showSuccessDialog(
                    title   = "Watchlist imported",
                    message = "Restored ${state.count} watchlist symbol(s).\n\n" +
                        "Your stocks & investments were not affected."
                )
            }

            is BackupUiState.AssetsExportSuccess -> {
                dismissBlockingProgress()
                viewModel.resetState()
                showSuccessDialog(
                    title   = "Investments exported",
                    message = state.message +
                        "\n\nKeep the file safe — you can restore it anytime via Import Stocks & Investments."
                )
            }

            is BackupUiState.AssetsImportSuccess -> {
                dismissBlockingProgress()
                viewModel.resetState()
                showSuccessDialog(
                    title   = "Investments imported",
                    message = "Restored ${state.count} investment(s).\n\n" +
                        "Stock prices will refresh automatically.\n" +
                        "Your watchlist was not affected."
                )
            }

            is BackupUiState.CsvImportSuccess -> {
                dismissBlockingProgress()
                // Reset BEFORE showing dialog — StateFlow re-emits on re-subscription (tab switch).
                // Resetting here ensures the next emission is Idle, not CsvImportSuccess.
                viewModel.resetState()
                val skippedNote = if (state.skipped > 0) "\n${state.skipped} row(s) were skipped." else ""
                showSuccessDialog(
                    title   = "Broker import complete",
                    message = "Added ${state.imported} holding(s) to your portfolio with live INR prices.$skippedNote"
                )
            }

            is BackupUiState.Error -> {
                dismissBlockingProgress()
                viewModel.resetState()
                showErrorDialog(state.message)
            }
        }
    }

    // ── Blocking progress dialog ──────────────────────────────────────────────

    /**
     * Shows a non-cancelable dialog at the Activity Window level.
     * This covers the bottom navigation bar and all other fragments,
     * preventing any tab switching or back-press while the operation runs.
     *
     * Calling this again while the dialog is showing just updates the label.
     */
    private fun showBlockingProgress(message: String) {
        if (!isAdded) return

        // If already showing, just update the message text
        val existing = blockingDialog
        if (existing?.isShowing == true) {
            existing.findViewById<TextView>(android.R.id.message)?.text = message
            return
        }

        val dp = resources.displayMetrics.density

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.CENTER_VERTICAL
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
        }
        row.addView(ProgressBar(requireContext()).apply {
            isIndeterminate = true
            layoutParams    = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
        })
        row.addView(TextView(requireContext()).apply {
            id       = android.R.id.message
            text     = message
            textSize = 15f
            setTextColor(requireContext().getColor(android.R.color.darker_gray))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = (16 * dp).toInt() }
        })

        blockingDialog = MaterialAlertDialogBuilder(requireActivity())
            .setView(row)
            .setCancelable(false)               // blocks back-press
            .create()
            .also { dlg ->
                dlg.setCanceledOnTouchOutside(false)
                dlg.show()
            }
    }

    private fun dismissBlockingProgress() {
        blockingDialog?.dismiss()
        blockingDialog = null
    }

    // ── Confirmation dialogs ──────────────────────────────────────────────────

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
            .setPositiveButton("Import") { dlg, _ -> dlg.dismiss(); viewModel.importWatchlist(uri) }
            .setNegativeButton("Cancel") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    private fun showBrokerCsvImportConfirmationDialog(uri: Uri) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Import broker holdings?")
            .setMessage(
                "This will append the stocks from the CSV/XLSX to your net worth.\n\n" +
                "Live prices will be fetched and converted to INR automatically.\n\n" +
                "Your existing investments and watchlist will NOT be deleted. Continue?"
            )
            .setPositiveButton("Import") { dlg, _ -> dlg.dismiss(); viewModel.importBrokerCsv(uri) }
            .setNegativeButton("Cancel") { dlg, _ -> dlg.dismiss() }
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
            .setPositiveButton("Import") { dlg, _ -> dlg.dismiss(); viewModel.importAssets(uri) }
            .setNegativeButton("Cancel") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    // ── Result dialogs ────────────────────────────────────────────────────────

    private fun showSuccessDialog(title: String, message: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Done") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    private fun showErrorDialog(message: String) {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Something went wrong")
            .setMessage(message)
            .setPositiveButton("OK") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        blockingDialog?.dismiss()
        blockingDialog = null
        _binding = null
    }
}
