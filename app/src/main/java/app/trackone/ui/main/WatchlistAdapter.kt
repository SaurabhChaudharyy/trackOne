package app.trackone.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.trackone.R
import app.trackone.data.database.StockEntity
import app.trackone.databinding.ItemStockBinding
import app.trackone.ui.detail.StockDetailActivity
import app.trackone.utils.FormatUtils
import app.trackone.utils.SymbolUtils

class WatchlistAdapter(
    private val onStockClick: (StockEntity, View) -> Unit,
    private val onRemoveClick: (StockEntity) -> Unit
) : RecyclerView.Adapter<WatchlistAdapter.StockViewHolder>() {

    var items: MutableList<StockEntity> = mutableListOf()

    /** Symbol of the row that launched (or is returning from) the stock detail screen. */
    var sharedSymbol: String? = null

    fun submitList(newItems: List<StockEntity>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(op: Int, np: Int) = items[op].symbol == newItems[np].symbol
            override fun areContentsTheSame(op: Int, np: Int) = items[op] == newItems[np]
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = items.size

    fun moveItem(fromPosition: Int, toPosition: Int) {
        val item = items.removeAt(fromPosition)
        items.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StockViewHolder {
        val binding = ItemStockBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StockViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StockViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class StockViewHolder(private val binding: ItemStockBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stock: StockEntity) {
            val ctx = binding.root.context
            // The name belongs to the symbol being transitioned, not to whichever view was tapped:
            // live price updates rebind this row while the detail screen is open, and losing the
            // name then makes the return transition wait out its timeout before it can start.
            // A symbol appears once in the list, so the name stays unique.
            binding.root.transitionName =
                if (stock.symbol == sharedSymbol) StockDetailActivity.SHARED_ELEMENT else null
            val isIndex = stock.symbol.startsWith("^")

            binding.tvSymbol.text = SymbolUtils.displaySymbol(stock.symbol)
            binding.tvCompanyName.text = stock.companyName
            binding.tvPrice.text = if (isIndex)
                FormatUtils.formatIndexPrice(stock.currentPrice, stock.currency)
            else
                FormatUtils.formatPrice(stock.currentPrice, stock.currency)
            binding.tvChange.text = FormatUtils.formatChange(stock.change)
            binding.tvChangePercent.text = FormatUtils.formatChangePercent(stock.changePercent)

            // Pill's fill is constant across themes, so the ink INSIDE it stays @color/primary.
            // tvChange (the absolute change) sits outside the pill on the theme background, so it
            // keeps its layout colour — forcing @color/primary on it made it black-on-black (invisible)
            // in dark mode while showing in light mode.
            val primaryColor = ContextCompat.getColor(ctx, R.color.primary)

            binding.tvChangePercent.setTextColor(primaryColor)
            binding.ivTrend.setImageResource(
                if (stock.isPositive) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down
            )
            binding.ivTrend.setColorFilter(primaryColor)

            binding.changeContainer.setBackgroundResource(
                if (stock.isPositive) R.drawable.bg_gain_pill else R.drawable.bg_loss_pill
            )

            if (stock.isStale) {
                binding.tvLastUpdated.text = "• ${FormatUtils.formatLastUpdated(stock.lastUpdated)}"
                binding.tvLastUpdated.visibility = android.view.View.VISIBLE
            } else {
                binding.tvLastUpdated.visibility = android.view.View.GONE
            }

            binding.root.setOnClickListener {
                sharedSymbol = stock.symbol
                onStockClick(stock, binding.root)
            }
            binding.btnRemove.setOnClickListener { onRemoveClick(stock) }
        }
    }
}
