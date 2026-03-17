package com.saurabh.financewidget.ui.networth

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private val onEditClick: (NetWorthAssetEntity) -> Unit
) : ListAdapter<NetWorthAssetEntity, NetWorthAssetAdapter.ViewHolder>(DIFF) {

    private val inrFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    inner class ViewHolder(private val binding: ItemNetworthAssetBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(asset: NetWorthAssetEntity) {
            binding.tvAssetName.text = asset.name
            binding.tvAssetValue.text = inrFormat.format(asset.currentValue)

            val subtitle = buildSubtitle(asset)
            if (subtitle.isNotBlank()) {
                binding.tvAssetNotes.text = subtitle
                binding.tvAssetNotes.visibility = View.VISIBLE
            } else {
                binding.tvAssetNotes.visibility = View.GONE
            }

            // P&L — only shown when a buy price was recorded
            if (asset.buyPrice > 0 && asset.quantity > 0) {
                val invested    = asset.buyPrice * asset.quantity
                val gain        = asset.currentValue - invested
                val gainPct     = (gain / invested) * 100.0
                val isGain      = gain >= 0
                val arrow       = if (isGain) "▲" else "▼"
                val colour      = if (isGain) R.color.gain_green else R.color.loss_red
                val ctx         = binding.root.context

                // Left: "▲ ₹3,200 (+2.74%)"
                binding.tvAssetPl.text = "%s %s (%+.2f%%)".format(
                    arrow, inrFormat.format(gain), gainPct
                )
                binding.tvAssetPl.setTextColor(ctx.getColor(colour))
                binding.tvAssetPl.visibility = View.VISIBLE

                // Right: "inv ₹1,16,800"
                binding.tvAssetInvested.text = "inv ${inrFormat.format(invested)}"
                binding.tvAssetInvested.visibility = View.VISIBLE
            } else {
                binding.tvAssetPl.visibility      = View.GONE
                binding.tvAssetInvested.visibility = View.GONE
            }

            // Label / note line (Gold, Silver, etc.) — own dedicated row below subtitle
            if (asset.notes.isNotBlank()) {
                binding.tvAssetLabel.text = asset.notes
                binding.tvAssetLabel.visibility = View.VISIBLE
            } else {
                binding.tvAssetLabel.visibility = View.GONE
            }

            binding.btnEditAsset.setOnClickListener {
                binding.btnEditAsset.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onEditClick(asset)
            }

            binding.btnDeleteAsset.setOnClickListener {
                binding.btnDeleteAsset.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                onDeleteClick(asset)
            }
        }

        private fun buildSubtitle(asset: NetWorthAssetEntity): String {
            val isFetchable = asset.assetType in listOf(
                AssetType.STOCK_IN, AssetType.STOCK_US, AssetType.CRYPTO, AssetType.GOLD, AssetType.SILVER
            )
            return when {
                isFetchable && asset.quantity > 0 -> {
                    val pricePerUnit = if (asset.quantity != 0.0) asset.currentValue / asset.quantity else 0.0
                    val qtyStr = if (asset.quantity % 1.0 == 0.0) asset.quantity.toInt().toString()
                                 else "%.4f".format(asset.quantity)
                    val unitLabel = when (asset.assetType) {
                        AssetType.GOLD, AssetType.SILVER -> "g"
                        else -> " units"
                    }
                    // Quantity + price per unit only — notes shown in tv_asset_label below
                    "$qtyStr$unitLabel \u00b7 ${inrFormat.format(pricePerUnit)}/unit"
                }
                // For manual types (MF, Cash, Bank) show notes in subtitle if no label view
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
            override fun areItemsTheSame(a: NetWorthAssetEntity, b: NetWorthAssetEntity) = a.id == b.id
            override fun areContentsTheSame(a: NetWorthAssetEntity, b: NetWorthAssetEntity) = a == b
        }
    }
}
