package com.saurabh.financewidget.ui.networth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.saurabh.financewidget.R
import com.saurabh.financewidget.data.database.AssetType
import com.saurabh.financewidget.data.database.NetWorthAssetEntity
import com.saurabh.financewidget.databinding.DialogAddAssetBinding
import com.saurabh.financewidget.databinding.FragmentNetworthBinding
import com.saurabh.financewidget.utils.Resource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

@AndroidEntryPoint
class NetWorthFragment : Fragment() {

    private var _binding: FragmentNetworthBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NetWorthViewModel by viewModels()
    private val inrFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    private val adapters = mutableMapOf<AssetType, NetWorthAssetAdapter>()

    // Tracks full data per section (before top-N trimming)
    private val fullListCache = mutableMapOf<AssetType, List<NetWorthAssetEntity>>()

    // Tracks whether each section is expanded (showing all) or collapsed (top 3)
    private val sectionShowAll = mutableMapOf(
        AssetType.STOCK_IN to false,
        AssetType.STOCK_US to false,
        AssetType.MF       to false,
        AssetType.GOLD     to false,
        AssetType.SILVER   to false,
        AssetType.CRYPTO   to false,
        AssetType.CASH     to false,
        AssetType.BANK     to false
    )

    private val sectionExpanded = mutableMapOf(
        AssetType.STOCK_IN to false,
        AssetType.STOCK_US to false,
        AssetType.MF       to false,
        AssetType.GOLD     to false,
        AssetType.SILVER   to false,
        AssetType.CRYPTO   to false,
        AssetType.CASH     to false,
        AssetType.BANK     to false
    )

    private val isFetchable = setOf(
        AssetType.STOCK_IN, AssetType.STOCK_US, AssetType.CRYPTO, AssetType.GOLD, AssetType.SILVER
    )

    // Active allocation tab filter — null means "All"
    private var activeFilter: AssetType? = null

    // The chip TextView for "All" tab, kept for selected-state toggling
    private var allChipView: TextView? = null
    // Map from AssetType → its chip TextView
    private val typeChips = mutableMapOf<AssetType, TextView>()

    private val indianStockSymbols = listOf(
        "RELIANCE.NS", "TCS.NS", "HDFCBANK.NS", "INFY.NS", "ICICIBANK.NS",
        "HINDUNILVR.NS", "ITC.NS", "SBIN.NS", "BAJFINANCE.NS", "LT.NS",
        "KOTAKBANK.NS", "AXISBANK.NS", "BHARTIARTL.NS", "ASIANPAINT.NS",
        "MARUTI.NS", "WIPRO.NS", "HCLTECH.NS", "TATAMOTORS.NS", "SUNPHARMA.NS",
        "M&M.NS", "TITAN.NS", "ULTRACEMCO.NS", "NESTLEIND.NS", "POWERGRID.NS",
        "TECHM.NS", "BAJAJFINSV.NS", "NTPC.NS", "ADANIPORTS.NS", "JSWSTEEL.NS",
        "ONGC.NS", "COALINDIA.NS", "GRASIM.NS", "DIVISLAB.NS", "CIPLA.NS",
        "HINDALCO.NS", "EICHERMOT.NS", "TATASTEEL.NS", "BPCL.NS", "BRITANNIA.NS",
        "APOLLOHOSP.NS", "TRENT.NS", "ZOMATO.NS", "NAUKRI.NS", "PAYTM.NS",
        "DMART.NS", "HAL.NS", "BEL.NS", "IRCTC.NS", "PIDILITIND.NS", "SIEMENS.NS",
        "BAJAJ-AUTO.NS", "HEROMOTOCO.NS", "DRREDDY.NS", "LUPIN.NS", "TORNTPHARM.NS",
        "VEDL.NS", "INDUSINDBK.NS", "BANDHANBNK.NS", "FEDERALBNK.NS", "PNB.NS"
    )

    private val usStockSymbols = listOf(
        "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA", "BRK-B",
        "V", "JPM", "UNH", "XOM", "JNJ", "MA", "PG", "HD", "AVGO", "CVX",
        "MRK", "ABBVIE", "COST", "PEP", "KO", "ADBE", "CRM", "NFLX", "AMD",
        "ORCL", "ACN", "LIN", "MCD", "CSCO", "TMO", "ABT", "BAC", "INTC",
        "WMT", "QCOM", "DIS", "NEE", "HON", "PFE", "GE", "PM"
    )

    companion object {
        private const val TOP_N = 3 // items shown before "View all"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNetworthBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerViews()
        setupHeaders()
        setupAddButtons()
        setupViewAllButtons()
        observeViewModel()
    }

