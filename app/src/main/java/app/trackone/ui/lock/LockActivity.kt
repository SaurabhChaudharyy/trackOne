package app.trackone.ui.lock

import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import app.trackone.databinding.ActivityLockBinding
import app.trackone.security.AppLockManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Full-screen gate shown by [app.trackone.FinanceApplication]'s activity-lifecycle callbacks
 * whenever [AppLockManager.shouldShowLockScreen] is true. Back press is disarmed — dismissing
 * this screen must never reveal the Activity underneath without a successful biometric/device
 * unlock, so a back press backgrounds the whole task instead.
 */
@AndroidEntryPoint
class LockActivity : AppCompatActivity() {

    @Inject
    lateinit var appLockManager: AppLockManager

    private lateinit var binding: ActivityLockBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }

        binding.btnUnlock.setOnClickListener { showBiometricPrompt() }
    }

    override fun onStart() {
        super.onStart()
        showBiometricPrompt()
    }

    private fun showBiometricPrompt() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    appLockManager.onUnlocked()
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancelled or dismissed the system prompt — leave this screen up so
                    // they can retry via the button rather than getting bounced anywhere.
                }

                override fun onAuthenticationFailed() {
                    // A presented fingerprint/face didn't match — the system prompt itself
                    // already shows an error and lets the user retry.
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock TrackOne")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        prompt.authenticate(promptInfo)
    }
}
