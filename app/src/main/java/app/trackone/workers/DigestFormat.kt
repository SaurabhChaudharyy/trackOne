package app.trackone.workers

import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.repository.NetWorthRepository.LiveQuote
import app.trackone.utils.GainLoss
import app.trackone.utils.PortfolioGainLoss
import java.util.Locale
import kotlin.math.abs

/*
 * The text and ranking behind the two portfolio notifications, kept free of Android/Worker
 * types so it's unit-testable:
 *  - daily:  how the market-priced holdings moved TODAY (needs live quotes' previous close)
 *  - weekly: total gain/loss since purchase (needs nothing but the stored holdings)
 */

// ── Daily: today's move ──────────────────────────────────────────────────────

internal data class DayMove(val name: String, val absChange: Double, val pctChange: Double)

internal data class DailyDigestContent(
    val totalAbsChange: Double,
    val totalPctChange: Double,
    val best: DayMove?,
    val worst: DayMove?
)

/**
 * Today's move across the holdings that have a live [quotes] entry with a known previous close.
 * Holdings without a market price (MF, cash, bank) or whose quote failed are left out rather
 * than counted as "unchanged", which would dilute the percentage. Returns null when nothing
 * could be measured, so the caller can skip the notification instead of posting a fake "0%".
 *
 * The percentage is against the previous value of the measured holdings only. When only one
 * holding is measurable, [DailyDigestContent.worst] is null instead of repeating it.
 */
internal fun computeDailyDigest(
    assets: List<NetWorthAssetEntity>,
    quotes: Map<Long, LiveQuote>
): DailyDigestContent? {
    val moves = mutableListOf<DayMove>()
    var totalAbs = 0.0
    var totalPrevious = 0.0

    for (asset in assets) {
        val quote = quotes[asset.id] ?: continue
        if (quote.previousCloseInr <= 0.0) continue
        val previousValue = quote.previousCloseInr * asset.quantity
        val absChange = (quote.priceInr - quote.previousCloseInr) * asset.quantity
        totalAbs += absChange
        totalPrevious += previousValue
        if (asset.name.isNotBlank()) {
            moves += DayMove(asset.name, absChange, absChange / previousValue * 100.0)
        }
    }

    if (totalPrevious <= 0.0) return null
    return DailyDigestContent(
        totalAbsChange = totalAbs,
        totalPctChange = totalAbs / totalPrevious * 100.0,
        best = moves.maxByOrNull { it.pctChange },
        worst = if (moves.size > 1) moves.minByOrNull { it.pctChange } else null
    )
}

internal fun formatDailyBody(content: DailyDigestContent): String = buildString {
    append("Today ${signedRupees(content.totalAbsChange)} (${signedPct(content.totalPctChange)}).")
    content.best?.let { append(" Top: ${it.name} ${signedPct(it.pctChange)}.") }
    content.worst?.let { append(" Lagging: ${it.name} ${signedPct(it.pctChange)}.") }
}

// ── Weekly: total return since purchase ──────────────────────────────────────

internal data class DigestContent(
    val portfolioAbsChange: Double,
    val portfolioPctChange: Double,
    val best: Pair<String, GainLoss>?,
    val worst: Pair<String, GainLoss>?
)

/**
 * Ranks [assets] by cumulative per-asset gain/loss to pick a best and worst performer.
 * Assets with a blank name are excluded (nothing meaningful to display). When there's only
 * one rankable asset, [DigestContent.worst] is null rather than repeating the same holding
 * as both best and worst.
 */
internal fun computeDigestContent(assets: List<NetWorthAssetEntity>): DigestContent {
    val portfolio = PortfolioGainLoss.compute(assets)

    // Cash and bank balances have no performance to rank (see PortfolioGainLoss.hasCostBasis).
    val ranked = assets
        .filter { it.name.isNotBlank() && PortfolioGainLoss.hasCostBasis(it) }
        .map { it.name to PortfolioGainLoss.computePerAsset(it) }
    val best = ranked.maxByOrNull { it.second.pctChange }
    val worst = if (ranked.size > 1) ranked.minByOrNull { it.second.pctChange } else null

    return DigestContent(portfolio.absChange, portfolio.pctChange, best, worst)
}

internal fun formatWeeklyBody(content: DigestContent): String = buildString {
    append("Total return ${signedRupees(content.portfolioAbsChange)} (${signedPct(content.portfolioPctChange)}).")
    content.best?.let { append(" Best: ${it.first} ${signedPct(it.second.pctChange)}.") }
    content.worst?.let { append(" Worst: ${it.first} ${signedPct(it.second.pctChange)}.") }
}

// ── Shared formatting ────────────────────────────────────────────────────────

private fun sign(value: Double) = if (value < 0) "-" else "+"

/** "+₹4.20L" / "-₹1,200" — Indian lakh/crore units above 1 lakh, grouped digits below. */
internal fun signedRupees(amount: Double): String {
    val abs = abs(amount)
    val body = when {
        abs >= 1e7 -> "%.2fCr".format(Locale.US, abs / 1e7)
        abs >= 1e5 -> "%.2fL".format(Locale.US, abs / 1e5)
        else -> "%,.0f".format(Locale.US, abs)
    }
    return "${sign(amount)}₹$body"
}

/** Two decimals normally; whole numbers once it's in the thousands, where decimals are noise. */
internal fun signedPct(pct: Double): String {
    val abs = abs(pct)
    val body = if (abs >= 1000.0) "%,.0f".format(Locale.US, abs) else "%.2f".format(Locale.US, abs)
    return "${sign(pct)}$body%"
}
