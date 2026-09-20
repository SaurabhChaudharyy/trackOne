package app.trackone.utils

/** The history windows offered on Home, with the Yahoo `range`/`interval` that fetches each. */
enum class ChartRange(val label: String, val yahooRange: String, val interval: String) {
    WEEK("1W", "5d", "1d"),
    MONTH("1M", "1mo", "1d"),
    QUARTER("3M", "3mo", "1d"),
    YEAR("1Y", "1y", "1d")
}
