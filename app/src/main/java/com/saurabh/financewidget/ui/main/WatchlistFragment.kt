package com.saurabh.financewidget.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.saurabh.financewidget.R
import com.saurabh.financewidget.databinding.FragmentWatchlistBinding
import com.saurabh.financewidget.ui.config.WidgetConfigActivity
import com.saurabh.financewidget.ui.detail.StockDetailActivity
import com.saurabh.financewidget.utils.MarketUtils
import com.saurabh.financewidget.utils.Resource
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class WatchlistFragment : Fragment() {

    private var _binding: FragmentWatchlistBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: WatchlistAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWatchlistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()

        binding.btnAddStock.setOnClickListener {
            binding.btnAddStock.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            WidgetConfigActivity.start(requireActivity())
        }

        binding.chipUsMarket.setOnClickListener { showMarketHoursDialog(Market.US) }
        binding.chipIndiaMarket.setOnClickListener { showMarketHoursDialog(Market.INDIA) }
    }

    private fun setupRecyclerView() {
        adapter = WatchlistAdapter(
            onStockClick = { stock -> 
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                StockDetailActivity.start(requireActivity(), stock.symbol) 
            },
            onRemoveClick = { stock ->
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                viewModel.removeFromWatchlist(stock.symbol)
                Snackbar.make(binding.root, "${stock.symbol} removed", Snackbar.LENGTH_LONG)
                    .setAction("UNDO") { viewModel.addToWatchlist(stock.symbol, stock.companyName) }
                    .show()
            }
        )

        binding.recyclerViewWatchlist.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@WatchlistFragment.adapter
            setHasFixedSize(true)
        }

        val swipeCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 
            ItemTouchHelper.LEFT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                val fromPos = vh.adapterPosition
                val toPos = t.adapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false

                adapter.moveItem(fromPos, toPos)
                rv.performHapticFeedback(android.view.HapticFeedbackConstants.SEGMENT_TICK)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                recyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.GESTURE_END)
                val watchlists = adapter.items.mapIndexed { index, stock -> 
                    com.saurabh.financewidget.data.database.WatchlistEntity(stock.symbol, stock.companyName, index)
                }
                viewModel.reorderWatchlist(watchlists)
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    binding.swipeRefreshLayout.isEnabled = false
                    viewHolder?.itemView?.performHapticFeedback(android.view.HapticFeedbackConstants.GESTURE_START)
                } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                    binding.swipeRefreshLayout.isEnabled = true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val stock = adapter.items[viewHolder.adapterPosition]
                viewModel.removeFromWatchlist(stock.symbol)
                viewHolder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                Snackbar.make(binding.root, "${stock.symbol} removed", Snackbar.LENGTH_LONG)
                    .setAction("UNDO") { viewModel.addToWatchlist(stock.symbol, stock.companyName) }
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
        viewModel.watchlistStocks.observe(viewLifecycleOwner) { stocks ->
            adapter.submitList(stocks)
            updateEmptyState(stocks.isEmpty())
            updateMarketStatus()
        }

        viewModel.isRefreshing.observe(viewLifecycleOwner) { isRefreshing ->
            binding.swipeRefreshLayout.isRefreshing = isRefreshing
        }

        viewModel.refreshState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is Resource.Error -> {
                    if (adapter.items.isEmpty()) {
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
            requireContext().getColor(if (usOpen) R.color.gain_green else R.color.text_tertiary)
        )

        binding.tvIndiaMarketStatus.text = if (indiaOpen) "Open" else "Closed"
        binding.tvIndiaMarketStatus.setTextColor(
            requireContext().getColor(if (indiaOpen) R.color.gain_green else R.color.text_tertiary)
        )
    }

    private enum class Market { US, INDIA }

    private fun showMarketHoursDialog(market: Market) {
        val deviceTz = TimeZone.getDefault()
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault()).apply { timeZone = deviceTz }

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

        AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle(title)
            .setMessage(details)
            .setPositiveButton("Got it", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
