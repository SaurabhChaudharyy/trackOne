package com.saurabh.financewidget.ui.networth

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.saurabh.financewidget.data.database.AssetType
import com.saurabh.financewidget.data.database.NetWorthAssetEntity
import com.saurabh.financewidget.databinding.ItemNetworthAssetBinding
import com.saurabh.financewidget.R
import java.text.NumberFormat
import java.util.Locale

class NetWorthAssetAdapter(
    private val onDeleteClick: (NetWorthAssetEntity) -> Unit,
    private val onEditClick: (NetWorthAssetEntity) -> Unit,
    private val onLongPress: (NetWorthAssetEntity) -> Unit = {},
    private val onSelectionChanged: (count: Int) -> Unit = {}
) : ListAdapter<NetWorthAssetEntity, NetWorthAssetAdapter.ViewHolder>(DIFF) {

    private val inrFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    private val usdFormat = NumberFormat.getCurrencyInstance(Locale.US)

    private val selectedIds = mutableSetOf<Long>()
    var isSelectionMode = false
        private set

    // ── Selection mode API ────────────────────────────────────────────────────

    /** Enter selection mode, pre-selecting the item that was long-pressed. */
    fun enterSelectionMode(initialId: Long) {
        isSelectionMode = true
        selectedIds.clear()
        selectedIds.add(initialId)
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    /** Exit selection mode and clear all selections. */
    fun exitSelectionMode() {
        isSelectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    /** Select every item currently in the list. */
    fun selectAll() {
        selectedIds.addAll(currentList.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    /** Deselect all items (stays in selection mode). */
    fun deselectAll() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun getSelectedIds(): Set<Long> = selectedIds.toSet()
    fun getSelectedCount(): Int = selectedIds.size
    fun areAllSelected(): Boolean =
        currentList.isNotEmpty() && selectedIds.size == currentList.size

    // ── ViewHolder ────────────────────────────────────────────────────────────

    inner class ViewHolder(private val binding: ItemNetworthAssetBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(asset: NetWorthAssetEntity) {
            val fmt = if (asset.currency == "USD") usdFormat else inrFormat

            binding.tvAssetName.text = asset.name.removePrefix("^")
            binding.tvAssetValue.text = fmt.format(asset.currentValue)

            val subtitle = buildSubtitle(asset, fmt)
            if (subtitle.isNotBlank()) {
                binding.tvAssetNotes.text = subtitle
                binding.tvAssetNotes.visibility = View.VISIBLE
            } else {
                binding.tvAssetNotes.visibility = View.GONE
            }

            if (asset.buyPrice > 0 && asset.quantity > 0) {
                val invested = asset.buyPrice * asset.quantity
                val gain     = asset.currentValue - invested
                val gainPct  = (gain / invested) * 100.0
                val isGain   = gain >= 0
                val arrow    = if (isGain) "▲" else "▼"
                val colour   = if (isGain) R.color.gain_green else R.color.loss_red
                val ctx      = binding.root.context

                binding.tvAssetPl.text = "%s %s (%+.2f%%)".format(
                    arrow, fmt.format(gain), gainPct
                )
                binding.tvAssetPl.setTextColor(ctx.getColor(R.color.text_primary))
                val bgRes = if (isGain) R.drawable.bg_gain_pill else R.drawable.bg_loss_pill
                binding.tvAssetPl.background = ctx.getDrawable(bgRes)
                binding.tvAssetPl.visibility = View.VISIBLE

                binding.tvAssetInvested.text = "inv ${fmt.format(invested)}"
                binding.tvAssetInvested.visibility = View.VISIBLE
            } else {
                binding.tvAssetPl.visibility       = View.GONE
                binding.tvAssetInvested.visibility = View.GONE
            }

            if (asset.notes.isNotBlank()) {
                binding.tvAssetLabel.text = asset.notes
                binding.tvAssetLabel.visibility = View.VISIBLE
            } else {
                binding.tvAssetLabel.visibility = View.GONE
            }

            // ── Mode-specific UI ──────────────────────────────────────────────
            if (isSelectionMode) {
                val checked = selectedIds.contains(asset.id)
                binding.checkboxSelect.isVisible = true
                binding.checkboxSelect.isChecked = checked

                binding.root.setOnLongClickListener(null)
                binding.root.setOnClickListener { toggleSelection(asset) }
            } else {
                binding.checkboxSelect.isVisible = false

                binding.root.setOnClickListener(null)
                binding.root.setOnLongClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(view.context)
                    val bsView = LayoutInflater.from(view.context).inflate(R.layout.bottom_sheet_asset_actions, null)
                    
                    bsView.findViewById<android.widget.TextView>(R.id.tv_bs_title).text = asset.name.removePrefix("^")
                    
                    bsView.findViewById<View>(R.id.action_edit).setOnClickListener {
                        bottomSheet.dismiss()
                        onEditClick(asset)
                    }
                    bsView.findViewById<View>(R.id.action_remove).setOnClickListener {
                        bottomSheet.dismiss()
                        onDeleteClick(asset)
                    }
                    bsView.findViewById<View>(R.id.action_select).setOnClickListener {
                        bottomSheet.dismiss()
                        onLongPress(asset)
                    }
                    
                    bottomSheet.setContentView(bsView)
                    bottomSheet.show()
                    true
                }
            }
        }

        private fun toggleSelection(asset: NetWorthAssetEntity) {
            if (selectedIds.contains(asset.id)) selectedIds.remove(asset.id)
            else selectedIds.add(asset.id)
            val idx = currentList.indexOf(asset)
            if (idx >= 0) notifyItemChanged(idx)
            onSelectionChanged(selectedIds.size)
        }

        private fun buildSubtitle(asset: NetWorthAssetEntity, fmt: NumberFormat): String {
            val isFetchable = asset.assetType in listOf(
                AssetType.STOCK_IN, AssetType.STOCK_US, AssetType.CRYPTO,
                AssetType.GOLD, AssetType.SILVER
            )
            return when {
                isFetchable && asset.quantity > 0 -> {
                    val pricePerUnit =
                        if (asset.quantity != 0.0) asset.currentValue / asset.quantity else 0.0
                    val qtyStr = if (asset.quantity % 1.0 == 0.0)
                        asset.quantity.toInt().toString()
                    else
                        "%.4f".format(asset.quantity)
                    val unitLabel = when (asset.assetType) {
                        AssetType.GOLD, AssetType.SILVER -> "g"
                        else -> " units"
                    }
                    "$qtyStr$unitLabel \u00b7 ${fmt.format(pricePerUnit)}/unit"
                }
                asset.notes.isNotBlank() -> asset.notes
                else -> ""
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNetworthAssetBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NetWorthAssetEntity>() {
            override fun areItemsTheSame(a: NetWorthAssetEntity, b: NetWorthAssetEntity) =
                a.id == b.id
            override fun areContentsTheSame(a: NetWorthAssetEntity, b: NetWorthAssetEntity) =
                a == b
        }
    }
}
