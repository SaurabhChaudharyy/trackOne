package com.saurabh.financewidget.ui.networth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import androidx.appcompat.app.AlertDialog
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

    private val sectionExpanded = mutableMapOf(
        AssetType.STOCK_IN to true,
        AssetType.STOCK_US to true,
        AssetType.MF       to true,
        AssetType.GOLD     to true,
        AssetType.SILVER   to true,
        AssetType.CRYPTO   to true,
        AssetType.CASH     to true,
        AssetType.BANK     to true
    )

    private val isFetchable = setOf(
        AssetType.STOCK_IN, AssetType.STOCK_US, AssetType.CRYPTO, AssetType.GOLD, AssetType.SILVER
    )

    // Curated lists of popular symbols shown as dropdown options
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
            }
        }
    }

    private fun setupHeaders() {
        fun wireHeader(header: View, rv: RecyclerView, type: AssetType) {
            header.setOnClickListener {
                val nowExpanded = !(sectionExpanded[type] ?: true)
                sectionExpanded[type] = nowExpanded
                rv.isVisible = nowExpanded
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

    private fun observeViewModel() {
        viewModel.totalNetWorth.observe(viewLifecycleOwner) { total ->
            binding.tvTotalNetworth.text = inrFormat.format(total ?: 0.0)
        }

        viewModel.allAssets.observe(viewLifecycleOwner) { all ->
            val grouped = all.groupBy { it.assetType }
            for ((type, adapter) in adapters) {
                adapter.submitList(grouped[type] ?: emptyList())
            }

            // Update section header labels with count appended inline
            // e.g. "Indian Stocks" → "Indian Stocks  3"
            fun updateLabel(labelView: android.widget.TextView, baseLabel: String, type: AssetType) {
                val count = grouped[type]?.size ?: 0
                labelView.text = if (count > 0) "$baseLabel\u2002$count" else baseLabel
            }
            updateLabel(binding.tvLabelStockIn, "Indian Stocks", AssetType.STOCK_IN)
            updateLabel(binding.tvLabelStockUs, "US Stocks",     AssetType.STOCK_US)
            updateLabel(binding.tvLabelMf,      "Mutual Funds",  AssetType.MF)
            updateLabel(binding.tvLabelGold,    "Gold",          AssetType.GOLD)
            updateLabel(binding.tvLabelSilver,  "Silver",        AssetType.SILVER)
            updateLabel(binding.tvLabelCrypto,  "Crypto",        AssetType.CRYPTO)
            updateLabel(binding.tvLabelCash,    "Cash on Hand",  AssetType.CASH)
            updateLabel(binding.tvLabelBank,    "Bank Balance",  AssetType.BANK)
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
        }
    }

    private fun showAddDialog(type: AssetType, title: String) {
        val d = DialogAddAssetBinding.inflate(layoutInflater)
        val fetchable = type in isFetchable

        // Initial visibility
        d.tilSymbol.isVisible    = fetchable
        d.llPriceCard.isVisible  = false
        d.tilQuantity.isVisible  = false   // shown only after price is fetched
        d.tilName.isVisible      = !fetchable
        d.llFetchRow.isVisible   = false   // legacy stub, always hidden

        // For manual mode, show the value field immediately
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
                    // Gold always uses GC=F — hide the symbol field and auto-fetch on dialog open
                    d.tilSymbol.isVisible  = false
                    d.tilQuantity.hint = "Quantity in grams"
                    // Show notes field so user can label entries (e.g. Physical, Digital)
                    d.tilNotes.isVisible = true
                    // Immediately fetch gold spot price (no user input needed)
                    triggerFetch(d, "GC=F", type) { price -> fetchedPricePerUnit = price }
                }
                AssetType.SILVER -> {
                    // Silver always uses SI=F — hide the symbol field and auto-fetch on dialog open
                    d.tilSymbol.isVisible  = false
                    d.tilQuantity.hint = "Quantity in grams"
                    // Show notes field so user can label entries (e.g. Physical, SGB)
                    d.tilNotes.isVisible = true
                    // Immediately fetch silver spot price (no user input needed)
                    triggerFetch(d, "SI=F", type) { price -> fetchedPricePerUnit = price }
                }
                AssetType.CRYPTO -> {
                    d.tilSymbol.hint   = "Pair (e.g. BTC-INR, ETH-INR)"
                    d.tilQuantity.hint = "Quantity of coins"
                    val ac = d.etSymbol as? AutoCompleteTextView
                    // Trigger on focus loss
                    ac?.setOnFocusChangeListener { _, hasFocus ->
                        if (!hasFocus) {
                            val sym = ac.text?.toString()?.trim() ?: return@setOnFocusChangeListener
                            if (sym.isNotBlank()) triggerFetch(d, sym, type) { price -> fetchedPricePerUnit = price }
                        }
                    }
                    // Also trigger on Enter / Search key
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

        // As user types quantity → recompute total value
        d.etQuantity.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (fetchedPricePerUnit > 0) {
                    val qty = s?.toString()?.toDoubleOrNull() ?: return
                    d.etValue.setText("%.2f".format(fetchedPricePerUnit * qty))
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

                viewModel.addAsset(
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

    /**
     * Populates [AutoCompleteTextView] with [symbols] using a case-insensitive CONTAINS filter,
     * so typing any part of a symbol shows matches. Also wires the IME Done/Search action and
     * focus-loss so the user can enter a completely custom symbol (not in the list) and still
     * trigger the price fetch.
     */
    private fun wireSymbolDropdown(
        d: DialogAddAssetBinding,
        symbols: List<String>,
        type: AssetType,
        onPriceFetched: (Double) -> Unit
    ) {
        val autoComplete = d.etSymbol as? AutoCompleteTextView ?: return

        // Custom ArrayAdapter with contains-based filter (case-insensitive)
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

        // Trigger fetch when user taps a suggestion from the dropdown
        autoComplete.setOnItemClickListener { _, _, _, _ ->
            val selected = autoComplete.text?.toString()?.trim()?.uppercase() ?: return@setOnItemClickListener
            if (selected.isNotBlank()) {
                val normalised = normaliseSymbol(selected, type)
                // Update field text to show the normalised symbol (e.g. TRENT.NS)
                autoComplete.setText(normalised)
                autoComplete.setSelection(normalised.length)
                d.tilSymbol.error = null
                triggerFetch(d, normalised, type, onPriceFetched)
            }
        }

        // Trigger fetch when user presses Done / Search on keyboard (for custom symbols)
        autoComplete.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO) {
                val typed = autoComplete.text?.toString()?.trim()?.uppercase() ?: ""
                if (typed.isNotBlank()) {
                    val normalised = normaliseSymbol(typed, type)
                    // Update field to show the resolved symbol
                    autoComplete.setText(normalised)
                    autoComplete.setSelection(normalised.length)
                    d.tilSymbol.error = null
                    triggerFetch(d, normalised, type, onPriceFetched)
                }
                true
            } else false
        }
    }

    /**
     * Fetches live price for [symbol].
     * - Shows the price card with a loading spinner immediately
     * - On success: displays prominent price, reveals quantity + value fields
     * - On error: shows error text in the card
     */
    private fun triggerFetch(
        d: DialogAddAssetBinding,
        symbol: String,
        type: AssetType,
        onPriceFetched: (Double) -> Unit
    ) {
        // Show the price card & loading state
        d.llPriceCard.isVisible    = true
        d.llPriceLoading.isVisible = true
        d.tvFetchStatus.isVisible  = false
        d.tvPriceError.isVisible   = false
        // Hide quantity + value + buy price until success
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

                    // Reveal the rest of the form
                    d.tilQuantity.isVisible = true
                    d.tilValue.isVisible    = true

                    // Show optional buy-price field with the correct unit hint
                    val buyHint = when (type) {
                        AssetType.GOLD, AssetType.SILVER -> "Avg Buy Price (₹/gram) — optional"
                        AssetType.CRYPTO                 -> "Avg Buy Price (₹/coin) — optional"
                        else                             -> "Avg Buy Price (₹/share) — optional"
                    }
                    d.tilBuyPrice.hint    = buyHint
                    d.tilBuyPrice.isVisible = true

                    // If quantity is already filled, recompute value
                    val qty = d.etQuantity.text?.toString()?.toDoubleOrNull()
                    if (qty != null && qty > 0) {
                        d.etValue.setText("%.2f".format(price * qty))
                    }
                }
                is Resource.Error -> {
                    d.tvPriceError.text    = "✗ ${result.message}"
                    d.tvPriceError.isVisible  = true
                    d.tvFetchStatus.isVisible = false
                    // Still show quantity/value/buy-price so user can enter manually
                    d.tilQuantity.isVisible  = true
                    d.tilBuyPrice.isVisible  = true
                    d.tilValue.isVisible     = true
                }
                else -> {}
            }
        }
    }

    /**
     * Ensures the symbol is in the correct format for Yahoo Finance.
     * - Indian stocks (STOCK_IN): appends ".NS" if no exchange suffix present.
     *   e.g. "TRENT" → "TRENT.NS", "TRENT.NS" stays as-is.
     * - Other types: returned as-is.
     */
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

    /**
     * Opens a pre-filled version of the add dialog for editing an existing asset.
     * Calls viewModel.updateAsset() on save (preserving the original ID and addedAt).
     */
    private fun showEditDialog(asset: NetWorthAssetEntity) {
        val type = asset.assetType
        val d = DialogAddAssetBinding.inflate(layoutInflater)
        val fetchable = type in isFetchable

        // Mirror the same visibility rules as showAddDialog
        // Symbol field: hide for Gold/Silver (fixed ticker) — only show for stocks/crypto
        val isMetalType = type == AssetType.GOLD || type == AssetType.SILVER
        d.tilSymbol.isVisible   = fetchable && !isMetalType
        d.llPriceCard.isVisible = false
        d.tilQuantity.isVisible = fetchable
        d.tilName.isVisible     = !fetchable
        d.llFetchRow.isVisible  = false
        d.tilValue.isVisible    = true
        d.tilBuyPrice.isVisible = asset.buyPrice > 0 || fetchable
        // Notes: shown for Gold/Silver so users can distinguish multiple entries
        d.tilNotes.isVisible    = isMetalType

        // Pre-fill existing values
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
        // Pre-fill notes for metal types
        if (isMetalType && asset.notes.isNotBlank()) d.etNotes.setText(asset.notes)

        // Set hints for buy-price field
        val buyHint = when (type) {
            AssetType.GOLD, AssetType.SILVER -> "Avg Buy Price (₹/gram) — optional"
            AssetType.CRYPTO                 -> "Avg Buy Price (₹/coin) — optional"
            else                             -> "Avg Buy Price (₹/share) — optional"
        }
        d.tilBuyPrice.hint = buyHint
        d.tilBuyPrice.isVisible = true

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
