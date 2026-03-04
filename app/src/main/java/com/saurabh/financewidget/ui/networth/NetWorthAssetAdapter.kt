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
import java.text.NumberFormat
import java.util.Locale

class NetWorthAssetAdapter(
    private val onDeleteClick: (NetWorthAssetEntity) -> Unit
) : ListAdapter<NetWorthAssetEntity, NetWorthAssetAdapter.ViewHolder>(DIFF) {

    private val inrFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    inner class ViewHolder(private val binding: ItemNetworthAssetBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(asset: NetWorthAssetEntity) {
            binding.tvAssetName.text = asset.name
            binding.tvAssetValue.text = inrFormat.format(asset.currentValue)

            // Build subtitle: quantity info for fetchable types, or custom notes
            val subtitle = buildSubtitle(asset)
            if (subtitle.isNotBlank()) {
                binding.tvAssetNotes.text = subtitle
                binding.tvAssetNotes.visibility = View.VISIBLE
            } else {
                binding.tvAssetNotes.visibility = View.GONE
            }

            binding.btnDeleteAsset.setOnClickListener { onDeleteClick(asset) }
        }

        private fun buildSubtitle(asset: NetWorthAssetEntity): String {
            val isFetchable = asset.assetType in listOf(
                AssetType.STOCK_IN, AssetType.STOCK_US, AssetType.CRYPTO, AssetType.GOLD
            )
            return when {
                isFetchable && asset.quantity > 0 -> {
                    val pricePerUnit = if (asset.quantity != 0.0) asset.currentValue / asset.quantity else 0.0
                    val qtyStr = if (asset.quantity % 1.0 == 0.0) asset.quantity.toInt().toString()
                                 else "%.4f".format(asset.quantity)
                    val unitLabel = if (asset.assetType == AssetType.GOLD) "g" else " units"
                    "$qtyStr$unitLabel · ${inrFormat.format(pricePerUnit)}/unit"
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