    private fun setupRecyclerViews() {
        val map = mapOf(
            AssetType.STOCK_IN to binding.rvStockIn,
            AssetType.STOCK_US to binding.rvStockUs,
            AssetType.MF       to binding.rvMf,
            AssetType.GOLD     to binding.rvGold,
            AssetType.SILVER   to binding.rvSilver,
            AssetType.CRYPTO   to binding.rvCrypto,
            AssetType.CASH     to binding.rvCash,
            AssetType.BANK     to binding.rvBank
        )
        for ((type, rv) in map) {
            val adapter = NetWorthAssetAdapter(
                onDeleteClick = { asset -> confirmDelete(asset) },
                onEditClick   = { asset -> showEditDialog(asset) }
            )
            adapters[type] = adapter
            rv.apply {
                layoutManager = LinearLayoutManager(requireContext())
                this.adapter = adapter
                isNestedScrollingEnabled = false
                isVisible = sectionExpanded[type] == true
            }
        }
    }

    private fun setupHeaders() {
        fun wireHeader(header: View, rv: RecyclerView, type: AssetType) {
            header.setOnClickListener {
                val nowExpanded = !(sectionExpanded[type] ?: true)
                sectionExpanded[type] = nowExpanded
                rv.isVisible = nowExpanded
                // Show "View all" button only when expanded AND section has more than TOP_N items
                val full = fullListCache[type] ?: emptyList()
                viewAllButtonFor(type)?.isVisible = nowExpanded && full.size > TOP_N
            }
        }

        wireHeader(binding.headerStockIn, binding.rvStockIn, AssetType.STOCK_IN)
        wireHeader(binding.headerStockUs, binding.rvStockUs, AssetType.STOCK_US)
        wireHeader(binding.headerMf,      binding.rvMf,      AssetType.MF)
        wireHeader(binding.headerGold,    binding.rvGold,    AssetType.GOLD)
        wireHeader(binding.headerSilver,  binding.rvSilver,  AssetType.SILVER)
        wireHeader(binding.headerCrypto,  binding.rvCrypto,  AssetType.CRYPTO)
        wireHeader(binding.headerCash,    binding.rvCash,    AssetType.CASH)
        wireHeader(binding.headerBank,    binding.rvBank,    AssetType.BANK)
    }

    private fun setupAddButtons() {
        binding.btnAddStockIn.setOnClickListener { showAddDialog(AssetType.STOCK_IN, "Add Indian Stock") }
        binding.btnAddStockUs.setOnClickListener { showAddDialog(AssetType.STOCK_US, "Add US Stock") }
        binding.btnAddMf.setOnClickListener      { showAddDialog(AssetType.MF,       "Add Mutual Fund") }
        binding.btnAddGold.setOnClickListener    { showAddDialog(AssetType.GOLD,     "Add Gold") }
        binding.btnAddSilver.setOnClickListener  { showAddDialog(AssetType.SILVER,   "Add Silver") }
        binding.btnAddCrypto.setOnClickListener  { showAddDialog(AssetType.CRYPTO,   "Add Crypto") }
        binding.btnAddCash.setOnClickListener    { showAddDialog(AssetType.CASH,     "Add Cash on Hand") }
        binding.btnAddBank.setOnClickListener    { showAddDialog(AssetType.BANK,     "Add Bank Balance") }
    }

