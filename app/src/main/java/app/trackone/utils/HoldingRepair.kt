package app.trackone.utils

import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity

/** Fixes for holdings that earlier app versions, or a restored backup, left in an inconsistent state. */
object HoldingRepair {

    /** A US holding's repaired gain has to land in this band of its cost, or the rate was not the whole problem. */
    private val REPAIRED_MULTIPLE = 0.2..5.0

    /**
     * The buy price in INR for a US holding that is labelled INR but whose buy price is still in USD,
     * or null when that is not what is wrong with it.
     *
     * A re-import used to overwrite the buy price (USD) and leave the row labelled INR, and a backup
     * restores both as saved. The value is in INR, so the gain comes out about 90x too big. The fix is
     * only made when it is unmistakable: the gain is beyond what [HoldingQuality] accepts, and
     * converting the buy price brings it to a normal one. A genuine 100x holding stays flagged.
     */
    fun usdBuyPriceInInr(asset: NetWorthAssetEntity, valueInr: Double, usdInrRate: Double): Double? {
        if (asset.assetType != AssetType.STOCK_US || asset.currency != "INR") return null
        if (!PortfolioGainLoss.hasCostBasis(asset) || asset.buyPrice <= 0.0 || asset.quantity <= 0.0) return null
        if (valueInr <= 0.0 || usdInrRate <= 0.0) return null

        val costNow = asset.buyPrice * asset.quantity
        if (valueInr / costNow <= HoldingQuality.MAX_PLAUSIBLE_MULTIPLE) return null

        val converted = asset.buyPrice * usdInrRate
        return converted.takeIf { valueInr / (it * asset.quantity) in REPAIRED_MULTIPLE }
    }

    /**
     * [duplicate] folded into [target], the same security held under two names: the quantities add,
     * the buy price is the quantity-weighted average, and the value follows [target]'s per-unit price.
     * Null when the two can't be combined honestly: different kinds or currencies, or a lot with no
     * quantity or no buy price (an average would invent one).
     */
    fun merge(target: NetWorthAssetEntity, duplicate: NetWorthAssetEntity): NetWorthAssetEntity? {
        if (target.assetType != duplicate.assetType || target.currency != duplicate.currency) return null
        if (target.quantity <= 0.0 || duplicate.quantity <= 0.0) return null
        if (target.buyPrice <= 0.0 || duplicate.buyPrice <= 0.0) return null

        val quantity = target.quantity + duplicate.quantity
        val averageBuyPrice = (target.quantity * target.buyPrice + duplicate.quantity * duplicate.buyPrice) / quantity
        return target.copy(
            quantity = quantity,
            buyPrice = averageBuyPrice,
            currentValue = target.currentValue / target.quantity * quantity
        )
    }
}
