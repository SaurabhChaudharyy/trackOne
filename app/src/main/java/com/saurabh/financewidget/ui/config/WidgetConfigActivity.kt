package com.saurabh.financewidget.ui.config

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
import com.google.android.material.snackbar.Snackbar
import com.saurabh.financewidget.R
import com.saurabh.financewidget.databinding.ActivityWidgetConfigBinding
import com.saurabh.financewidget.utils.Resource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class WidgetConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWidgetConfigBinding
    private val viewModel: ConfigViewModel by viewModels()
    private lateinit var searchAdapter: SearchResultAdapter
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, WidgetConfigActivity::class.java))
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

        // If launched by the widget picker AND stocks already exist,
        // skip the search UI and immediately configure the widget.
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            lifecycleScope.launch {
                val hasStocks = viewModel.hasAnyStocks()
                if (hasStocks) {
                    finishConfiguration()
                    return@launch
                }
                // No stocks yet — show the add UI so the user can populate the watchlist first
                showAddUi()
            }
        } else {
            // Opened from "Add Stock" button in the app — always show the search UI
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

        // Done button commits all selected
        binding.btnDone.setOnClickListener {
            val selected = searchAdapter.getSelectedItems()
            if (selected.isEmpty()) {
                finishConfiguration()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                var addedCount = 0
                selected.forEach { result ->
                    try {
                        viewModel.addToWatchlistSync(result.symbol, result.displayName)
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