    /** Wire up each "View all" / "View less" button */
    private fun setupViewAllButtons() {
        val viewAllMap = mapOf(
            AssetType.STOCK_IN to binding.tvViewAllStockIn,
            AssetType.STOCK_US to binding.tvViewAllStockUs,
            AssetType.MF       to binding.tvViewAllMf,
            AssetType.GOLD     to binding.tvViewAllGold,
            AssetType.SILVER   to binding.tvViewAllSilver,
            AssetType.CRYPTO   to binding.tvViewAllCrypto,
            AssetType.CASH     to binding.tvViewAllCash,
            AssetType.BANK     to binding.tvViewAllBank
        )
        for ((type, btn) in viewAllMap) {
            btn.setOnClickListener {
                val nowAll = !(sectionShowAll[type] ?: false)
                sectionShowAll[type] = nowAll
                btn.text = if (nowAll) "View less" else "View all"
                // Re-submit the (possibly trimmed) list
                val full = fullListCache[type] ?: emptyList()
                val displayed = if (nowAll) full else full.take(TOP_N)
                adapters[type]?.submitList(displayed)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.totalNetWorth.observe(viewLifecycleOwner) { total ->
            binding.tvTotalNetworth.text = inrFormat.format(total ?: 0.0)
        }

        viewModel.totalPnL.observe(viewLifecycleOwner) { (absChange, pct) ->
            val chip = binding.tvTotalPnl
            if (absChange == 0.0 && pct == 0.0) {
                chip.isVisible = false
                return@observe
            }
            val isGain   = absChange >= 0
            val arrow    = if (isGain) "↗" else "↘"
            val sign     = if (isGain) "+" else "-"
            val textColor = requireContext().getColor(
                if (isGain) R.color.gain_green else R.color.loss_red
            )
            val bgColor  = requireContext().getColor(
                if (isGain) R.color.gain_green_bg else R.color.loss_red_bg
            )

            chip.text = "$arrow $sign${inrFormat.format(kotlin.math.abs(absChange))} (${"%.2f".format(kotlin.math.abs(pct))}%)"
            chip.setTextColor(textColor)

            // Tint background programmatically using a GradientDrawable clone
            val bg = androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.bg_pnl_chip
            )?.mutate() as? android.graphics.drawable.GradientDrawable
            bg?.setColor(bgColor)
            chip.background = bg

            chip.isVisible = true
        }

        viewModel.allAssets.observe(viewLifecycleOwner) { all ->
            val grouped = all.groupBy { it.assetType }
            for ((type, adapter) in adapters) {
                val list = grouped[type] ?: emptyList()
                // Sort by currentValue descending so top holders appear first
                val sorted = list.sortedByDescending { it.currentValue }
                fullListCache[type] = sorted

                val showAll = sectionShowAll[type] == true
                val displayed = if (showAll) sorted else sorted.take(TOP_N)
                adapter.submitList(displayed)

                // Show/hide "View all" button
                val viewAllBtn = viewAllButtonFor(type)
                if (sorted.size > TOP_N) {
                    viewAllBtn?.isVisible = sectionExpanded[type] == true
                    viewAllBtn?.text = if (showAll) "View less" else "View all"
                } else {
                    viewAllBtn?.isVisible = false
                }
            }

            fun updateCount(countView: android.widget.TextView, type: AssetType) {
                val count = grouped[type]?.size ?: 0
                if (count > 0) {
                    countView.text = count.toString()
                    countView.isVisible = true
                } else {
                    countView.isVisible = false
                }
            }
            updateCount(binding.tvCountStockIn, AssetType.STOCK_IN)
            updateCount(binding.tvCountStockUs, AssetType.STOCK_US)
            updateCount(binding.tvCountMf,      AssetType.MF)
            updateCount(binding.tvCountGold,    AssetType.GOLD)
            updateCount(binding.tvCountSilver,  AssetType.SILVER)
            updateCount(binding.tvCountCrypto,  AssetType.CRYPTO)
            updateCount(binding.tvCountCash,    AssetType.CASH)
            updateCount(binding.tvCountBank,    AssetType.BANK)
        }

        viewModel.assetSummary.observe(viewLifecycleOwner) { summary ->
            binding.tvTotalStockIn.text = inrFormat.format(summary[AssetType.STOCK_IN] ?: 0.0)
            binding.tvTotalStockUs.text = inrFormat.format(summary[AssetType.STOCK_US] ?: 0.0)
            binding.tvTotalMf.text      = inrFormat.format(summary[AssetType.MF]       ?: 0.0)
            binding.tvTotalGold.text    = inrFormat.format(summary[AssetType.GOLD]     ?: 0.0)
            binding.tvTotalSilver.text  = inrFormat.format(summary[AssetType.SILVER]   ?: 0.0)
            binding.tvTotalCrypto.text  = inrFormat.format(summary[AssetType.CRYPTO]   ?: 0.0)
            binding.tvTotalCash.text    = inrFormat.format(summary[AssetType.CASH]     ?: 0.0)
            binding.tvTotalBank.text    = inrFormat.format(summary[AssetType.BANK]     ?: 0.0)

            updateBreakdownUI(summary)
        }
    }

    // ─── View All toggle helper ───────────────────────────────────────────────

    private fun viewAllButtonFor(type: AssetType): TextView? = when (type) {
        AssetType.STOCK_IN -> binding.tvViewAllStockIn
        AssetType.STOCK_US -> binding.tvViewAllStockUs
        AssetType.MF       -> binding.tvViewAllMf
        AssetType.GOLD     -> binding.tvViewAllGold
        AssetType.SILVER   -> binding.tvViewAllSilver
        AssetType.CRYPTO   -> binding.tvViewAllCrypto
        AssetType.CASH     -> binding.tvViewAllCash
        AssetType.BANK     -> binding.tvViewAllBank
    }

    // ─── Breakdown + Allocation Tabs ─────────────────────────────────────────

    private fun updateBreakdownUI(summary: Map<AssetType, Double>) {
        val total = summary.values.sum()
        if (total <= 0) {
            binding.llBreakdownContainer.isVisible = false
            return
        }

        binding.llBreakdownContainer.isVisible = true
        binding.llSegmentedBar.removeAllViews()
        binding.llBreakdownList.removeAllViews()

        val sorted = summary.entries.filter { it.value > 0 }.sortedByDescending { it.value }

        // ── Allocation chip tabs ──────────────────────────────────────────────
        binding.llAllocationTabs.removeAllViews()
        typeChips.clear()

        // "All" chip
        val allChip = buildChip("All · ${inrFormat.format(total)}", null, isAll = true)
        allChipView = allChip
        binding.llAllocationTabs.addView(allChip)

        for (entry in sorted) {
            val type  = entry.key
            val value = entry.value
            val chip  = buildChip(
                "${getCategoryName(type)} · ${inrFormat.format(value)}",
                type,
                isAll = false
            )
            typeChips[type] = chip
            binding.llAllocationTabs.addView(chip)
        }

        // Apply current filter selection state
        refreshChipStates()

        // ── Segmented bar ─────────────────────────────────────────────────────
        sorted.forEachIndexed { index, entry ->
            val type  = entry.key
            val value = entry.value
            val color = getCategoryColor(type)

            val segment = View(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, value.toFloat()
                ).apply {
                    if (index < sorted.size - 1) {
                        marginEnd = (1.5f * resources.displayMetrics.density).toInt()
                    }
                }
                setBackgroundColor(color)
            }
            binding.llSegmentedBar.addView(segment)
        }

        // ── Breakdown list ────────────────────────────────────────────────────
        val displayList = if (activeFilter == null) sorted
                          else sorted.filter { it.key == activeFilter }

        displayList.forEach { (type, value) ->
            val pct = (value / total) * 100.0
            val color = getCategoryColor(type)

            val itemBinding = com.saurabh.financewidget.databinding.ItemBreakdownCategoryBinding.inflate(
                layoutInflater, binding.llBreakdownList, false
            )
            itemBinding.vColorIndicator.setCardBackgroundColor(color)
            itemBinding.tvCategoryName.text = getCategoryName(type)
            itemBinding.tvCategoryAmount.text = inrFormat.format(value)
            itemBinding.tvCategoryPercentage.text = "%.1f%%".format(pct)

            // Clicking the row acts as a filter tab
            itemBinding.root.setOnClickListener {
                activeFilter = if (activeFilter == type) null else type
                updateBreakdownUI(summary)
                refreshChipStates()
                updateSectionVisibility()
            }

            binding.llBreakdownList.addView(itemBinding.root)
        }
    }

