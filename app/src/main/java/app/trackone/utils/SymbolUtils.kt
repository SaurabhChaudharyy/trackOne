package app.trackone.utils

import app.trackone.data.database.AssetType

/**
 * NSE-listed Indian stocks need the ".NS" suffix for Yahoo Finance lookups. Without it, a
 * bare ticker can silently resolve to an unrelated company on another exchange that happens
 * to share the same symbol — e.g. "IEX" is the Indian Energy Exchange on the NSE, but also
 * IDEX Corporation on the NYSE. A broker CSV import stores the bare NSE ticker as the asset
 * name (see BrokerCsvRepository's row parsers), so anywhere that looks up a live price for a
 * STOCK_IN asset by name needs this — not just the net-worth refresh path.
 */
object SymbolUtils {
    /**
     * An NSE series tag some brokers append to a holding's name ("GOLDIETF-E", "IDEA-BE"): it says
     * how the share trades, and is not part of the ticker Yahoo knows. A fixed list rather than any
     * hyphen, since BAJAJ-AUTO and MCDOWELL-N are real tickers.
     */
    private val NSE_SERIES_TAG = Regex("-(E|BE|BZ|EQ|SM|ST)$")

    /**
     * The Yahoo Finance symbol a holding's stored name is priced under, whichever way it was
     * entered. The one place this mapping lives: the live quote, Home's movers and the history
     * charts all look a holding up by it, and had each grown their own copy.
     */
    fun yahooSymbol(name: String, type: AssetType): String = when (type) {
        AssetType.GOLD     -> "GC=F"
        AssetType.SILVER   -> "SI=F"
        AssetType.STOCK_IN -> normaliseIndianSymbol(name)
        // Yahoo writes a class share with a hyphen ("BRK-B"); an Interactive Brokers export has a space.
        else               -> name.trim().uppercase().replace(Regex("\\s+"), "-")
    }

    /** Guarded on an existing '.' so an already-suffixed symbol isn't double-suffixed. */
    fun normaliseIndianSymbol(symbol: String): String {
        val trimmed = symbol.trim().uppercase()
        val dot = trimmed.indexOf('.')
        val base = if (dot >= 0) trimmed.substring(0, dot) else trimmed
        val exchange = if (dot >= 0) trimmed.substring(dot) else ".NS"
        return base.replace(NSE_SERIES_TAG, "").ifEmpty { base } + exchange
    }

    /** What to show a person: Yahoo's index caret and NSE/BSE suffixes are lookup plumbing, not names. */
    fun displaySymbol(symbol: String): String =
        symbol.removePrefix("^").removeSuffix(".NS").removeSuffix(".BO")
}
