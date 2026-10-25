package app.trackone.utils

/**
 * The history windows offered on Home, with the Yahoo `range`/`interval` that fetches each.
 * [intraday] ranges come back as bars within a day, so the chart keys them by timestamp rather
 * than by calendar day. A [fullHistory] range has no [yahooRange]: it is fetched from the epoch
 * with an explicit period, so the requested [interval] is honoured. Declaration order is the order
 * of the chips.
 */
enum class ChartRange(
    val label: String,
    val yahooRange: String?,
    val interval: String,
    val intraday: Boolean = false,
    val fullHistory: Boolean = false
) {
    DAY("1D", "1d", "5m", intraday = true),
    WEEK("1W", "5d", "1d"),
    MONTH("1M", "1mo", "1d"),
    QUARTER("3M", "3mo", "1d"),
    YEAR("1Y", "1y", "1d"),
    // Weekly bars: every exchange stamps a week on the same IST day, so holdings from different
    // markets line up. Monthly bars drift a day or two apart between exchanges and would zig-zag.
    ALL("ALL", null, "1wk", fullHistory = true)
}
