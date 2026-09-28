package app.trackone.ui.main

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback
import app.trackone.R
import app.trackone.databinding.ActivityMainBinding
import app.trackone.ui.home.HomeFragment
import app.trackone.ui.networth.NetWorthFragment
import app.trackone.ui.settings.SettingsFragment
import app.trackone.ui.util.applyBottomContentInset
import app.trackone.ui.util.applyEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // On a plain recreate() (e.g. theme switch), the FragmentManager has already restored these
    // exact fragment instances by tag before onCreate runs, so look them up first instead of
    // always constructing a fresh instance — otherwise setupFragments() below would add a second,
    // untracked copy on top of the restored one.
    private val homeFragment: HomeFragment by lazy {
        supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment ?: HomeFragment()
    }
    private val watchlistFragment: WatchlistFragment by lazy {
        supportFragmentManager.findFragmentByTag(TAG_WATCHLIST) as? WatchlistFragment ?: WatchlistFragment()
    }
    private val netWorthFragment: NetWorthFragment by lazy {
        supportFragmentManager.findFragmentByTag(TAG_NETWORTH) as? NetWorthFragment ?: NetWorthFragment()
    }
    private val settingsFragment: SettingsFragment by lazy {
        supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment ?: SettingsFragment()
    }

    private lateinit var activeFragment: Fragment

    override fun onCreate(savedInstanceState: Bundle?) {
        // Container-transform start side (row/card -> stock detail). Overlay off so the tapped
        // view stays in this window's hierarchy and the transform can find it on return.
        setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        window.sharedElementsUseOverlay = false
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdge(topInsetView = binding.appBar, bottomInsetView = binding.bottomNav, navBarIsConstantDark = true)
        applyBottomContentInset(binding.fragmentContainer)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = ""

        setupFragments(savedInstanceState)
        setupBottomNav()
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, netWorthFragment,  TAG_NETWORTH).hide(netWorthFragment)
                .add(R.id.fragment_container, settingsFragment,  TAG_SETTINGS).hide(settingsFragment)
                .add(R.id.fragment_container, watchlistFragment, TAG_WATCHLIST).hide(watchlistFragment)
                .add(R.id.fragment_container, homeFragment,      TAG_HOME)
                .commit()
            activeFragment = homeFragment
        } else {
            // Fragments (and each one's hidden/shown flag) are already restored by the
            // FragmentManager — figure out which tab was actually visible instead of defaulting
            // back to Home, which used to leave the bottom nav's restored selection (handled
            // separately by Android's own view-state restore) pointing at a different tab than
            // the content actually on screen.
            activeFragment = listOf(homeFragment, watchlistFragment, netWorthFragment, settingsFragment)
                .firstOrNull { !it.isHidden } ?: homeFragment
        }
    }

    private fun setupBottomNav() {
        // Fixed branding — "TrackOne" stays on toolbar always
        binding.tvToolbarTitle.apply {
            text = "TrackOne"
            val interBold = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_bold)
            textSize = 28f
            letterSpacing = -0.01f
            setTypeface(interBold, android.graphics.Typeface.NORMAL)
        }

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
            true
        }
        binding.bottomNav.selectedItemId = when (activeFragment) {
            watchlistFragment -> R.id.nav_watchlist
            netWorthFragment  -> R.id.nav_networth
            settingsFragment  -> R.id.nav_settings
            else              -> R.id.nav_home
        }
    }

    private fun showFragment(target: Fragment) {
        if (target === activeFragment) return
        // Material "fade through" is the spec'd pattern for bottom-nav destinations that have no
        // spatial relationship to each other: the old tab fades out, then the new one fades in
        // while scaling up slightly. Transitions need reordering allowed.
        listOf(activeFragment, target).forEach {
            it.exitTransition = MaterialFadeThrough()
            it.enterTransition = MaterialFadeThrough()
        }
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .hide(activeFragment)
            .show(target)
            .commit()
        activeFragment = target
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean = false

    private companion object {
        const val TAG_HOME = "home"
        const val TAG_WATCHLIST = "watchlist"
        const val TAG_NETWORTH = "networth"
        const val TAG_SETTINGS = "settings"
    }
}
