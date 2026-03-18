package com.saurabh.financewidget.ui.splash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.saurabh.financewidget.databinding.ActivitySplashBinding
import com.saurabh.financewidget.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("CustomSplashScreen")
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val viewModel: SplashViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startAnimations()
        awaitReadyAndNavigate()
    }

    // ─── Entrance animations ──────────────────────────────────────────────

    private fun startAnimations() {
        // Start everything invisible / scaled down
        binding.llBrand.alpha = 0f
        binding.llBrand.scaleX = 0.78f
        binding.llBrand.scaleY = 0.78f
        binding.vAccentBar.alpha        = 0f
        binding.tvLoading.alpha         = 0f

        // 1) Brand group: fade-in + scale up with overshoot (150ms delay for
        //    a slight "after the OS splash" feel — keeps it snappy)
        binding.llBrand.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(120)
            .setDuration(480)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        // 2) Neon accent bar slides up from alpha 0 → 1 (slight delay after brand)
        binding.vAccentBar.animate()
            .alpha(1f)
            .setStartDelay(420)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 3) Loading label fades in last to signal background work
        binding.tvLoading.animate()
            .alpha(0.75f)
            .setStartDelay(540)
            .setDuration(280)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    // ─── Wait for data + minimum display time ────────────────────────────

    private fun awaitReadyAndNavigate() {
        lifecycleScope.launch {
            // Kick off pre-fetch in parallel via the ViewModel
            viewModel.startPrefetch()

            // Enforce a minimum visible time so the animation feels intentional
            // (avoids an instant flash on fast devices / cached data)
            val minimumMs = 1_400L
            val startTime = System.currentTimeMillis()

            // Wait until ViewModel signals data is ready
            viewModel.isReady.collect { ready ->
                if (ready) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val remaining = (minimumMs - elapsed).coerceAtLeast(0)
                    delay(remaining)
                    navigateToMain()
                }
            }
        }
    }

    // ─── Exit transition ──────────────────────────────────────────────────

    private fun navigateToMain() {
        // Fade out the splash before launching MainActivity
        binding.root.animate()
            .alpha(0f)
            .setDuration(220)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                // Disable the default slide animation — our fade-out IS the exit transition
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
                finish()
            }
            .start()
    }
}
