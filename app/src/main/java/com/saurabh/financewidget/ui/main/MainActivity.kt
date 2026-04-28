package com.saurabh.financewidget.ui.main

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
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

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.bottomNav.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            val (fragment, title) = when (item.itemId) {
                R.id.nav_home      -> homeFragment      to getString(R.string.tab_home)
                R.id.nav_watchlist -> watchlistFragment to getString(R.string.tab_markets)
                R.id.nav_networth  -> netWorthFragment  to getString(R.string.tab_networth)
                R.id.nav_settings  -> settingsFragment  to getString(R.string.tab_settings)
                else               -> return@setOnItemSelectedListener false
            }
            showFragment(fragment)
            binding.tvToolbarTitle.text = title
            true
        }
        binding.bottomNav.selectedItemId = R.id.nav_home
    }

    private fun showFragment(target: Fragment) {
        if (target === activeFragment) return
        supportFragmentManager.beginTransaction()
            .hide(activeFragment)
            .show(target)
            .commit()
        activeFragment = target
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean = false
}

