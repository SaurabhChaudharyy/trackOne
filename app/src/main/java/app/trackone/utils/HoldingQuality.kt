package app.trackone.utils

import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.database.hasLivePrice

/** Why a holding's numbers can't be trusted, in the words shown to the person. */
enum class HoldingIssue(val message: String) {
    NO_LIVE_PRICE("Price isn't updating · edit it to set the ticker"),
    FOREIGN_CURRENCY("Value isn't converted to ₹ yet"),
    IMPLAUSIBLE_GAIN("Gain looks off · check the buy price")
}

/**
 * Sanity checks that surface bad data instead of letting it read as fact. A wrong number with no
 * warning costs more trust than a missing one — e.g. a holding named "Reliance Industries" (not a
 * ticker) never gets a live price, so its value silently goes stale.
 */
object HoldingQuality {

    /** Generous on purpose: long weekends and holidays shouldn't look like a fault. */
    const val STALE_AFTER_MS = 3L * 24 * 60 * 60 * 1000

    /** Beyond ±these multiples of cost, a data-entry error is likelier than a real result. */
    private const val MAX_PLAUSIBLE_MULTIPLE = 20.0
    private const val MIN_PLAUSIBLE_MULTIPLE = 0.05

    /** All problems found, most actionable first. */
    fun issues(asset: NetWorthAssetEntity, nowMs: Long = System.currentTimeMillis()): List<HoldingIssue> =
        buildList {
            // updatedAt only advances when a live refresh succeeds (or the user edits the row).
            if (asset.assetType.hasLivePrice && nowMs - asset.updatedAt > STALE_AFTER_MS) add(HoldingIssue.NO_LIVE_PRICE)
            if (asset.currency != "INR") add(HoldingIssue.FOREIGN_CURRENCY)
            if (hasImplausibleGain(asset)) add(HoldingIssue.IMPLAUSIBLE_GAIN)
        }

    fun primaryIssue(asset: NetWorthAssetEntity, nowMs: Long = System.currentTimeMillis()): HoldingIssue? =
        issues(asset, nowMs).firstOrNull()

    private fun hasImplausibleGain(asset: NetWorthAssetEntity): Boolean {
        if (!PortfolioGainLoss.hasCostBasis(asset) || asset.buyPrice <= 0.0 || asset.quantity <= 0.0) return false
        val multiple = asset.currentValue / (asset.buyPrice * asset.quantity)
        return multiple > MAX_PLAUSIBLE_MULTIPLE || multiple < MIN_PLAUSIBLE_MULTIPLE
    }
}
