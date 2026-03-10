package com.saurabh.financewidget.ui.config

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.saurabh.financewidget.R
import com.saurabh.financewidget.data.model.YahooSearchResult
import com.saurabh.financewidget.databinding.ItemSearchResultBinding

class SearchResultAdapter(
    private val onSelectionChanged: (selected: Set<YahooSearchResult>) -> Unit
) : ListAdapter<YahooSearchResult, SearchResultAdapter.SearchViewHolder>(DIFF_CALLBACK) {

    private val selectedItems = mutableSetOf<YahooSearchResult>()

    fun getSelectedItems(): Set<YahooSearchResult> = selectedItems.toSet()

    fun clearSelection() {
        selectedItems.clear()
        notifyDataSetChanged()
        onSelectionChanged(selectedItems)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<YahooSearchResult>() {
            override fun areItemsTheSame(old: YahooSearchResult, new: YahooSearchResult) =
                old.symbol == new.symbol
            override fun areContentsTheSame(old: YahooSearchResult, new: YahooSearchResult) =
                old == new
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchViewHolder {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SearchViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SearchViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SearchViewHolder(private val binding: ItemSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(result: YahooSearchResult) {
            val ctx = binding.root.context
            binding.tvSearchSymbol.text = result.symbol
            binding.tvSearchName.text = result.displayName
            binding.tvSearchType.text = buildString {
                if (result.exchange.isNotEmpty()) append(result.exchange)
                if (result.typeDisplay.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(result.typeDisplay)
                }
            }

            val isSelected = result in selectedItems
            applySelectionState(isSelected, result, ctx)

            binding.btnAdd.setOnClickListener { toggleSelection(result) }
            binding.root.setOnClickListener { toggleSelection(result) }
        }

        private fun toggleSelection(result: YahooSearchResult) {
            binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            if (result in selectedItems) selectedItems.remove(result)
            else selectedItems.add(result)
            onSelectionChanged(selectedItems.toSet())
            notifyItemChanged(currentList.indexOf(result))
        }

        private fun applySelectionState(
            isSelected: Boolean,
            @Suppress("UNUSED_PARAMETER") result: YahooSearchResult,
            ctx: android.content.Context
        ) {
            if (isSelected) {
                binding.ivSelected.visibility = View.VISIBLE
                binding.btnAdd.text = "Added"
                binding.btnAdd.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.gain_green_bg))
                binding.btnAdd.setTextColor(ContextCompat.getColor(ctx, R.color.gain_green))
                binding.root.alpha = 1f
            } else {
                binding.ivSelected.visibility = View.GONE
                binding.btnAdd.text = "Add"
                binding.btnAdd.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.text_primary))
                binding.btnAdd.setTextColor(ContextCompat.getColor(ctx, R.color.on_primary))
                binding.root.alpha = 1f
            }
        }
    }
}
