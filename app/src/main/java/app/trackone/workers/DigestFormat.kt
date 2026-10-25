package app.trackone.workers

import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.repository.NetWorthRepository.LiveQuote
import app.trackone.utils.GainLoss
import app.trackone.utils.PortfolioGainLoss
import java.util.Locale
import kotlin.math.abs

/*
 * The text and ranking behind the portfolio notifications, kept free of Android/Worker
 * types so it's unit-testable:
 *  - daily:  each market's close (its indices, and how that market's holdings moved today)
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

/** One index's move at the close, as reported by its quote. */
internal data class IndexMove(val label: String, val pctChange: Double)

/**
 * A finished notification: a [title] and the [lines] under it, one finding per line, so the expanded
 * notification reads as a short list.
 */
internal data class DigestMessage(val title: String, val lines: List<String>) {
    /** The lines as the expanded notification shows them. */
    val body: String get() = lines.joinToString("\n")

    /** The lines on one line, for the collapsed notification. */
    val summary: String get() = lines.joinToString(SEPARATOR)
}

/**
 * [market]'s close, led by what the user cares about most: how their holdings there moved ([content],
 * null when none could be measured, or the user holds nothing there). Then the [indices] on one line,
 * then the best and the worst of the holdings.
 */
internal fun formatMarketDigest(market: DigestMarket, indices: List<IndexMove>, content: DailyDigestContent?): DigestMessage {
    val lines = mutableListOf<String>()
    content?.let { lines += "Your ${market.adjective} stocks ${movePct(it.totalPctChange)} (${signedRupees(it.totalAbsChange)})" }
    if (indices.isNotEmpty()) lines += indices.joinToString(SEPARATOR) { "${it.label} ${movePct(it.pctChange)}" }
    content?.best?.let { lines += "Top: ${it.name} ${movePct(it.pctChange)}" }
    content?.worst?.let { lines += "Lagging: ${it.name} ${movePct(it.pctChange)}" }
    return DigestMessage("${market.adjective} market closed", lines)
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

internal fun formatWeeklyMessage(content: DigestContent): DigestMessage {
    val lines = mutableListOf("Total return ${movePct(content.portfolioPctChange)} (${signedRupees(content.portfolioAbsChange)})")
    content.best?.let { lines += "Best: ${it.first} ${movePct(it.second.pctChange)}" }
    content.worst?.let { lines += "Worst: ${it.first} ${movePct(it.second.pctChange)}" }
    return DigestMessage("Your week in review", lines)
}

// ── Colouring ────────────────────────────────────────────────────────────────

internal enum class MoveDirection { GAIN, LOSS, FLAT }

/** A stretch of a notification line that is a move, and which way it went. */
internal data class MoveSpan(val start: Int, val end: Int, val direction: MoveDirection)

/** An arrow and its percentage ("↗ +1.65%"), with the rupee amount in brackets when one follows. */
private val MOVE = Regex("([\u2197\u2198\u2192]) [+-][\\d,.]+%(?: \\([+-]\u20B9[^)]+\\))?")

/**
 * Where [text] has moves to colour, as the app colours money direction everywhere: the whole move
 * (arrow, percentage and amount) takes the colour of its arrow, so a flat day isn't green for its "+".
 * Found from the text itself, so the lines stay plain strings that read the same uncoloured.
 */
internal fun moveSpans(text: String): List<MoveSpan> = MOVE.findAll(text).map { match ->
    val direction = when (match.groupValues[1]) {
        "\u2197" -> MoveDirection.GAIN
        "\u2198" -> MoveDirection.LOSS
        else -> MoveDirection.FLAT
    }
    MoveSpan(match.range.first, match.range.last + 1, direction)
}.toList()

/** The words that introduce a holding on its line, left regular so the holding's name is what stands out. */
private val LABEL = Regex("^(?:Top|Lagging|Best|Worst): ")

/**
 * The stretches of [text] to set in bold: what each move is about ("Your Indian stocks", "Nifty 50", a
 * holding's name), meaning the words before its arrow back to the start of the line or the previous
 * "·". Found from the text, like [moveSpans], so the lines stay plain strings.
 */
internal fun subjectSpans(text: String): List<IntRange> = moveSpans(text).mapNotNull { move ->
    val before = text.substring(0, move.start)
    val lineStart = maxOf(before.lastIndexOf('\n') + 1, before.lastIndexOf(SEPARATOR).let { if (it < 0) 0 else it + SEPARATOR.length })
    val segment = before.substring(lineStart)
    val start = lineStart + (LABEL.find(segment)?.value?.length ?: 0)
    val end = before.trimEnd().length
    if (end > start) start until end else null
}

private const val SEPARATOR = "  \u00b7  "

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

/**
 * A move in the app's own notation, as the Home and Assets screens show it: "↗ +9.08%", "↘ -0.95%".
 * A move that rounds to zero is flat ("→ 0.00%"), not a tiny fall.
 */
internal fun movePct(pct: Double): String {
    val flat = abs(pct) < 0.005
    val arrow = when {
        flat -> "\u2192"
        pct > 0 -> "\u2197"
        else -> "\u2198"
    }
    return "$arrow ${signedPct(if (flat) 0.0 else pct)}"
}
