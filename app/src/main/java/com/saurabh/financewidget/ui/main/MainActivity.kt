package com.saurabh.financewidget.ui.main

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.saurabh.financewidget.R
import com.saurabh.financewidget.databinding.ActivityMainBinding
import com.saurabh.financewidget.ui.home.HomeFragment
import com.saurabh.financewidget.ui.networth.NetWorthFragment
import com.saurabh.financewidget.ui.settings.SettingsFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val homeFragment      by lazy { HomeFragment() }
    private val watchlistFragment by lazy { WatchlistFragment() }
    private val netWorthFragment  by lazy { NetWorthFragment() }
    private val settingsFragment  by lazy { SettingsFragment() }

    private var activeFragment: Fragment = homeFragment

    /** Neon dot views mapped by menu item position. */
    private val dotViews = mutableMapOf<Int, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = ""

        setupFragments()
        setupBottomNav()
    }

    private fun setupFragments() {
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, netWorthFragment,  "networth").hide(netWorthFragment)
            .add(R.id.fragment_container, settingsFragment,  "settings").hide(settingsFragment)
            .add(R.id.fragment_container, watchlistFragment, "watchlist").hide(watchlistFragment)
            .add(R.id.fragment_container, homeFragment,      "home")
            .commit()
    }

    private val navItemIds = listOf(R.id.nav_home, R.id.nav_watchlist, R.id.nav_networth, R.id.nav_settings)

    private fun setupBottomNav() {
        // Fixed branding — "TrackOne" stays on toolbar always
        binding.tvToolbarTitle.apply {
            text = "TrackOne"
            val interSemiBold = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_semi_bold)
            textSize = 28f
            letterSpacing = -0.03f
            setTypeface(interSemiBold, android.graphics.Typeface.NORMAL)
        }

        // Inject neon dot indicators after layout
        binding.bottomNav.post { injectDotIndicators() }

        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.bottomNav.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val (fragment, title) = when (item.itemId) {
                R.id.nav_home      -> homeFragment to "TrackOne"
                R.id.nav_watchlist -> watchlistFragment to "Watchlist"
                R.id.nav_networth  -> netWorthFragment to "Assets"
                R.id.nav_settings  -> settingsFragment to "Settings"
                else               -> return@setOnItemSelectedListener false
            }
            binding.tvToolbarTitle.text = title
            showFragment(fragment)
            updateDotIndicator(item.itemId)
            true
        }
        binding.bottomNav.selectedItemId = R.id.nav_home
    }

    /**
     * Injects a small neon-yellow dot View into each BottomNavigationView menu item.
     * The dot sits at the bottom-center of each item's FrameLayout.
     */
    private fun injectDotIndicators() {
        val navView = binding.bottomNav
        // BottomNavigationView → BottomNavigationMenuView (child 0)
        val menuView = navView.getChildAt(0) as? ViewGroup ?: return
        val dotSize = (6 * resources.displayMetrics.density).toInt()
        val dotColor = ContextCompat.getColor(this, R.color.neon_highlight)

        for (i in 0 until menuView.childCount) {
            val itemView = menuView.getChildAt(i) as? ViewGroup ?: continue

            val dot = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                    bottomMargin = (4 * resources.displayMetrics.density).toInt()
                }
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.dot_active_tab)
                visibility = View.GONE
            }

            // The item view is a FrameLayout — we can add child views
            itemView.addView(dot)
            dotViews[i] = dot
        }

        // Show the dot for the initially selected item
        updateDotIndicator(binding.bottomNav.selectedItemId)
    }

    /** Show the neon dot only under the active tab. */
    private fun updateDotIndicator(selectedId: Int) {
        val activeIndex = navItemIds.indexOf(selectedId)
        dotViews.forEach { (index, dot) ->
            dot.visibility = if (index == activeIndex) View.VISIBLE else View.GONE
        }
    }

    private fun showFragment(target: Fragment) {
        if (target === activeFragment) return
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .hide(activeFragment)
            .show(target)
            .commit()
        activeFragment = target
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean = false
}
