package com.saurabh.financewidget.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.saurabh.financewidget.utils.Resource

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