    /** Creates a single allocation chip TextView */
    private fun buildChip(
        label: String,
        type: AssetType?,
        isAll: Boolean
    ): TextView {
        val density  = resources.displayMetrics.density
        val hPadPx   = (12 * density).toInt()
        val vPadPx   = (6  * density).toInt()
        val marginPx = (6  * density).toInt()

        return TextView(requireContext()).apply {
            text = label
            textSize = 12f

            // Inter font — explicitly NORMAL style to prevent system italic
            try {
                val typeface = ResourcesCompat.getFont(context, R.font.inter_semi_bold)
                setTypeface(typeface, android.graphics.Typeface.NORMAL)
            } catch (_: Exception) {}

            setPadding(hPadPx, vPadPx, hPadPx, vPadPx)
            background = requireContext().getDrawable(R.drawable.bg_allocation_chip)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = marginPx }

            isSelected = false
            setTextColor(requireContext().getColor(R.color.text_secondary))

            setOnClickListener {
                activeFilter = if (isAll) null else type
                viewModel.assetSummary.value?.let { summary ->
                    updateBreakdownUI(summary)
                }
                refreshChipStates()
                updateSectionVisibility()
            }
        }
    }

    /** Update selected/unselected visual state on all chips */
    private fun refreshChipStates() {
        val selectedColor   = requireContext().getColor(R.color.on_primary) // white
        val unselectedColor = requireContext().getColor(R.color.text_secondary)

        // "All" chip
        allChipView?.apply {
            isSelected = (activeFilter == null)
            setTextColor(if (activeFilter == null) selectedColor else unselectedColor)
        }

        typeChips.forEach { (type, chip) ->
            val sel = (activeFilter == type)
            chip.isSelected = sel
            chip.setTextColor(if (sel) selectedColor else unselectedColor)
        }
    }

    /** Show only the section(s) that match the active filter; show all when filter is null */
    private fun updateSectionVisibility() {
        val sectionMap = mapOf(
            AssetType.STOCK_IN to binding.sectionStockIn,
            AssetType.STOCK_US to binding.sectionStockUs,
            AssetType.MF       to binding.sectionMf,
            AssetType.GOLD     to binding.sectionGold,
            AssetType.SILVER   to binding.sectionSilver,
            AssetType.CRYPTO   to binding.sectionCrypto,
            AssetType.CASH     to binding.sectionCash,
            AssetType.BANK     to binding.sectionBank
        )
        val filter = activeFilter
        for ((type, container) in sectionMap) {
            container.isVisible = (filter == null || filter == type)
        }
    }

    // ─── Colour + Name helpers ────────────────────────────────────────────────

    private fun getCategoryColor(type: AssetType): Int {
        val colorRes = when (type) {
            AssetType.STOCK_IN -> R.color.cat_stock_in
            AssetType.STOCK_US -> R.color.cat_stock_us
            AssetType.MF       -> R.color.cat_mf
            AssetType.GOLD     -> R.color.cat_gold
            AssetType.SILVER   -> R.color.cat_silver
            AssetType.CRYPTO   -> R.color.cat_crypto
            AssetType.CASH     -> R.color.cat_cash
            AssetType.BANK     -> R.color.cat_bank
        }
        return requireContext().getColor(colorRes)
    }

    private fun getCategoryName(type: AssetType): String {
        return when (type) {
            AssetType.STOCK_IN -> "Indian Stocks"
            AssetType.STOCK_US -> "US Stocks"
            AssetType.MF       -> "Mutual Funds"
            AssetType.GOLD     -> "Gold"
            AssetType.SILVER   -> "Silver"
            AssetType.CRYPTO   -> "Crypto"
            AssetType.CASH     -> "Cash on Hand"
            AssetType.BANK     -> "Bank Balance"
        }
    }

    // ─── Add Dialog ──────────────────────────────────────────────────────────

    private fun showAddDialog(type: AssetType, title: String) {
        val d = DialogAddAssetBinding.inflate(layoutInflater)
        val fetchable = type in isFetchable

        d.tilSymbol.isVisible    = fetchable
        d.llPriceCard.isVisible  = false
        d.tilQuantity.isVisible  = false   
        d.tilName.isVisible      = !fetchable
        d.llFetchRow.isVisible   = false   

        d.tilValue.isVisible = !fetchable

        var fetchedPricePerUnit = 0.0

        if (fetchable) {
            when (type) {
                AssetType.STOCK_IN -> {
                    d.tilSymbol.hint   = "Search NSE symbol (e.g. RELIANCE, TCS)"
                    d.tilQuantity.hint = "Number of shares"
                    wireSymbolDropdown(d, indianStockSymbols, type) { price ->
                        fetchedPricePerUnit = price
                    }
                }
                AssetType.STOCK_US -> {
                    d.tilSymbol.hint   = "Search US ticker (e.g. AAPL, NVDA)"
                    d.tilQuantity.hint = "Number of shares"
                    wireSymbolDropdown(d, usStockSymbols, type) { price ->
                        fetchedPricePerUnit = price
                    }
                }
                AssetType.GOLD -> {

                    d.tilSymbol.isVisible  = false
                    d.tilQuantity.hint = "Quantity in grams"

                    d.tilNotes.isVisible = true

                    triggerFetch(d, "GC=F", type) { price -> fetchedPricePerUnit = price }
                }
                AssetType.SILVER -> {

                    d.tilSymbol.isVisible  = false
                    d.tilQuantity.hint = "Quantity in grams"

                    d.tilNotes.isVisible = true

                    triggerFetch(d, "SI=F", type) { price -> fetchedPricePerUnit = price }
                }
                AssetType.CRYPTO -> {
                    d.tilSymbol.hint   = "Pair (e.g. BTC-INR, ETH-INR)"
                    d.tilQuantity.hint = "Quantity of coins"
                    val ac = d.etSymbol as? AutoCompleteTextView

                    ac?.setOnFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) {
                            val sym = ac.text?.toString()?.trim() ?: return@setOnFocusChangeListener
                            if (sym.isNotBlank()) triggerFetch(d, sym, type) { price -> fetchedPricePerUnit = price }
                        }
                    }

                    ac?.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE ||
                            actionId == EditorInfo.IME_ACTION_SEARCH ||
                            actionId == EditorInfo.IME_ACTION_GO) {
                            val sym = ac.text?.toString()?.trim() ?: ""
                            if (sym.isNotBlank()) {
                                d.tilSymbol.error = null
                                triggerFetch(d, sym, type) { price -> fetchedPricePerUnit = price }
                            }
                            true
                        } else false
                    }
                }
                else -> {}
            }
        }

        var isAutoUpdating = false

        d.etQuantity.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isAutoUpdating || !d.etQuantity.hasFocus()) return
                if (fetchedPricePerUnit > 0) {
                    val qty = s?.toString()?.toDoubleOrNull()
                    isAutoUpdating = true
                    if (qty != null) {
                        d.etValue.setText("%.2f".format(fetchedPricePerUnit * qty))
                    } else if (s.isNullOrEmpty()) {
                        d.etValue.text?.clear()
                    }
                    isAutoUpdating = false
                }
            }
        })

        d.etValue.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isAutoUpdating || !d.etValue.hasFocus()) return
                if (fetchedPricePerUnit > 0) {
                    val value = s?.toString()?.toDoubleOrNull()
                    isAutoUpdating = true
                    if (value != null) {
                        val fmt = if (type == AssetType.CRYPTO) "%.8f" else "%.4f"
                        val calcQty = value / fetchedPricePerUnit

                        d.etQuantity.setText(fmt.format(calcQty))
                    } else if (s.isNullOrEmpty()) {
                        d.etQuantity.text?.clear()
                    }
                    isAutoUpdating = false
                }
            }
        })

        val dialog = AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle(title)
            .setView(d.root)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = d.etValue.text?.toString()?.toDoubleOrNull()

                val name = when {
                    type == AssetType.GOLD   -> "GOLD"
                    type == AssetType.SILVER -> "SILVER"
                    fetchable -> {
                        (d.etSymbol as? AutoCompleteTextView)?.text?.toString()?.trim()?.uppercase()
                            ?.ifBlank { "" } ?: ""
                    }
                    else -> d.etName.text?.toString()?.trim() ?: ""
                }

                val qty = if (fetchable) d.etQuantity.text?.toString()?.toDoubleOrNull() ?: 1.0 else 1.0

                if (name.isBlank() && type != AssetType.GOLD && type != AssetType.SILVER) {
                    d.tilSymbol.error = "Select a symbol first"
                    return@setOnClickListener
                }
                if (value == null || value <= 0) {
                    d.tilValue.error = "Enter a valid amount"
                    return@setOnClickListener
                }

                viewModel.addOrMergeAsset(
                    NetWorthAssetEntity(
                        name = name.ifBlank { if (type == AssetType.GOLD) "GOLD" else name },
                        assetType = type,
                        quantity = qty,
                        buyPrice = d.etBuyPrice.text?.toString()?.toDoubleOrNull() ?: 0.0,
                        currentValue = value,
                        notes = d.etNotes.text?.toString()?.trim() ?: ""
                    )
                )

                if (sectionExpanded[type] == false) {
                    sectionExpanded[type] = true
                    rvForType(type)?.isVisible = true
                }

                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun wireSymbolDropdown(
        d: DialogAddAssetBinding,
        symbols: List<String>,
        type: AssetType,
        onPriceFetched: (Double) -> Unit
    ) {
        val autoComplete = d.etSymbol as? AutoCompleteTextView ?: return

        val adapter = object : ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            symbols.toMutableList()
        ) {
            private val allItems = symbols.toList()
            private val containsFilter = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val results = FilterResults()
                    if (constraint.isNullOrBlank()) {
                        results.values = allItems
                        results.count = allItems.size
                    } else {
                        val query = constraint.toString().uppercase()
                        val filtered = allItems.filter { it.contains(query, ignoreCase = true) }
                        results.values = filtered
                        results.count = filtered.size
                    }
                    return results
                }

                @Suppress("UNCHECKED_CAST")
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    clear()
                    addAll(results?.values as? List<String> ?: emptyList())
                    if ((results?.count ?: 0) > 0) notifyDataSetChanged()
                    else notifyDataSetInvalidated()
                }
            }
            override fun getFilter(): Filter = containsFilter
        }

        autoComplete.setAdapter(adapter)
        autoComplete.threshold = 1

        autoComplete.setOnItemClickListener { _, _, _, _ ->
            val selected = autoComplete.text?.toString()?.trim()?.uppercase() ?: return@setOnItemClickListener
            if (selected.isNotBlank()) {
                val normalised = normaliseSymbol(selected, type)

                autoComplete.setText(normalised)
                autoComplete.setSelection(normalised.length)
                d.tilSymbol.error = null
                triggerFetch(d, normalised, type, onPriceFetched)
            }
        }

        autoComplete.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO) {
                val typed = autoComplete.text?.toString()?.trim()?.uppercase() ?: ""
                if (typed.isNotBlank()) {
                    val normalised = normaliseSymbol(typed, type)

                    autoComplete.setText(normalised)
                    autoComplete.setSelection(normalised.length)
                    d.tilSymbol.error = null
                    triggerFetch(d, normalised, type, onPriceFetched)
                }
                true
            } else false
        }
    }

    private fun triggerFetch(
        d: DialogAddAssetBinding,
        symbol: String,
        type: AssetType,
        onPriceFetched: (Double) -> Unit
    ) {

        d.llPriceCard.isVisible    = true
        d.llPriceLoading.isVisible = true
        d.tvFetchStatus.isVisible  = false
        d.tvPriceError.isVisible   = false

        d.tilQuantity.isVisible  = false
        d.tilBuyPrice.isVisible  = false
        d.tilValue.isVisible     = false

        lifecycleScope.launch {
            val result = viewModel.fetchLivePrice(symbol, type)
            d.llPriceLoading.isVisible = false
            when (result) {
                is Resource.Success -> {
                    val price = result.data
                    onPriceFetched(price)
                    val unitLabel = when (type) {
                        AssetType.GOLD   -> "/gram"
                        AssetType.SILVER -> "/gram"
                        AssetType.CRYPTO -> "/coin"
                        else             -> "/share"
                    }
                    d.tvFetchStatus.text = inrFormat.format(price) + unitLabel
                    d.tvFetchStatus.setTextColor(requireContext().getColor(R.color.text_primary))
                    d.tvFetchStatus.isVisible = true
                    d.tvPriceError.isVisible  = false

                    d.tilQuantity.isVisible = true
                    d.tilValue.isVisible    = true

                    val buyHint = when (type) {
                        AssetType.GOLD, AssetType.SILVER -> "Avg Buy Price (₹/gram) — optional"
                        AssetType.CRYPTO                 -> "Avg Buy Price (₹/coin) — optional"
                        else                             -> "Avg Buy Price (₹/share) — optional"
                    }
                    d.tilBuyPrice.hint    = buyHint
                    d.tilBuyPrice.isVisible = true

                    val qty = d.etQuantity.text?.toString()?.toDoubleOrNull()
                    val prefilledValue = d.etValue.text?.toString()?.toDoubleOrNull()
                    if (qty != null && qty > 0) {
                        d.etValue.setText("%.2f".format(price * qty))
                    } else if (prefilledValue != null && prefilledValue > 0) {
                        val fmt = if (type == AssetType.CRYPTO) "%.8f" else "%.4f"
                        d.etQuantity.setText(fmt.format(prefilledValue / price))
                    }
                }
                is Resource.Error -> {
                    d.tvPriceError.text    = "✗ ${result.message}"
                    d.tvPriceError.isVisible  = true
                    d.tvFetchStatus.isVisible = false

                    d.tilQuantity.isVisible  = true
                    d.tilBuyPrice.isVisible  = true
                    d.tilValue.isVisible     = true
                }
                else -> {}
            }
        }
    }

    private fun normaliseSymbol(symbol: String, type: AssetType): String {
        if (type == AssetType.STOCK_IN && !symbol.contains('.')) {
            return "$symbol.NS"
        }
        return symbol
    }

    private fun confirmDelete(asset: NetWorthAssetEntity) {
        AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle("Remove ${asset.name}?")
            .setMessage("This will be removed from your net worth.")
            .setPositiveButton("Remove") { _, _ -> viewModel.deleteAsset(asset) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditDialog(asset: NetWorthAssetEntity) {
        val type = asset.assetType
        val d = DialogAddAssetBinding.inflate(layoutInflater)
        val fetchable = type in isFetchable

        val isMetalType = type == AssetType.GOLD || type == AssetType.SILVER
        d.tilSymbol.isVisible   = fetchable && !isMetalType
        d.llPriceCard.isVisible = false
        d.tilQuantity.isVisible = fetchable
        d.tilName.isVisible     = !fetchable
        d.llFetchRow.isVisible  = false
        d.tilValue.isVisible    = true
        d.tilBuyPrice.isVisible = asset.buyPrice > 0 || fetchable

        d.tilNotes.isVisible    = isMetalType

        if (fetchable) {
            if (!isMetalType) {
                (d.etSymbol as? AutoCompleteTextView)?.setText(asset.name)
            }
            d.etQuantity.setText(
                if (asset.quantity % 1.0 == 0.0) asset.quantity.toInt().toString()
                else "%.4f".format(asset.quantity)
            )
        } else {
            d.etName.setText(asset.name)
        }
        d.etValue.setText("%.2f".format(asset.currentValue))
        if (asset.buyPrice > 0) d.etBuyPrice.setText("%.2f".format(asset.buyPrice))

        if (isMetalType && asset.notes.isNotBlank()) d.etNotes.setText(asset.notes)

        val buyHint = when (type) {
            AssetType.GOLD, AssetType.SILVER -> "Avg Buy Price (₹/gram) — optional"
            AssetType.CRYPTO                 -> "Avg Buy Price (₹/coin) — optional"
            else                             -> "Avg Buy Price (₹/share) — optional"
        }
        d.tilBuyPrice.hint = buyHint

        // Derive price per unit from existing data initially
        var pricePerUnit = if (asset.quantity > 0) asset.currentValue / asset.quantity else 0.0
        var isAutoUpdating = false

        if (fetchable) {
            d.llPriceCard.isVisible = true
            d.llPriceLoading.isVisible = true
            d.tvFetchStatus.isVisible = false
            val fetchSymbol = when (type) {
                AssetType.GOLD -> "GC=F"
                AssetType.SILVER -> "SI=F"
                else -> asset.name
            }
            lifecycleScope.launch {
                val result = viewModel.fetchLivePrice(fetchSymbol, type)
                if (result is Resource.Success) {
                    pricePerUnit = result.data
                    d.llPriceLoading.isVisible = false
                    val unitLabel = when (type) {
                        AssetType.GOLD, AssetType.SILVER -> "/gram"
                        AssetType.CRYPTO -> "/coin"
                        else -> "/share"
                    }
                    d.tvFetchStatus.text = "Live price: ${inrFormat.format(pricePerUnit)}$unitLabel"
                    d.tvFetchStatus.setTextColor(requireContext().getColor(R.color.text_secondary))
                    d.tvFetchStatus.isVisible = true

                    if (!isAutoUpdating && !d.etValue.hasFocus()) {
                        isAutoUpdating = true
                        val qty = d.etQuantity.text?.toString()?.toDoubleOrNull() ?: asset.quantity
                        d.etValue.setText("%.2f".format(pricePerUnit * qty))
                        isAutoUpdating = false
                    }
                } else {
                    d.llPriceCard.isVisible = false
                }
            }
        }

        d.etQuantity.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isAutoUpdating || !d.etQuantity.hasFocus()) return
                if (pricePerUnit > 0) {
                    val qty = s?.toString()?.toDoubleOrNull()
                    isAutoUpdating = true
                    if (qty != null) {
                        d.etValue.setText("%.2f".format(pricePerUnit * qty))
                    } else if (s.isNullOrEmpty()) {
                        d.etValue.text?.clear()
                    }
                    isAutoUpdating = false
                }
            }
        })

        d.etValue.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isAutoUpdating || !d.etValue.hasFocus()) return
                if (pricePerUnit > 0) {
                    val value = s?.toString()?.toDoubleOrNull()
                    isAutoUpdating = true
                    if (value != null) {
                        val fmt = if (type == AssetType.CRYPTO) "%.8f" else "%.4f"
                        val calcQty = value / pricePerUnit
                        d.etQuantity.setText(fmt.format(calcQty))
                    } else if (s.isNullOrEmpty()) {
                        d.etQuantity.text?.clear()
                    }
                    isAutoUpdating = false
                }
            }
        })

        val dialog = AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle("Edit ${asset.name}")
            .setView(d.root)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = d.etValue.text?.toString()?.toDoubleOrNull()
                if (value == null || value <= 0) {
                    d.tilValue.error = "Enter a valid amount"
                    return@setOnClickListener
                }

                val qty = if (fetchable)
                    d.etQuantity.text?.toString()?.toDoubleOrNull() ?: asset.quantity
                else
                    asset.quantity

                val name = when {
                    type == AssetType.GOLD   -> "GOLD"
                    type == AssetType.SILVER -> "SILVER"
                    fetchable -> {
                        (d.etSymbol as? AutoCompleteTextView)?.text?.toString()?.trim()?.uppercase()
                            ?.ifBlank { asset.name } ?: asset.name
                    }
                    else -> d.etName.text?.toString()?.trim()?.ifBlank { asset.name } ?: asset.name
                }

                viewModel.updateAsset(
                    asset.copy(
                        name         = name,
                        quantity     = qty,
                        buyPrice     = d.etBuyPrice.text?.toString()?.toDoubleOrNull() ?: 0.0,
                        currentValue = value,
                        notes        = if (isMetalType) d.etNotes.text?.toString()?.trim() ?: asset.notes else asset.notes,
                        updatedAt    = System.currentTimeMillis()
                    )
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun rvForType(type: AssetType): RecyclerView? = when (type) {
        AssetType.STOCK_IN -> binding.rvStockIn
        AssetType.STOCK_US -> binding.rvStockUs
        AssetType.MF       -> binding.rvMf
        AssetType.GOLD     -> binding.rvGold
        AssetType.SILVER   -> binding.rvSilver
        AssetType.CRYPTO   -> binding.rvCrypto
        AssetType.CASH     -> binding.rvCash
        AssetType.BANK     -> binding.rvBank
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

