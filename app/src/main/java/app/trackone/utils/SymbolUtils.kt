package app.trackone.utils

/**
 * NSE-listed Indian stocks need the ".NS" suffix for Yahoo Finance lookups. Without it, a
 * bare ticker can silently resolve to an unrelated company on another exchange that happens
 * to share the same symbol — e.g. "IEX" is the Indian Energy Exchange on the NSE, but also
 * IDEX Corporation on the NYSE. A broker CSV import stores the bare NSE ticker as the asset
 * name (see BrokerCsvRepository's row parsers), so anywhere that looks up a live price for a
 * STOCK_IN asset by name needs this — not just the net-worth refresh path.
 */
object SymbolUtils {
    /** Guarded on an existing '.' so an already-suffixed symbol isn't double-suffixed. */
    fun normaliseIndianSymbol(symbol: String): String {
        val trimmed = symbol.trim().uppercase()
        return if (trimmed.contains('.')) trimmed else "$trimmed.NS"
    }

    /** What to show a person: Yahoo's index caret and NSE/BSE suffixes are lookup plumbing, not names. */
    fun displaySymbol(symbol: String): String =
        symbol.removePrefix("^").removeSuffix(".NS").removeSuffix(".BO")
}
