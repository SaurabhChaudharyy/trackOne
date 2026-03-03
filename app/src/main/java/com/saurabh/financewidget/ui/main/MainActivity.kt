package com.saurabh.financewidget.ui.main

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.saurabh.financewidget.R
import com.saurabh.financewidget.databinding.ActivityMainBinding
import com.saurabh.financewidget.ui.config.WidgetConfigActivity
import com.saurabh.financewidget.ui.detail.StockDetailActivity
import com.saurabh.financewidget.utils.MarketUtils
import com.saurabh.financewidget.utils.Resource
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: WatchlistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = ""

        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()

        binding.btnAddStock.setOnClickListener {
            WidgetConfigActivity.start(this)
        }

        binding.chipUsMarket.setOnClickListener { showMarketHoursDialog(Market.US) }
        binding.chipIndiaMarket.setOnClickListener { showMarketHoursDialog(Market.INDIA) }
    }

    private fun setupRecyclerView() {
        adapter = WatchlistAdapter(
            onStockClick = { stock -> StockDetailActivity.start(this, stock.symbol) },
            onRemoveClick = { stock ->
                viewModel.removeFromWatchlist(stock.symbol)
                Snackbar.make(binding.root, "${stock.symbol} removed", Snackbar.LENGTH_LONG)
                    .setAction("UNDO") { viewModel.refresh() }
                    .show()
            }
        )

        binding.recyclerViewWatchlist.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            setHasFixedSize(true)
        }

        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val stock = adapter.currentList[viewHolder.adapterPosition]
                viewModel.removeFromWatchlist(stock.symbol)
                Snackbar.make(binding.root, "${stock.symbol} removed", Snackbar.LENGTH_LONG)
                    .setAction("UNDO") { viewModel.refresh() }
                    .show()
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerViewWatchlist)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener { viewModel.refresh() }
        binding.swipeRefreshLayout.setColorSchemeResources(R.color.gain_green)
    }

    private fun observeViewModel() {
        viewModel.watchlistStocks.observe(this) { stocks ->
            adapter.submitList(stocks)
            updateEmptyState(stocks.isEmpty())
            updateMarketStatus()
        }

        viewModel.isRefreshing.observe(this) { isRefreshing ->
            binding.swipeRefreshLayout.isRefreshing = isRefreshing
        }

        viewModel.refreshState.observe(this) { state ->
            when (state) {
                is Resource.Error -> {
                    if (adapter.currentList.isEmpty()) {
                        binding.errorView.visibility = View.VISIBLE
                        binding.errorText.text = state.message
                    }
                }
                else -> binding.errorView.visibility = View.GONE
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyStateLayout.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerViewWatchlist.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateMarketStatus() {
        val usOpen = MarketUtils.isUsMarketOpen()
        val indiaOpen = MarketUtils.isIndiaMarketOpen()

        binding.tvUsMarketStatus.text = if (usOpen) "Open" else "Closed"
        binding.tvUsMarketStatus.setTextColor(
            getColor(if (usOpen) R.color.gain_green else R.color.text_tertiary)
        )

        binding.tvIndiaMarketStatus.text = if (indiaOpen) "Open" else "Closed"
        binding.tvIndiaMarketStatus.setTextColor(
            getColor(if (indiaOpen) R.color.gain_green else R.color.text_tertiary)
        )
    }

    // ── Market hours popup ───────────────────────────────────────────────────

    private enum class Market { US, INDIA }

    private fun showMarketHoursDialog(market: Market) {
        val deviceTz = TimeZone.getDefault()
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault()).apply { timeZone = deviceTz }
        val now = Calendar.getInstance()

        fun toDeviceTime(hour: Int, minute: Int, sourceTz: TimeZone): String {
            val cal = Calendar.getInstance(sourceTz).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }
            return sdf.format(cal.time)
        }

        val (title, details) = when (market) {
            Market.US -> {
                val estTz = TimeZone.getTimeZone("America/New_York")
                val open  = toDeviceTime(9, 30,  estTz)
                val close = toDeviceTime(16, 0,  estTz)
                val preOpen  = toDeviceTime(4, 0,  estTz)
                val afterClose = toDeviceTime(20, 0, estTz)
                val isOpen = MarketUtils.isUsMarketOpen()
                "NYSE / NASDAQ" to buildString {
                    appendLine("Status:  ${if (isOpen) "🟢 Open" else "⚫ Closed"}")
                    appendLine()
                    appendLine("Regular session")
                    appendLine("  Open   $open")
                    appendLine("  Close  $close")
                    appendLine()
                    appendLine("Pre-market:  from $preOpen")
                    appendLine("After-hours: until $afterClose")
                    appendLine()
                    append("Times shown in your local timezone (${deviceTz.getDisplayName(false, TimeZone.SHORT)})")
                }
            }
            Market.INDIA -> {
                val istTz = TimeZone.getTimeZone("Asia/Kolkata")
                val open  = toDeviceTime(9, 15,  istTz)
                val close = toDeviceTime(15, 30, istTz)
                val preOpen  = toDeviceTime(9, 0,  istTz)
                val isOpen = MarketUtils.isIndiaMarketOpen()
                "NSE / BSE" to buildString {
                    appendLine("Status:  ${if (isOpen) "🟢 Open" else "⚫ Closed"}")
                    appendLine()
                    appendLine("Regular session")
                    appendLine("  Open   $open")
                    appendLine("  Close  $close")
                    appendLine()
                    appendLine("Pre-open:  from $preOpen")
                    appendLine()
                    append("Times shown in your local timezone (${deviceTz.getDisplayName(false, TimeZone.SHORT)})")
                }
            }
        }

        AlertDialog.Builder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle(title)
            .setMessage(details)
            .setPositiveButton("Got it", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add_stock -> { WidgetConfigActivity.start(this); true }
            R.id.action_refresh   -> { viewModel.refresh(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
