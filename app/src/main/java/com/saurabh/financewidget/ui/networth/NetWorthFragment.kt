package com.saurabh.financewidget.ui.networth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
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

    // Map type → adapter
    private val adapters = mutableMapOf<AssetType, NetWorthAssetAdapter>()

    // Track collapsed state per section (default: all expanded)
    private val sectionExpanded = mutableMapOf(
        AssetType.STOCK_IN to true,
        AssetType.STOCK_US to true,
        AssetType.MF       to true,
        AssetType.GOLD     to true,
        AssetType.CRYPTO   to true,
        AssetType.CASH     to true,
        AssetType.BANK     to true
    )

    // Whether a type uses auto-fetch (symbol + qty) vs manual (label + amount)
    private val isFetchable = setOf(
        AssetType.STOCK_IN, AssetType.STOCK_US, AssetType.CRYPTO, AssetType.GOLD
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

    // ── RecyclerViews ────────────────────────────────────────────────────────

    private fun setupRecyclerViews() {
        val map = mapOf(
            AssetType.STOCK_IN to binding.rvStockIn,
            AssetType.STOCK_US to binding.rvStockUs,
            AssetType.MF       to binding.rvMf,
            AssetType.GOLD     to binding.rvGold,
            AssetType.CRYPTO   to binding.rvCrypto,
            AssetType.CASH     to binding.rvCash,
            AssetType.BANK     to binding.rvBank
        )
        for ((type, rv) in map) {
            val adapter = NetWorthAssetAdapter { asset -> confirmDelete(asset) }
            adapters[type] = adapter
            rv.apply {
                layoutManager = LinearLayoutManager(requireContext())
                this.adapter = adapter
                isNestedScrollingEnabled = false
            }
        }
    }

    // ── Section collapse / expand ───────────────────────────────────────────

    private fun setupHeaders() {
        fun wireHeader(header: View, rv: RecyclerView, chevron: View, type: AssetType) {
            header.setOnClickListener {
                val nowExpanded = !(sectionExpanded[type] ?: true)
                sectionExpanded[type] = nowExpanded
                rv.isVisible = nowExpanded
                chevron.animate()
                    .rotation(if (nowExpanded) 0f else -90f)
                    .setDuration(200)
                    .start()
            }
        }

        wireHeader(binding.headerStockIn, binding.rvStockIn, binding.chevronStockIn, AssetType.STOCK_IN)
        wireHeader(binding.headerStockUs, binding.rvStockUs, binding.chevronStockUs, AssetType.STOCK_US)
        wireHeader(binding.headerMf,      binding.rvMf,      binding.chevronMf,      AssetType.MF)
        wireHeader(binding.headerGold,    binding.rvGold,    binding.chevronGold,    AssetType.GOLD)
        wireHeader(binding.headerCrypto,  binding.rvCrypto,  binding.chevronCrypto,  AssetType.CRYPTO)
        wireHeader(binding.headerCash,    binding.rvCash,    binding.chevronCash,    AssetType.CASH)
        wireHeader(binding.headerBank,    binding.rvBank,    binding.chevronBank,    AssetType.BANK)
    }

    // ── Add buttons ─────────────────────────────────────────────────────────

    private fun setupAddButtons() {
        binding.btnAddStockIn.setOnClickListener { showAddDialog(AssetType.STOCK_IN, "Add Indian Stock") }
        binding.btnAddStockUs.setOnClickListener { showAddDialog(AssetType.STOCK_US, "Add US Stock") }
        binding.btnAddMf.setOnClickListener      { showAddDialog(AssetType.MF,       "Add Mutual Fund") }
        binding.btnAddGold.setOnClickListener    { showAddDialog(AssetType.GOLD,     "Add Gold") }
        binding.btnAddCrypto.setOnClickListener  { showAddDialog(AssetType.CRYPTO,   "Add Crypto") }
        binding.btnAddCash.setOnClickListener    { showAddDialog(AssetType.CASH,     "Add Cash on Hand") }
        binding.btnAddBank.setOnClickListener    { showAddDialog(AssetType.BANK,     "Add Bank Balance") }
    }

    // ── ViewModel ───────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.totalNetWorth.observe(viewLifecycleOwner) { total ->
            binding.tvTotalNetworth.text = inrFormat.format(total ?: 0.0)
        }

        viewModel.allAssets.observe(viewLifecycleOwner) { all ->
            val grouped = all.groupBy { it.assetType }
            for ((type, adapter) in adapters) {
                adapter.submitList(grouped[type] ?: emptyList())
            }
        }

        viewModel.assetSummary.observe(viewLifecycleOwner) { summary ->
            binding.tvTotalStockIn.text = inrFormat.format(summary[AssetType.STOCK_IN] ?: 0.0)
            binding.tvTotalStockUs.text = inrFormat.format(summary[AssetType.STOCK_US] ?: 0.0)
            binding.tvTotalMf.text      = inrFormat.format(summary[AssetType.MF]       ?: 0.0)
            binding.tvTotalGold.text    = inrFormat.format(summary[AssetType.GOLD]     ?: 0.0)
            binding.tvTotalCrypto.text  = inrFormat.format(summary[AssetType.CRYPTO]   ?: 0.0)
            binding.tvTotalCash.text    = inrFormat.format(summary[AssetType.CASH]     ?: 0.0)
            binding.tvTotalBank.text    = inrFormat.format(summary[AssetType.BANK]     ?: 0.0)
        }
    }

    // ── Smart Add dialog ────────────────────────────────────────────────────

    private fun showAddDialog(type: AssetType, title: String) {
        val d = DialogAddAssetBinding.inflate(layoutInflater)
        val fetchable = type in isFetchable

        // Show correct mode
        d.tilSymbol.isVisible   = fetchable
        d.tilQuantity.isVisible = fetchable
        d.llFetchRow.isVisible  = fetchable
        d.tilName.isVisible     = !fetchable

        // Hint depends on type
        when (type) {
            AssetType.GOLD     -> {
                d.tilSymbol.hint   = "Symbol — leave blank for spot price"
                d.tilQuantity.hint = "Quantity in grams"
            }
            AssetType.STOCK_IN -> {
                d.tilSymbol.hint   = "NSE symbol  (e.g. RELIANCE.NS)"
                d.tilQuantity.hint = "Number of shares"
            }
            AssetType.STOCK_US -> {
                d.tilSymbol.hint   = "US ticker  (e.g. AAPL, NVDA)"
                d.tilQuantity.hint = "Number of shares"
            }
            AssetType.CRYPTO   -> {
                d.tilSymbol.hint   = "Crypto pair  (e.g. BTC-INR, ETH-INR)"
                d.tilQuantity.hint = "Quantity of coins"
            }
            else -> {}
        }

        // Track fetched price per unit
        var fetchedPricePerUnit = 0.0

        // Fetch button
        d.btnFetchPrice.setOnClickListener {
            val symbolInput = d.etSymbol.text?.toString()?.trim()
                ?: if (type == AssetType.GOLD) "GC=F" else ""
            val sym = symbolInput.ifBlank {
                if (type == AssetType.GOLD) "GC=F" else return@setOnClickListener
            }

            d.tvFetchStatus.text = "Fetching…"
            d.btnFetchPrice.isEnabled = false

            lifecycleScope.launch {
                val result = viewModel.fetchLivePrice(sym, type)
                d.btnFetchPrice.isEnabled = true
                when (result) {
                    is Resource.Success -> {
                        fetchedPricePerUnit = result.data
                        val qty = d.etQuantity.text?.toString()?.toDoubleOrNull() ?: 1.0
                        val totalValue = fetchedPricePerUnit * qty
                        d.etValue.setText("%.2f".format(totalValue))
                        val unitLabel = if (type == AssetType.GOLD) "/gram" else "/unit"
                        d.tvFetchStatus.text = "✓ ${inrFormat.format(fetchedPricePerUnit)}$unitLabel"
                        d.tvFetchStatus.setTextColor(requireContext().getColor(R.color.gain_green))
                    }
                    is Resource.Error -> {
                        d.tvFetchStatus.text = "✗ ${result.message}"
                        d.tvFetchStatus.setTextColor(requireContext().getColor(R.color.loss_red))
                    }
                    else -> {}
                }
            }
        }

        // Auto-update total when qty changes (if we already have price)
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
            .setPositiveButton("Add", null) // set below to prevent auto-dismiss on validation failure
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = d.etValue.text?.toString()?.toDoubleOrNull()

                val name = if (fetchable) {
                    d.etSymbol.text?.toString()?.trim()?.uppercase()
                        ?: if (type == AssetType.GOLD) "GOLD" else ""
                } else {
                    d.etName.text?.toString()?.trim() ?: ""
                }

                val qty = if (fetchable) d.etQuantity.text?.toString()?.toDoubleOrNull() ?: 1.0 else 1.0
                val notes = d.etNotes.text?.toString()?.trim() ?: ""

                if (name.isBlank()) {
                    val tilToHighlight = if (fetchable) d.tilSymbol else d.tilName
                    tilToHighlight.error = "Required"
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
                        currentValue = value,
                        notes = notes
                    )
                )

                // Auto-expand section if collapsed
                if (sectionExpanded[type] == false) {
                    sectionExpanded[type] = true
                    rvForType(type)?.isVisible = true
                    chevronForType(type)?.rotation = 0f
                }

                dialog.dismiss()
            }
        }

        dialog.show()
    }

    // ── Delete confirm ───────────────────────────────────────────────────────

    private fun confirmDelete(asset: NetWorthAssetEntity) {
        AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle("Remove ${asset.name}?")
            .setMessage("This will be removed from your net worth.")
            .setPositiveButton("Remove") { _, _ -> viewModel.deleteAsset(asset) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun rvForType(type: AssetType): RecyclerView? = when (type) {
        AssetType.STOCK_IN -> binding.rvStockIn
        AssetType.STOCK_US -> binding.rvStockUs
        AssetType.MF       -> binding.rvMf
        AssetType.GOLD     -> binding.rvGold
        AssetType.CRYPTO   -> binding.rvCrypto
        AssetType.CASH     -> binding.rvCash
        AssetType.BANK     -> binding.rvBank
    }

    private fun chevronForType(type: AssetType): View? = when (type) {
        AssetType.STOCK_IN -> binding.chevronStockIn
        AssetType.STOCK_US -> binding.chevronStockUs
        AssetType.MF       -> binding.chevronMf
        AssetType.GOLD     -> binding.chevronGold
        AssetType.CRYPTO   -> binding.chevronCrypto
        AssetType.CASH     -> binding.chevronCash
        AssetType.BANK     -> binding.chevronBank
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
