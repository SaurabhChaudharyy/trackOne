package app.trackone.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import android.util.Patterns
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.trackone.R
import app.trackone.data.repository.BrokerCsvRepository
import app.trackone.databinding.DialogEmailAuthBinding
import app.trackone.databinding.FragmentSettingsBinding
import app.trackone.security.AppLockPrefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin renderer over three independent seams: [AuthViewModel] (Google + email/password sign-in),
 * [CloudBackupViewModel] (Firestore backup/restore), and [CsvImportViewModel] (broker CSV import).
 * None of the three ViewModels know about each other — this Fragment is the only place that
 * wires an effect from one into another (e.g. refreshing last-sync time when auth state changes).
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val authViewModel: AuthViewModel by viewModels()
    private val cloudBackupViewModel: CloudBackupViewModel by viewModels()
    private val csvImportViewModel: CsvImportViewModel by viewModels()

    @Inject
    lateinit var appLockPrefs: AppLockPrefs

    /** Non-cancelable Activity-level dialog — blocks the entire window (including bottom nav). */
    private var blockingDialog: AlertDialog? = null
    private var blockingProgressBar: ProgressBar? = null
    private var blockingMessageView: TextView? = null

    /** Tracks whichever confirmation/result dialog is currently on screen, so a second one
     *  (e.g. from a double-tap firing the triggering click listener twice) can't stack on
     *  top of it — see [setOnDebouncedClickListener] and its use on [FragmentSettingsBinding.rowClearLocalData]. */
    private var activeDialog: AlertDialog? = null

    /**
     * Same as [View.setOnClickListener], but ignores a second tap that lands within
     * [intervalMs] of the first. Guards against a fast double-tap firing a dialog-opening
     * listener twice — which can stack two identical confirmation dialogs, where dismissing
     * the top one (thinking you've cancelled) leaves the second showing underneath.
     */
    private fun View.setOnDebouncedClickListener(intervalMs: Long = 700L, action: (View) -> Unit) {
        var lastClickAt = 0L
        setOnClickListener { v ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastClickAt >= intervalMs) {
                lastClickAt = now
                action(v)
            }
        }
    }

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
                    authViewModel.handleGoogleSignInResult(idToken)
                } else {
                    Toast.makeText(requireContext(), "Sign-in failed: no ID token", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiException) {
                android.util.Log.e("SettingsFragment", "Google Sign-In failed: statusCode=${e.statusCode}, message=${e.message}", e)
                Toast.makeText(
                    requireContext(),
                    "Sign-in failed (code ${e.statusCode}): ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            android.util.Log.w("SettingsFragment", "Google Sign-In: unexpected resultCode=${result.resultCode}")
            Toast.makeText(requireContext(), "Sign-in was interrupted, please try again", Toast.LENGTH_SHORT).show()
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
        updateThemeRowLabel()
        updateFingerprintLockRow()
        observeAuthState()
        observeGoogleSignInState()
        observeCloudBackupState()
        observeCsvImportState()
    }

    override fun onResume() {
        super.onResume()
        // Refresh auth state when returning to the fragment (e.g. after a Google sign-in)
        authViewModel.refreshAuthState()
    }

    // ── Click listeners ───────────────────────────────────────────────────

    private fun setupClickListeners() {

        // Google Sign-In
        binding.cardSignIn.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            launchGoogleSignIn()
        }

        // Email / Password Sign-In
        binding.cardSignInEmail.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showEmailAuthDialog()
        }

        // Sign Out
        binding.btnSignOut.setOnDebouncedClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showSignOutConfirmation()
        }

        // Backup to Cloud
        binding.rowBackup.setOnDebouncedClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showBackupConfirmation()
        }

        // Restore from Cloud
        binding.rowRestore.setOnDebouncedClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showRestoreConfirmation()
        }

        // Clear Local Data — local-only wipe, available whether signed in or not.
        //
        // Debounced: a fast double-tap used to fire this listener twice before the first
        // confirmation dialog was visible, stacking two identical dialogs. Tapping "Cancel"
        // only dismissed the top one, leaving the second dialog underneath — a further tap
        // to dismiss it (in the same screen position) could land on its "Clear" button
        // instead, wiping local data right after the user thought they'd cancelled.
        binding.rowClearLocalData.setOnDebouncedClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showClearLocalDataConfirmation()
        }

        // Broker CSV Import
        binding.cardImportBrokerCsv.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            importBrokerCsvLauncher.launch(BrokerCsvRepository.IMPORT_MIME_TYPES)
        }

        // "Which file do I upload?" — per-broker export guide, so users don't have
        // to trial-and-error which report their broker offers actually works.
        binding.tvWhichFileToUpload.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showBrokerFileGuideDialog()
        }

        // Theme (Light / Dark / System)
        binding.cardTheme.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showThemePickerDialog()
        }

        // Fingerprint Lock — the switch itself ignores touches (see layout); this row-level
        // listener is the single source of truth, same pattern as the other toggleable rows.
        binding.rowFingerprintLock.setOnDebouncedClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            toggleFingerprintLock()
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
        if (!authViewModel.isGoogleSignInConfigured) {
            Toast.makeText(
                requireContext(),
                "Google Sign-In isn't available in this build. Use email/password sign-in instead.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val signInClient = authViewModel.getGoogleSignInClient()
        googleSignInLauncher.launch(signInClient.signInIntent)
    }

    private fun observeGoogleSignInState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                authViewModel.googleSignInState.collect { state ->
                    when (state) {
                        is GoogleSignInUiState.Idle -> dismissBlockingProgress()
                        is GoogleSignInUiState.Loading -> showBlockingProgress("Signing in…")
                        is GoogleSignInUiState.Error -> {
                            dismissBlockingProgress()
                            authViewModel.resetGoogleSignInState()
                            showErrorDialog(state.message)
                        }
                    }
                }
            }
        }
    }

    // ── Email / Password Sign-In ─────────────────────────────────────────

    private fun showEmailAuthDialog() {
        if (!isAdded) return

        val d = DialogEmailAuthBinding.inflate(LayoutInflater.from(requireContext()))
        var isSignUpMode = false

        fun updateModeText() {
            d.tvToggleMode.text = if (isSignUpMode) {
                "Already have an account? Sign in"
            } else {
                "Don't have an account? Sign up"
            }
        }
        updateModeText()

        d.tvToggleMode.setOnClickListener {
            isSignUpMode = !isSignUpMode
            updateModeText()
            d.tvError.isVisible = false
        }

        d.tvForgotPassword.setOnClickListener {
            val email = d.etEmail.text?.toString()?.trim().orEmpty()
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                d.tvError.text = "Enter a valid email first"
                d.tvError.isVisible = true
                return@setOnClickListener
            }
            authViewModel.sendPasswordReset(email)
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle("Sign in with Email")
            .setView(d.root)
            .setPositiveButton("Continue", null)
            .setNegativeButton("Cancel") { dlg, _ -> dlg.dismiss() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = d.etEmail.text?.toString()?.trim().orEmpty()
                val password = d.etPassword.text?.toString()?.trim().orEmpty()

                d.tvError.isVisible = false

                when {
                    !Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                        d.tvError.text = "Enter a valid email address"
                        d.tvError.isVisible = true
                    }
                    password.length < 6 -> {
                        d.tvError.text = "Password must be at least 6 characters"
                        d.tvError.isVisible = true
                    }
                    else -> {
                        if (isSignUpMode) {
                            authViewModel.signUpWithEmail(email, password)
                        } else {
                            authViewModel.signInWithEmail(email, password)
                        }
                    }
                }
            }
        }

        // Observe email-auth results only while this dialog is alive
        val job = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                authViewModel.emailAuthState.collect { state ->
                    when (state) {
                        is EmailAuthUiState.Idle -> Unit
                        is EmailAuthUiState.Loading -> {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                            d.tvError.isVisible = false
                        }
                        is EmailAuthUiState.Success -> {
                            authViewModel.resetEmailAuthState()
                            dialog.dismiss()
                        }
                        is EmailAuthUiState.Info -> {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            d.tvError.setTextColor(requireContext().getColor(R.color.text_secondary))
                            d.tvError.text = state.message
                            d.tvError.isVisible = true
                        }
                        is EmailAuthUiState.Error -> {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            d.tvError.setTextColor(requireContext().getColor(R.color.loss_red))
                            d.tvError.text = state.message
                            d.tvError.isVisible = true
                        }
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            job.cancel()
            authViewModel.resetEmailAuthState()
        }

        dialog.show()
    }

    // ── Observe auth state ────────────────────────────────────────────────

    private fun observeAuthState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                authViewModel.authState.collect { authState ->
                    updateAuthUi(authState)
                    when (authState) {
                        is AuthState.SignedIn -> cloudBackupViewModel.refreshLastSyncTime()
                        is AuthState.SignedOut -> cloudBackupViewModel.clearLastSyncTime()
                        is AuthState.Unknown -> Unit
                    }
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
                binding.tvCloudBackupHeader.isVisible = false
                binding.cardCloudBackup.isVisible = false
            }
            is AuthState.SignedOut -> {
                binding.cardSignIn.isVisible = true
                binding.cardProfile.isVisible = false
                binding.tvCloudBackupHeader.isVisible = false
                binding.cardCloudBackup.isVisible = false
            }
            is AuthState.SignedIn -> {
                binding.cardSignIn.isVisible = false
                binding.cardProfile.isVisible = true
                binding.tvCloudBackupHeader.isVisible = true
                binding.cardCloudBackup.isVisible = true

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
            binding.tvLastBackup.text = "Never backed up"
        } else {
            val relative = DateUtils.getRelativeTimeSpanString(
                timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            binding.tvLastBackup.text = "Last backup: $relative"
        }
    }

    // ── Observe cloud backup state ────────────────────────────────────────────

    private fun observeCloudBackupState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                cloudBackupViewModel.lastSyncTime.collect { timestamp -> updateLastSyncLabel(timestamp) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                cloudBackupViewModel.state.collect { state -> handleCloudBackupState(state) }
            }
        }
    }

    private fun handleCloudBackupState(state: CloudBackupUiState) {
        when (state) {
            is CloudBackupUiState.Idle -> dismissBlockingProgress()
            is CloudBackupUiState.SyncingUp -> showBlockingProgress("Backing up to cloud…")
            is CloudBackupUiState.SyncingDown -> showBlockingProgress("Restoring from cloud…")
            is CloudBackupUiState.FetchingPrices -> showBlockingProgress("Fetching live prices…")
            is CloudBackupUiState.Clearing -> showBlockingProgress("Clearing local data…")
            is CloudBackupUiState.Success -> {
                dismissBlockingProgress()
                cloudBackupViewModel.resetState()
                showSuccessDialog(title = state.title, message = state.message)
            }
            is CloudBackupUiState.Error -> {
                dismissBlockingProgress()
                cloudBackupViewModel.resetState()
                showErrorDialog(state.message)
            }
        }
    }

    // ── Observe CSV import state ────────────────────────────────────────────

    private fun observeCsvImportState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                csvImportViewModel.state.collect { state -> handleCsvImportState(state) }
            }
        }
    }

    private fun handleCsvImportState(state: CsvImportUiState) {
        when (state) {
            is CsvImportUiState.Idle -> dismissBlockingProgress()
            is CsvImportUiState.Loading -> showBlockingProgress("Importing…", state.current, state.total)
            is CsvImportUiState.FetchingPrices -> showBlockingProgress("Fetching live prices…", state.current, state.total)
            is CsvImportUiState.Success -> {
                dismissBlockingProgress()
                csvImportViewModel.resetState()
                val skippedNote = if (state.skipped > 0) "\n${state.skipped} row(s) were skipped." else ""
                showSuccessDialog(
                    title   = "Broker import complete",
                    message = "Added ${state.imported} holding(s) to your portfolio with live INR prices.$skippedNote"
                )
            }
            is CsvImportUiState.Error -> {
                dismissBlockingProgress()
                csvImportViewModel.resetState()
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
     * Calling this again while the dialog is showing just updates the label and progress.
     *
     * @param current/[total] when [total] > 0, the spinner switches to a determinate progress
     * bar with a "N%" label — reassurance that a slow step (e.g. importing many holdings, or
     * fetching live prices one symbol at a time) is actually moving, not stuck. When [total]
     * is 0 (unknown length), it falls back to an indeterminate spinner.
     */
    private fun showBlockingProgress(message: String, current: Int = 0, total: Int = 0) {
        if (!isAdded) return

        val fullMessage = if (total > 0) "$message ${current * 100 / total}%" else message

        // If already showing, just update the message text and progress in place.
        val existing = blockingDialog
        if (existing?.isShowing == true) {
            blockingMessageView?.text = fullMessage
            blockingProgressBar?.apply {
                if (total > 0) {
                    isIndeterminate = false
                    max = total
                    progress = current
                } else {
                    isIndeterminate = true
                }
            }
            return
        }

        val dp = resources.displayMetrics.density

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = android.view.Gravity.CENTER_VERTICAL
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
        }
        val progressBar = ProgressBar(
            requireContext(),
            null,
            if (total > 0) android.R.attr.progressBarStyleHorizontal else android.R.attr.progressBarStyle
        ).apply {
            isIndeterminate = total <= 0
            if (total > 0) { max = total; progress = current }
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
        }
        row.addView(progressBar)
        val messageView = TextView(requireContext()).apply {
            text     = fullMessage
            textSize = 15f
            setTextColor(requireContext().getColor(android.R.color.darker_gray))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = (16 * dp).toInt() }
        }
        row.addView(messageView)

        blockingProgressBar = progressBar
        blockingMessageView = messageView
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
        blockingProgressBar = null
        blockingMessageView = null
    }

    // ── Confirmation dialogs ──────────────────────────────────────────────

    /** Shows exactly which report to export from each supported broker, and where to find it. */
    // ── Theme ────────────────────────────────────────────────────────────

    private val themeModes = intArrayOf(
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        AppCompatDelegate.MODE_NIGHT_NO,
        AppCompatDelegate.MODE_NIGHT_YES
    )
    private val themeLabels = arrayOf("System default", "Light", "Dark")

    private fun updateThemeRowLabel() {
        val index = themeModes.indexOf(ThemePrefs.getMode(requireContext())).let { if (it < 0) 0 else it }
        binding.tvThemeValue.text = themeLabels[index]
    }

    private fun showThemePickerDialog() {
        if (!isAdded) return
        val current = themeModes.indexOf(ThemePrefs.getMode(requireContext())).let { if (it < 0) 0 else it }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Theme")
            .setSingleChoiceItems(themeLabels, current) { dialog, which ->
                val mode = themeModes[which]
                ThemePrefs.setMode(requireContext(), mode)
                AppCompatDelegate.setDefaultNightMode(mode)
                // setDefaultNightMode() only recreates this fragment when the *resolved*
                // light/dark state actually flips (e.g. Dark -> Light). Picking a mode that
                // resolves to the same state (e.g. Light -> System default while the system is
                // already light) leaves this row's label stale otherwise, since nothing else
                // refreshes it.
                updateThemeRowLabel()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ── Fingerprint Lock ─────────────────────────────────────────────────

    private fun updateFingerprintLockRow() {
        binding.switchFingerprintLock.isChecked = appLockPrefs.isLockEnabled
    }

    private fun toggleFingerprintLock() {
        if (!isAdded) return

        if (appLockPrefs.isLockEnabled) {
            appLockPrefs.isLockEnabled = false
            updateFingerprintLockRow()
            return
        }

        val canAuthenticate = BiometricManager.from(requireContext())
            .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(
                requireContext(),
                "Set up a fingerprint or screen lock in your device settings first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        appLockPrefs.isLockEnabled = true
        updateFingerprintLockRow()
    }

    private fun showBrokerFileGuideDialog() {
        if (!isAdded) return
        val binding = app.trackone.databinding.DialogBrokerFileGuideBinding.inflate(LayoutInflater.from(requireContext()))
        val inflater = LayoutInflater.from(requireContext())
        app.trackone.data.repository.BrokerGuide.entries.forEach { entry ->
            val row = app.trackone.databinding.ItemBrokerGuideBinding.inflate(inflater, binding.llBrokerRows, true)
            row.tvBrokerName.text = entry.broker
            row.tvBrokerSource.text = entry.source
            row.tvBrokerPath.text = "${entry.path} · ${entry.format}"
            row.tvBrokerNote.text = entry.note
        }
        MaterialAlertDialogBuilder(requireContext())
            .setView(binding.root)
            .setPositiveButton("Got it") { dlg, _ -> dlg.dismiss() }
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
            .setPositiveButton("Import") { dlg, _ -> dlg.dismiss(); csvImportViewModel.importBrokerCsv(uri) }
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
            .setPositiveButton("Sign out") { dlg, _ -> dlg.dismiss(); authViewModel.signOut() }
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
            .setPositiveButton("Backup") { dlg, _ -> dlg.dismiss(); cloudBackupViewModel.backupToCloud() }
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
            .setPositiveButton("Restore") { dlg, _ -> dlg.dismiss(); cloudBackupViewModel.restoreFromCloud() }
            .setNegativeButton("Cancel") { dlg, _ -> dlg.dismiss() }
            .show()
    }

    /**
     * The warning adapts to whether a cloud backup actually exists: if it does, this data
     * is recoverable via Restore, so the copy says so instead of implying total loss.
     */
    private fun showClearLocalDataConfirmation() {
        if (!isAdded) return
        // Belt-and-braces alongside the debounced click listener: never let a second copy
        // of this dialog stack on top of one that's already showing.
        if (activeDialog?.isShowing == true) return

        val hasCloudBackup = cloudBackupViewModel.lastSyncTime.value != null
        val recoveryLine = if (hasCloudBackup) {
            "Your cloud backup won't be touched — you can bring this data back anytime with Restore from Cloud."
        } else {
            "You don't have a cloud backup, so this data will be gone for good."
        }
        activeDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear local data?")
            .setMessage(
                "This erases all watchlists and investments stored on this device.\n\n" +
                "$recoveryLine\n\nThis action cannot be undone. Continue?"
            )
            .setPositiveButton("Clear") { dlg, _ -> dlg.dismiss(); cloudBackupViewModel.clearLocalData() }
            .setNegativeButton("Cancel") { dlg, _ -> dlg.dismiss() }
            .setOnDismissListener { activeDialog = null }
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
