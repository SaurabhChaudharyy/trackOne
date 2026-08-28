package app.trackone.ui.config

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import app.trackone.R
import app.trackone.data.database.WatchlistGroupEntity
import app.trackone.data.repository.StockRepository
import app.trackone.databinding.ActivityWidgetConfigBinding
import app.trackone.ui.widget.WidgetPrefs
import app.trackone.utils.Resource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class WidgetConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWidgetConfigBinding
    private val viewModel: ConfigViewModel by viewModels()
    private lateinit var searchAdapter: SearchResultAdapter
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var activeGroupId: Long = 1L

    companion object {
        private const val EXTRA_GROUP_ID = "extra_group_id"

        fun start(context: Context, groupId: Long = 1L) {
            context.startActivity(
                Intent(context, WidgetConfigActivity::class.java)
                    .putExtra(EXTRA_GROUP_ID, groupId)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWidgetConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        setResult(RESULT_CANCELED)

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            // Real widget placement, or a reconfigure (long-press → Edit) — this widget
            // instance needs its own watchlist assignment, distinct from any other widget.
            lifecycleScope.launch {
                val groups = viewModel.getWatchlistGroupsSync()
                activeGroupId = WidgetPrefs.getGroupId(this@WidgetConfigActivity, appWidgetId)

                if (groups.size > 1) {
                    showGroupPicker(groups)
                } else {
                    activeGroupId = groups.firstOrNull()?.id ?: StockRepository.DEFAULT_GROUP_ID
                    proceedWithChosenGroup()
                }
            }
        } else {
            // Launched from the in-app "Add stock" buttons on a specific watchlist tab —
            // the group is already known from that tab, no widget instance involved.
            activeGroupId = intent.getLongExtra(EXTRA_GROUP_ID, StockRepository.DEFAULT_GROUP_ID)
            showAddUi()
        }
    }

    /** Lets the user pick which watchlist this specific widget instance should show. Shown
     *  both on first placement and on every reconfigure, since a reconfigure's whole point
     *  is to let the user change that choice. */
    private fun showGroupPicker(groups: List<WatchlistGroupEntity>) {
        val names = groups.map { it.name }.toTypedArray()
        var selectedIndex = groups.indexOfFirst { it.id == activeGroupId }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Which watchlist should this widget show?")
            .setSingleChoiceItems(names, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton("Continue") { dialog, _ ->
                activeGroupId = groups[selectedIndex].id
                dialog.dismiss()
                proceedWithChosenGroup()
            }
            .setOnCancelListener { finish() } // back/outside-tap on a fresh placement — abandon it
            .show()
    }

    private fun proceedWithChosenGroup() {
        WidgetPrefs.setGroupId(this, appWidgetId, activeGroupId)
        lifecycleScope.launch {
            val hasStocks = viewModel.hasAnyStocks(activeGroupId)
            if (hasStocks) {
                finishConfiguration()
                return@launch
            }
            showAddUi()
        }
    }

    private fun showAddUi() {
        setupToolbar()
        setupSearch()
        setupRecyclerView()
        observeViewModel()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.configToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Add Stocks"
        binding.configToolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.search(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupRecyclerView() {
        searchAdapter = SearchResultAdapter { selected ->
            updateActionBar(selected.size)
        }

        binding.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(this@WidgetConfigActivity)
            adapter = searchAdapter
        }

        binding.btnDone.setOnClickListener {
            binding.btnDone.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            val selected = searchAdapter.getSelectedItems()
            if (selected.isEmpty()) {
                finishConfiguration()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                var addedCount = 0
                selected.forEach { result ->
                    try {
                        viewModel.addToWatchlistSync(result.symbol, result.displayName, activeGroupId)
                        addedCount++
                    } catch (_: Exception) {}
                }
                val msg = if (addedCount == 1) "1 stock added"
                           else "$addedCount stocks added"
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                searchAdapter.clearSelection()
                updateActionBar(0)
                finishConfiguration()
            }
        }
    }

    private fun updateActionBar(count: Int) {
        when {
            count == 0 -> {
                binding.tvSelectedCount.text = "Select stocks to add"
                binding.btnDone.text = "Done"
            }
            count == 1 -> {
                binding.tvSelectedCount.text = "1 stock selected"
                binding.btnDone.text = "Add (1)"
            }
            else -> {
                binding.tvSelectedCount.text = "$count stocks selected"
                binding.btnDone.text = "Add ($count)"
            }
        }
    }

    private fun observeViewModel() {
        viewModel.searchResults.observe(this) { resource ->
            when (resource) {
                is Resource.Success -> {
                    searchAdapter.submitList(resource.data)
                    binding.rvSearchResults.visibility =
                        if (resource.data.isNotEmpty()) View.VISIBLE else View.GONE
                    binding.searchProgress.visibility = View.GONE
                }
                is Resource.Loading -> {
                    binding.searchProgress.visibility = View.VISIBLE
                }
                is Resource.Error -> {
                    binding.searchProgress.visibility = View.GONE
                    Snackbar.make(binding.root, resource.message, Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun finishConfiguration() {
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }
            sendBroadcast(intent)
            val resultValue = Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            setResult(RESULT_OK, resultValue)
        }
        finish()
    }
}
