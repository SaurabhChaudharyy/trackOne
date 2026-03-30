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
    private val usdFormat = NumberFormat.getCurrencyInstance(Locale.US)

    /** Returns the right formatter for the asset's native currency. */
    private fun fmt(currency: String) = if (currency == "USD") usdFormat else inrFormat

    inner class ViewHolder(private val binding: ItemNetworthAssetBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(asset: NetWorthAssetEntity) {
            val fmt = fmt(asset.currency)

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
                binding.tvAssetPl.setTextColor(ctx.getColor(colour))
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

            binding.btnEditAsset.setOnClickListener {
                binding.btnEditAsset.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onEditClick(asset)
            }

            binding.btnDeleteAsset.setOnClickListener {
                binding.btnDeleteAsset.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                onDeleteClick(asset)
            }
        }

        private fun buildSubtitle(asset: NetWorthAssetEntity, fmt: NumberFormat): String {
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
            override fun areItemsTheSame(a: NetWorthAssetEntity, b: NetWorthAssetEntity) = a.id == b.id
            override fun areContentsTheSame(a: NetWorthAssetEntity, b: NetWorthAssetEntity) = a == b
        }
    }
}
