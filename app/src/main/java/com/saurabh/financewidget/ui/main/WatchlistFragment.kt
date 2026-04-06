package com.saurabh.financewidget.ui.main

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context.INPUT_METHOD_SERVICE
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.saurabh.financewidget.R
import com.saurabh.financewidget.data.database.WatchlistGroupEntity
import com.saurabh.financewidget.databinding.DialogWatchlistNameBinding
import com.saurabh.financewidget.databinding.FragmentWatchlistBinding
import com.saurabh.financewidget.databinding.ItemWatchlistTabBinding
import com.saurabh.financewidget.ui.config.WidgetConfigActivity
import com.saurabh.financewidget.ui.detail.StockDetailActivity
import com.saurabh.financewidget.utils.Resource

class WatchlistFragment : Fragment() {

    private var _binding: FragmentWatchlistBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: WatchlistAdapter

    /** Map groupId → tab view for quick lookup. */
    private val tabViews = mutableMapOf<Long, View>()

    companion object {
        private const val MAX_WATCHLISTS = 5
    }

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
            binding.btnAddStock.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val groupId = viewModel.activeGroupId.value ?: 1L
            WidgetConfigActivity.start(requireActivity(), groupId)
        }

        binding.btnAddWatchlist.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val currentCount = viewModel.watchlistGroups.value?.size ?: 0
            if (currentCount >= MAX_WATCHLISTS) {
                Snackbar.make(
                    binding.root,
                    "Maximum $MAX_WATCHLISTS watchlists reached",
                    Snackbar.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            showCreateWatchlistDialog()
        }
    }

    // ── Tab strip ─────────────────────────────────────────────────────────────

    private fun rebuildTabs(groups: List<WatchlistGroupEntity>, activeId: Long) {
        val container = binding.llTabs
        // Remove stale tab views
        val existingIds = tabViews.keys.toSet()
        val newIds = groups.map { it.id }.toSet()
        (existingIds - newIds).forEach { removedId ->
            tabViews[removedId]?.let { container.removeView(it) }
            tabViews.remove(removedId)
        }

        groups.forEach { group ->
            val tabView = tabViews.getOrPut(group.id) {
                val tabBinding = ItemWatchlistTabBinding.inflate(
                    LayoutInflater.from(requireContext()), container, false
                )
                // Long-press → rename / delete popup
                tabBinding.llWatchlistTab.setOnLongClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showTabOptionsMenu(v, group)
                    true
                }
                tabBinding.llWatchlistTab.setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    viewModel.selectGroup(group.id)
                }
                container.addView(tabBinding.root)
                tabBinding.root
            }

            // Update label
            tabView.findViewById<TextView>(R.id.tv_tab_name).text = group.name

            // Active indicator
            val isActive = group.id == activeId
            val tabLabel = tabView.findViewById<TextView>(R.id.tv_tab_name)
            tabLabel.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isActive) R.color.text_primary else R.color.text_tertiary
                )
            )
            tabView.background = if (isActive)
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_watchlist_tab_active)
            else null

            // Scroll active tab into view
            if (isActive) {
                binding.hsvTabs.post { binding.hsvTabs.smoothScrollTo(tabView.left - 12, 0) }
            }
        }
    }

    private fun showTabOptionsMenu(anchor: View, group: WatchlistGroupEntity) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Rename")
        // Allow delete only when > 1 group exists
        val groupCount = viewModel.watchlistGroups.value?.size ?: 1
        if (groupCount > 1) {
            popup.menu.add(0, 2, 1, "Delete")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showRenameWatchlistDialog(group)
                2 -> confirmDeleteWatchlist(group)
            }
            true
        }
        popup.show()
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showCreateWatchlistDialog() {
        val dialogBinding = DialogWatchlistNameBinding.inflate(LayoutInflater.from(requireContext()))
        dialogBinding.etWatchlistName.hint = "e.g. Tech, Crypto, India…"

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("New Watchlist")
            .setView(dialogBinding.root)
            .setPositiveButton("Create", null)   // overridden below to validate
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialogBinding.etWatchlistName.requestFocus()
            showKeyboard(dialogBinding.etWatchlistName)

            dialogBinding.etWatchlistName.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    triggerCreateWatchlist(dialogBinding, dialog)
                    true
                } else false
            }

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                triggerCreateWatchlist(dialogBinding, dialog)
            }
        }
        dialog.show()
    }

    private fun triggerCreateWatchlist(
        dialogBinding: DialogWatchlistNameBinding,
        dialog: androidx.appcompat.app.AlertDialog
    ) {
        val name = dialogBinding.etWatchlistName.text?.toString()?.trim() ?: ""
        if (name.isEmpty()) {
            dialogBinding.tilWatchlistName.error = "Name cannot be empty"
            return
        }
        val groups = viewModel.watchlistGroups.value ?: emptyList()
        if (groups.size >= MAX_WATCHLISTS) {
            dialogBinding.tilWatchlistName.error = "Maximum $MAX_WATCHLISTS watchlists reached"
            return
        }
        val isDuplicate = groups.any { it.name.equals(name, ignoreCase = true) }
        if (isDuplicate) {
            dialogBinding.tilWatchlistName.error = "A watchlist named \"$name\" already exists"
            return
        }
        hideKeyboard(dialogBinding.etWatchlistName)
        dialog.dismiss()
        viewModel.createWatchlistGroup(name)
        Snackbar.make(binding.root, "\"$name\" created", Snackbar.LENGTH_SHORT).show()
    }

    private fun showRenameWatchlistDialog(group: WatchlistGroupEntity) {
        val dialogBinding = DialogWatchlistNameBinding.inflate(LayoutInflater.from(requireContext()))
        dialogBinding.etWatchlistName.setText(group.name)
        dialogBinding.etWatchlistName.selectAll()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename Watchlist")
            .setView(dialogBinding.root)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialogBinding.etWatchlistName.requestFocus()
            showKeyboard(dialogBinding.etWatchlistName)

            dialogBinding.etWatchlistName.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    triggerRenameWatchlist(dialogBinding, dialog, group)
                    true
                } else false
            }

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                triggerRenameWatchlist(dialogBinding, dialog, group)
            }
        }
        dialog.show()
    }

    private fun triggerRenameWatchlist(
        dialogBinding: DialogWatchlistNameBinding,
        dialog: androidx.appcompat.app.AlertDialog,
        group: WatchlistGroupEntity
    ) {
        val name = dialogBinding.etWatchlistName.text?.toString()?.trim() ?: ""
        if (name.isEmpty()) {
            dialogBinding.tilWatchlistName.error = "Name cannot be empty"
            return
        }
        // Duplicate check — ignore the group's own current name so saving unchanged name works
        val groups = viewModel.watchlistGroups.value ?: emptyList()
        val isDuplicate = groups.any {
            it.id != group.id && it.name.equals(name, ignoreCase = true)
        }
        if (isDuplicate) {
            dialogBinding.tilWatchlistName.error = "A watchlist named \"$name\" already exists"
            return
        }
        hideKeyboard(dialogBinding.etWatchlistName)
        dialog.dismiss()
        viewModel.renameWatchlistGroup(group.id, name)
    }

    private fun confirmDeleteWatchlist(group: WatchlistGroupEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete \"${group.name}\"?")
            .setMessage("All stocks in this watchlist will be removed. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteWatchlistGroup(group.id)
                Snackbar.make(binding.root, "\"${group.name}\" deleted", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── RecyclerView ─────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = WatchlistAdapter(
            onStockClick = { stock ->
                binding.root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                StockDetailActivity.start(requireActivity(), stock.symbol)
            },
            onRemoveClick = { stock ->
                binding.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
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
                rv.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                recyclerView.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                val activeGroupItems = viewModel.activeGroupWatchlist.value ?: return
                val reordered = adapter.items.mapIndexed { index, stock ->
                    // Find the original WatchlistEntity to preserve groupId
                    val original = activeGroupItems.firstOrNull { it.symbol == stock.symbol }
                    com.saurabh.financewidget.data.database.WatchlistEntity(
                        symbol = stock.symbol,
                        displayName = stock.companyName,
                        position = index,
                        groupId = original?.groupId ?: (viewModel.activeGroupId.value ?: 1L)
                    )
                }
                viewModel.reorderWatchlist(reordered)
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    binding.swipeRefreshLayout.isEnabled = false
                    viewHolder?.itemView?.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                    binding.swipeRefreshLayout.isEnabled = true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val stock = adapter.items[viewHolder.adapterPosition]
                viewModel.removeFromWatchlist(stock.symbol)
                viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                Snackbar.make(binding.root, "${stock.symbol} removed", Snackbar.LENGTH_LONG)
                    .setAction("UNDO") { viewModel.addToWatchlist(stock.symbol, stock.companyName) }
                    .show()
            }

            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX < 0) {
                    val itemView  = viewHolder.itemView
                    val ctx       = recyclerView.context
                    val density   = ctx.resources.displayMetrics.density
                    val revealed  = -dX

                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ContextCompat.getColor(ctx, R.color.loss_red)
                    }
                    c.drawRect(
                        itemView.right + dX,
                        itemView.top.toFloat(),
                        itemView.right.toFloat(),
                        itemView.bottom.toFloat(),
                        bgPaint
                    )

                    if (revealed >= 60 * density) {
                        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color     = Color.WHITE
                            textSize  = 13f * density
                            typeface  = Typeface.DEFAULT_BOLD
                            textAlign = Paint.Align.CENTER
                        }
                        val cx = itemView.right - revealed / 2f
                        val cy = (itemView.top + itemView.bottom) / 2f -
                            (textPaint.descent() + textPaint.ascent()) / 2f
                        c.drawText("Remove", cx, cy, textPaint)
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerViewWatchlist)
    }

    // ── SwipeRefresh ──────────────────────────────────────────────────────────

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener { viewModel.refresh() }
        binding.swipeRefreshLayout.setColorSchemeResources(R.color.gain_green)
    }

    // ── ViewModel observers ───────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.watchlistStocks.observe(viewLifecycleOwner) { stocks ->
            val wasEmpty = adapter.items.isEmpty()
            val isNowPopulated = stocks.isNotEmpty()

            // When transitioning from 0 items → N items, the DefaultItemAnimator plays
            // "add" animations for every item at the same moment RecyclerView goes from
            // GONE to VISIBLE — that's the flicker. Disabling the animator for just this
            // one frame (and restoring it on the next post) gives a clean instant render.
            if (wasEmpty && isNowPopulated) {
                val savedAnimator = binding.recyclerViewWatchlist.itemAnimator
                binding.recyclerViewWatchlist.itemAnimator = null
                binding.recyclerViewWatchlist.post {
                    binding.recyclerViewWatchlist.itemAnimator = savedAnimator
                }
            }

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

        // Groups + active group → rebuild tab strip together
        viewModel.watchlistGroups.observe(viewLifecycleOwner) { groups ->
            val activeId = viewModel.activeGroupId.value ?: 1L
            rebuildTabs(groups, activeId)
            // Dim the + button when the limit is reached
            val atLimit = groups.size >= MAX_WATCHLISTS
            binding.btnAddWatchlist.alpha = if (atLimit) 0.35f else 1f
        }
        viewModel.activeGroupId.observe(viewLifecycleOwner) { activeId ->
            val groups = viewModel.watchlistGroups.value ?: return@observe
            rebuildTabs(groups, activeId)
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyStateLayout.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerViewWatchlist.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    // ── Keyboard helpers ──────────────────────────────────────────────────────

    private fun showKeyboard(view: View) {
        val imm = requireContext().getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tabViews.clear()
        _binding = null
    }
}
