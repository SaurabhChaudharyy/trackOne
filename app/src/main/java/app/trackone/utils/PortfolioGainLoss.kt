package app.trackone.utils

import app.trackone.data.database.NetWorthAssetEntity

/**
 * One gain/loss figure: how much was put in, what it's worth now, and the delta both ways.
 * For an asset with no known buy price, [invested] is defined as [current] (break-even) —
 * there's no honest cost-basis to compare against, and treating it as 0 would make every such
 * asset look like a windfall gain.
 */
data class GainLoss(
    val invested: Double,
    val current: Double,
    val absChange: Double,
    val pctChange: Double
)

/**
 * The portfolio P&L math used to live twice — once in NetWorthViewModel.totalPnL and once in
 * HomeViewModel.buildPortfolioSummary — as two independently-maintained copies of the same
 * formula. Pulled out here so both ViewModels (and the daily digest worker) share one
 * implementation instead of three that can silently drift apart.
 */
object PortfolioGainLoss {

    fun compute(assets: List<NetWorthAssetEntity>): GainLoss {
        var totalInvested = 0.0
        var totalCurrent = 0.0
        for (asset in assets) {
            val invested = investedValue(asset)
            totalInvested += invested
            totalCurrent += asset.currentValue
        }
        return fromTotals(totalInvested, totalCurrent)
    }

    fun computePerAsset(asset: NetWorthAssetEntity): GainLoss =
        fromTotals(investedValue(asset), asset.currentValue)

    private fun investedValue(asset: NetWorthAssetEntity): Double =
        if (asset.buyPrice > 0.0) asset.buyPrice * asset.quantity else asset.currentValue

    private fun fromTotals(invested: Double, current: Double): GainLoss {
        val absChange = current - invested
        val pctChange = if (invested > 0.0) (absChange / invested) * 100.0 else 0.0
        return GainLoss(invested, current, absChange, pctChange)
    }
}
