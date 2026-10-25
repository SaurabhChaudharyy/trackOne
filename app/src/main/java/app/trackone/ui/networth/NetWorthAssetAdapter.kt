package app.trackone.ui.networth

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.databinding.ItemNetworthAssetBinding
import app.trackone.ui.util.MoneyColor
import app.trackone.R
import app.trackone.utils.HoldingIssue
import app.trackone.utils.HoldingQuality
import app.trackone.utils.HoldingRowStats
import app.trackone.utils.PortfolioGainLoss
import java.text.NumberFormat
import java.util.Locale

class NetWorthAssetAdapter(
    private val onDeleteClick: (NetWorthAssetEntity) -> Unit,
    private val onEditClick: (NetWorthAssetEntity) -> Unit,
    private val onLongPress: (NetWorthAssetEntity) -> Unit = {},
    private val onSelectionChanged: (count: Int) -> Unit = {},
    /** The amber warning was tapped: open the fix for [HoldingIssue] (only issues that can be acted on are tappable). */
    private val onWarningClick: (NetWorthAssetEntity, HoldingIssue) -> Unit = { _, _ -> }
) : ListAdapter<NetWorthAssetEntity, NetWorthAssetAdapter.ViewHolder>(DIFF) {

    private val inrFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    private val usdFormat = NumberFormat.getCurrencyInstance(Locale.US)

    // The qty · avg · ltp line is bare numbers: the currency already sits on the row's value.
    private val inrPlain = PlainPriceFormat(Locale("en", "IN"))
    private val usdPlain = PlainPriceFormat(Locale.US)

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
            val ctx = binding.root.context

            binding.tvAssetName.text = asset.name.removePrefix("^")
            binding.tvAssetValue.text = fmt.format(asset.currentValue)

            val subtitle = buildSubtitle(asset)
            if (subtitle.isNotBlank()) {
                binding.tvAssetNotes.text = subtitle
                binding.tvAssetNotes.visibility = View.VISIBLE
            } else {
                binding.tvAssetNotes.visibility = View.GONE
            }

            // Surface untrustworthy numbers instead of presenting them as fact. A warning is one of
            // the neon's jobs: ink on a highlighter stroke that follows each wrapped line.
            val issue = HoldingQuality.primaryIssue(asset)
            binding.tvAssetWarning.text =
                if (issue != null) markerHighlight("⚠ ${issue.message}", ctx.getColor(R.color.marker)) else ""
            binding.tvAssetWarning.visibility = if (issue != null) View.VISIBLE else View.GONE
            binding.tvAssetWarning.isClickable = issue?.tappable == true
            binding.tvAssetWarning.setOnClickListener(
                if (issue != null && issue.tappable) View.OnClickListener { onWarningClick(asset, issue) } else null
            )

            // Today's move rides beside the name as an arrow and a percent: the arrow keeps it
            // readable without colour, and the rupee figure is left to the P&L line.
            val today = HoldingRowStats.todayChange(asset)
            if (today != null) {
                val up = today.absChange >= 0
                binding.tvAssetToday.text = "%s%.2f%%".format(if (up) "\u25B4" else "\u25BE", kotlin.math.abs(today.pctChange))
                binding.tvAssetToday.setTextColor(ctx.getColor(MoneyColor.forChange(today.absChange)))
                binding.tvAssetToday.contentDescription =
                    "%s %.2f percent today".format(if (up) "Up" else "Down", kotlin.math.abs(today.pctChange))
                binding.tvAssetToday.isVisible = true
            } else {
                binding.tvAssetToday.isVisible = false
            }

            if (PortfolioGainLoss.hasCostBasis(asset) && asset.buyPrice > 0 && asset.quantity > 0) {
                val perAsset = PortfolioGainLoss.computePerAsset(asset)
                val gain     = perAsset.absChange
                val gainPct  = perAsset.pctChange
                val isGain   = gain >= 0
                val arrow    = if (isGain) "↗" else "↘"

                binding.tvAssetPl.text = "%s %s (%+.2f%%)".format(
                    arrow, fmt.format(gain), gainPct
                )
                // The move is coloured text; money direction never gets a filled pill.
                binding.tvAssetPl.setTextColor(ctx.getColor(MoneyColor.forChange(gain)))
                binding.tvAssetPl.background = null
                binding.tvAssetPl.visibility = View.VISIBLE
            } else {
                binding.tvAssetPl.visibility = View.GONE
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

        /** [text] over a highlighter stroke; thin spaces keep the stroke off the first and last glyph. */
        private fun markerHighlight(text: String, color: Int): CharSequence =
            SpannableString("\u2009$text\u2009").apply {
                setSpan(BackgroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

        private fun buildSubtitle(asset: NetWorthAssetEntity): String {
            val plain = if (asset.currency == "USD") usdPlain else inrPlain
            return HoldingRowStats.detailLine(asset) { plain.format(it) }
                ?: asset.notes.takeIf { it.isNotBlank() }
                ?: ""
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
        /** Two decimals on a small price, whole units once it is large enough that decimals only crowd the row. */
        private class PlainPriceFormat(locale: Locale) {
            private val withDecimals = digits(locale, 2)
            private val whole = digits(locale, 0)

            fun format(price: Double): String =
                (if (HoldingRowStats.showsWholeUnits(price)) whole else withDecimals).format(price)

            private fun digits(locale: Locale, count: Int): NumberFormat =
                NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = count; maximumFractionDigits = count }
        }

        private val DIFF = object : DiffUtil.ItemCallback<NetWorthAssetEntity>() {
            override fun areItemsTheSame(a: NetWorthAssetEntity, b: NetWorthAssetEntity) =
                a.id == b.id
            override fun areContentsTheSame(a: NetWorthAssetEntity, b: NetWorthAssetEntity) =
                a == b
        }
    }
}
