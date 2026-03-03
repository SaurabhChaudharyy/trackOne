package com.saurabh.financewidget.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.saurabh.financewidget.R
import com.saurabh.financewidget.data.database.StockEntity
import com.saurabh.financewidget.databinding.ItemStockBinding
import com.saurabh.financewidget.utils.FormatUtils

class WatchlistAdapter(
    private val onStockClick: (StockEntity) -> Unit,
    private val onRemoveClick: (StockEntity) -> Unit
) : ListAdapter<StockEntity, WatchlistAdapter.StockViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<StockEntity>() {
            override fun areItemsTheSame(old: StockEntity, new: StockEntity) = old.symbol == new.symbol
            override fun areContentsTheSame(old: StockEntity, new: StockEntity) = old == new
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StockViewHolder {
        val binding = ItemStockBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StockViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StockViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class StockViewHolder(private val binding: ItemStockBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stock: StockEntity) {
            val ctx = binding.root.context

            binding.tvSymbol.text = stock.symbol
            binding.tvCompanyName.text = stock.companyName
            binding.tvPrice.text = FormatUtils.formatPrice(stock.currentPrice, stock.currency)
            binding.tvChange.text = FormatUtils.formatChange(stock.change)
            binding.tvChangePercent.text = FormatUtils.formatChangePercent(stock.changePercent)

            val gainColor = ContextCompat.getColor(ctx, R.color.gain_green)
            val lossColor = ContextCompat.getColor(ctx, R.color.loss_red)
            val changeColor = if (stock.isPositive) gainColor else lossColor

            binding.tvChange.setTextColor(changeColor)
            binding.tvChangePercent.setTextColor(changeColor)
            binding.ivTrend.setImageResource(
                if (stock.isPositive) R.drawable.ic_trending_up else R.drawable.ic_trending_down
            )
            binding.ivTrend.setColorFilter(changeColor)

            binding.changeContainer.setBackgroundResource(
                if (stock.isPositive) R.drawable.bg_gain_pill else R.drawable.bg_loss_pill
            )

            if (stock.isStale) {
                binding.tvLastUpdated.text = "• ${FormatUtils.formatLastUpdated(stock.lastUpdated)}"
                binding.tvLastUpdated.visibility = android.view.View.VISIBLE
            } else {
                binding.tvLastUpdated.visibility = android.view.View.GONE
            }

            binding.root.setOnClickListener { onStockClick(stock) }
            binding.btnRemove.setOnClickListener { onRemoveClick(stock) }
        }
    }
}
