package app.trackone.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.trackone.R
import app.trackone.data.repository.BrokerCsvRepository
import app.trackone.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    /** Non-cancelable Activity-level dialog — blocks the entire window (including bottom nav). */
    private var blockingDialog: AlertDialog? = null

    // ── Activity Result Launchers ─────────────────────────────────────────

    private val importBrokerCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) showBrokerCsvImportConfirmationDialog(uri)
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken != null) {
                    viewModel.handleGoogleSignInResult(idToken)
                } else {
                    Toast.makeText(requireContext(), "Sign-in failed: no ID token", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiException) {
                Toast.makeText(requireContext(), "Sign-in cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

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
        observeAuthState()
    }

    override fun onResume() {
        super.onResume()
        // Refresh auth state when returning to the fragment (e.g. after a Google sign-in)
        viewModel.refreshAuthState()
    }

    // ── Click listeners ───────────────────────────────────────────────────

    private fun setupClickListeners() {

        // Google Sign-In
        binding.cardSignIn.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            launchGoogleSignIn()
        }

        // Sign Out
        binding.btnSignOut.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showSignOutConfirmation()
        }

        // Backup to Cloud
        binding.rowBackup.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showBackupConfirmation()
        }

        // Restore from Cloud
        binding.rowRestore.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showRestoreConfirmation()
        }

        // Broker CSV Import
        binding.cardImportBrokerCsv.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            importBrokerCsvLauncher.launch(BrokerCsvRepository.IMPORT_MIME_TYPES)
        }

        // Contact Us
        binding.cardContactUs.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf("saurabh@trackone.app"))
                putExtra(Intent.EXTRA_SUBJECT, "[TrackOne] Feedback / Bug Report")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Hi,\n\n" +
                    "[Describe your issue or feedback here]\n\n" +
                    "---\n" +
                    "App: TrackOne v1.2\n" +
                    "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                    "Android: ${android.os.Build.VERSION.RELEASE}"
                )
            }
            try {
                startActivity(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(
                    requireContext(),
                    "No email app found. Please email saurabh@trackone.app directly.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── Google Sign-In ────────────────────────────────────────────────────

    private fun launchGoogleSignIn() {
        val signInClient = viewModel.getGoogleSignInClient()
        googleSignInLauncher.launch(signInClient.signInIntent)
    }

    // ── Observe auth state ────────────────────────────────────────────────

    private fun observeAuthState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.authState.collect { authState ->
                    updateAuthUi(authState)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.lastSyncTime.collect { timestamp ->
                    updateLastSyncLabel(timestamp)
                }
            }
        }
    }

    private fun updateAuthUi(state: AuthState) {
        when (state) {
            is AuthState.Unknown -> {
                // Initial state — hide everything until resolved
                binding.cardSignIn.isVisible = false
                binding.cardProfile.isVisible = false
                binding.tvCloudSyncHeader.isVisible = false
                binding.cardCloudSync.isVisible = false
            }
            is AuthState.SignedOut -> {
                binding.cardSignIn.isVisible = true
                binding.cardProfile.isVisible = false
                binding.tvCloudSyncHeader.isVisible = false
                binding.cardCloudSync.isVisible = false
            }
            is AuthState.SignedIn -> {
                binding.cardSignIn.isVisible = false
                binding.cardProfile.isVisible = true
                binding.tvCloudSyncHeader.isVisible = true
                binding.cardCloudSync.isVisible = true

                // User info
                binding.tvUserName.text = state.displayName ?: "User"
                binding.tvUserEmail.text = state.email ?: ""

                // Avatar via Glide
                if (!state.photoUrl.isNullOrEmpty()) {
                    Glide.with(this)
                        .load(state.photoUrl)
                        .transform(CircleCrop())
                        .placeholder(R.drawable.bg_settings_icon)
                        .into(binding.ivAvatar)
                }
            }
        }
    }

    private fun updateLastSyncLabel(timestamp: Long?) {
        if (timestamp == null || timestamp == 0L) {
            binding.tvLastSync.text = "Never synced"
        } else {
            val relative = DateUtils.getRelativeTimeSpanString(
                timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            binding.tvLastSync.text = "Last backup: $relative"
        }
    }

    // ── Observe backup/sync UI state ──────────────────────────────────────

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
                showBlockingProgress("Fetching live prices…")
            }

            is BackupUiState.SyncingUp -> {
                showBlockingProgress("Backing up to cloud…")
            }

            is BackupUiState.SyncingDown -> {
                showBlockingProgress("Restoring from cloud…")
            }

            is BackupUiState.CsvImportSuccess -> {
                dismissBlockingProgress()
                viewModel.resetState()
                val skippedNote = if (state.skipped > 0) "\n${state.skipped} row(s) were skipped." else ""
                showSuccessDialog(
                    title   = "Broker import complete",
                    message = "Added ${state.imported} holding(s) to your portfolio with live INR prices.$skippedNote"
                )
            }

            is BackupUiState.SyncSuccess -> {
                dismissBlockingProgress()
                viewModel.resetState()
                showSuccessDialog(
                    title = "Sync complete",
                    message = state.message
                )
            }

            is BackupUiState.Error -> {
                dismissBlockingProgress()
                viewModel.resetState()
                showErrorDialog(state.message)
            }
        }
    }


    // ── Blocking progress dialog ──────────────────────────────────────────

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

    // ── Confirmation dialogs ──────────────────────────────────────────────

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

    private fun showSignOutConfirmation() {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sign out?")
            .setMessage(
                "Your local data will remain on this device.\n\n" +
                "You can sign in again anytime to access your cloud backup."
            )
            .setPositiveButton("Sign out") { dlg, _ -> dlg.dismiss(); viewModel.signOut() }
            .setNegativeButton("Cancel") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    private fun showBackupConfirmation() {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Backup to Cloud?")
            .setMessage(
                "This will upload your current watchlists and investments to your Google account.\n\n" +
                "Any existing cloud backup will be replaced."
            )
            .setPositiveButton("Backup") { dlg, _ -> dlg.dismiss(); viewModel.backupToCloud() }
            .setNegativeButton("Cancel") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    private fun showRestoreConfirmation() {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Restore from Cloud?")
            .setMessage(
                "⚠️ This will REPLACE all your local data (watchlists and investments) " +
                "with the cloud backup.\n\n" +
                "This action cannot be undone. Continue?"
            )
            .setPositiveButton("Restore") { dlg, _ -> dlg.dismiss(); viewModel.restoreFromCloud() }
            .setNegativeButton("Cancel") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    // ── Result dialogs ────────────────────────────────────────────────────

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
