package app.trackone.utils

import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.database.hasLivePrice

/** The extra figures a holdings row shows beyond its value: what it cost, what a unit is worth, how today went. */
object HoldingRowStats {

    /**
     * How the holding moved since yesterday's close, or null when that can't be known: a holding
     * priced by hand has no market move, and a quote without a previous close says nothing about
     * the day (null, not "unchanged").
     */
    fun todayChange(asset: NetWorthAssetEntity): GainLoss? {
        if (!asset.assetType.hasLivePrice) return null
        if (asset.previousClosePrice <= 0.0 || asset.quantity <= 0.0) return null
        return PortfolioGainLoss.between(asset.previousClosePrice * asset.quantity, asset.currentValue)
    }

    /** The per-unit price paid, or null when there is no honest cost basis (cash, or no buy price). */
    fun averagePrice(asset: NetWorthAssetEntity): Double? =
        asset.buyPrice.takeIf { PortfolioGainLoss.hasCostBasis(asset) && it > 0.0 }

    /**
     * The row's muted line, "601 · avg 273.67 · ltp 261.20": what is held, what it cost per unit
     * (when that is known) and what a unit is worth now. Bare numbers, formatted by [number], since
     * the row's value already carries the currency. Null for a holding with no market price to show.
     */
    fun detailLine(asset: NetWorthAssetEntity, number: (Double) -> String): String? {
        if (!asset.assetType.hasLivePrice || asset.quantity <= 0.0) return null
        val metal = asset.assetType == AssetType.GOLD || asset.assetType == AssetType.SILVER
        val quantity = (if (asset.quantity % 1.0 == 0.0) asset.quantity.toInt().toString() else "%.4f".format(asset.quantity)) +
            if (metal) "g" else ""
        return listOfNotNull(
            quantity,
            averagePrice(asset)?.let { "avg ${number(it)}" },
            "ltp ${number(asset.currentValue / asset.quantity)}"
        ).joinToString(" \u00b7 ")
    }
}

/** How a section's holdings are ordered. Holdings that can't be ranked on the chosen key sink to the bottom. */
enum class HoldingSort(val label: String) {
    VALUE("Value") {
        override fun sort(assets: List<NetWorthAssetEntity>) = assets.sortedByDescending { it.currentValue }
    },
    PNL_PERCENT("P&L %") {
        override fun sort(assets: List<NetWorthAssetEntity>) =
            assets.sortedByDescending { PortfolioGainLoss.computePerAsset(it).pctChange }
    },
    TODAY("Today") {
        override fun sort(assets: List<NetWorthAssetEntity>): List<NetWorthAssetEntity> {
            val (known, unknown) = assets.partition { HoldingRowStats.todayChange(it) != null }
            return known.sortedByDescending { HoldingRowStats.todayChange(it)!!.pctChange } +
                unknown.sortedByDescending { it.currentValue }
        }
    };

    abstract fun sort(assets: List<NetWorthAssetEntity>): List<NetWorthAssetEntity>

    /** The next sort in the cycle a tap on the sort control steps through. */
    fun next(): HoldingSort = entries[(ordinal + 1) % entries.size]

    companion object {
        /** A stored name back to a sort; anything unreadable is the default, [VALUE]. */
        fun fromName(name: String?): HoldingSort = entries.firstOrNull { it.name == name } ?: VALUE
    }
}
