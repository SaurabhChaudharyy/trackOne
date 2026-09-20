package app.trackone.data.repository

import app.trackone.data.database.AssetType
import app.trackone.data.model.YahooSearchResult

enum class MatchBasis { ISIN, NAME }

sealed interface SymbolResolution {
    /** Exactly one listing fits. [symbol] is in the form the app stores (bare NSE ticker, "CODE.BO", "AAPL", "BTC-USD"). */
    data class Confident(val symbol: String, val matchedName: String, val basis: MatchBasis) : SymbolResolution

    /** More than one listing fits (e.g. GOOG and GOOGL share a name) — a person has to choose. */
    data class Ambiguous(val symbols: List<String>) : SymbolResolution

    object NotFound : SymbolResolution

    /** The search itself could not be made (offline, HTTP error, timeout) — not the same as "no such company". */
    object Unavailable : SymbolResolution
}

/**
 * Decides which search result, if any, a company name or ISIN refers to. Deliberately conservative:
 * pricing the wrong company silently is worse than leaving a holding flagged, so anything that is
 * not exactly one clear match is [SymbolResolution.Ambiguous] or [SymbolResolution.NotFound].
 *
 * Yahoo's search returns a lot of noise for one company — foreign GDR listings, futures, ETFs on
 * the same underlying, Toronto/Frankfurt copies with the identical name — so each asset type is
 * restricted to the listings that can actually be priced the way the app prices it.
 */
object SymbolMatcher {

    private val ISIN_PATTERN = Regex("^[A-Z]{2}[A-Z0-9]{9}[0-9]$")
    private val TICKER_PATTERN = Regex("^[A-Z0-9][A-Z0-9.&\\-]{0,19}$")

    /** Corporate suffixes that differ between sources for the same company ("Ltd" / "Limited" / "Inc."). */
    private val NAME_NOISE = setOf("LIMITED", "LTD", "INC", "INCORPORATED", "CORP", "CORPORATION", "CO", "COMPANY", "PLC", "THE")

    fun isIsin(text: String): Boolean = ISIN_PATTERN.matches(text.trim().uppercase())

    /** True for something shaped like a ticker (RELIANCE, M&M, BAJAJ-AUTO, TCS.NS), false for a company name. */
    fun looksLikeTicker(text: String): Boolean = TICKER_PATTERN.matches(text.trim())

    fun normaliseName(name: String): String =
        name.uppercase()
            .replace("&", " AND ")
            .replace(Regex("[^A-Z0-9 ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() && it !in NAME_NOISE }
            .joinToString(" ")

    fun choose(query: String, candidates: List<YahooSearchResult>, type: AssetType): SymbolResolution {
        val basis = if (isIsin(query)) MatchBasis.ISIN else MatchBasis.NAME
        return when (type) {
            AssetType.STOCK_IN -> chooseListed(query, basis, candidates.equities().filter { it.isNseOrBse() }, prefersNse = true)
            AssetType.STOCK_US -> chooseListed(query, basis, candidates.equities().filter { it.isUsExchange() }, prefersNse = false)
            AssetType.CRYPTO   -> chooseCrypto(query, candidates)
            else               -> SymbolResolution.NotFound   // MF/cash/bank/gold/silver have no ticker to resolve
        }
    }

    private fun List<YahooSearchResult>.equities() = filter { it.quoteType == "EQUITY" || it.quoteType == "ETF" }

    private fun YahooSearchResult.isNseOrBse() = symbol.endsWith(".NS") || symbol.endsWith(".BO")

    private fun YahooSearchResult.isUsExchange() =
        exchange.contains("NASDAQ", ignoreCase = true) || exchange.contains("NYSE", ignoreCase = true)

    private fun chooseListed(
        query: String,
        basis: MatchBasis,
        listings: List<YahooSearchResult>,
        prefersNse: Boolean
    ): SymbolResolution {
        val matching = when (basis) {
            // An ISIN names one security, so what comes back must be that one company (its NSE and
            // BSE listings share a name). Differing names mean the search returned noise.
            MatchBasis.ISIN ->
                if (listings.map { normaliseName(it.canonicalName()) }.distinct().size == 1) listings else emptyList()
            MatchBasis.NAME -> {
                val wanted = normaliseName(query)
                listings.filter { wanted.isNotEmpty() && (wanted == normaliseName(it.shortName) || wanted == normaliseName(it.longName)) }
            }
        }
        if (matching.isEmpty()) return SymbolResolution.NotFound

        if (prefersNse) {
            val nse = matching.filter { it.symbol.endsWith(".NS") }
            val bse = matching.filter { it.symbol.endsWith(".BO") }
            return when {
                nse.size == 1 -> confident(nse.first(), nse.first().symbol.removeSuffix(".NS"), basis)
                // A bare BSE code would be looked up on NSE, so BSE-only listings keep their suffix.
                nse.isEmpty() && bse.size == 1 -> confident(bse.first(), bse.first().symbol, basis)
                else -> SymbolResolution.Ambiguous(matching.map { it.symbol })
            }
        }
        val symbols = matching.map { it.symbol }.distinct()
        return if (symbols.size == 1) confident(matching.first(), symbols.first(), basis)
        else SymbolResolution.Ambiguous(symbols)
    }

    /** Yahoo lists a coin as "<Name> USD" (e.g. "Bitcoin USD"); "Bitcoin Cash USD" and the ETFs/futures don't match. */
    private fun chooseCrypto(query: String, candidates: List<YahooSearchResult>): SymbolResolution {
        val wanted = normaliseName(query) + " USD"
        val matching = candidates.filter {
            it.quoteType == "CRYPTOCURRENCY" && (normaliseName(it.shortName) == wanted || normaliseName(it.longName) == wanted)
        }
        return when (matching.size) {
            0 -> SymbolResolution.NotFound
            1 -> confident(matching.first(), matching.first().symbol, MatchBasis.NAME)
            else -> SymbolResolution.Ambiguous(matching.map { it.symbol })
        }
    }

    private fun YahooSearchResult.canonicalName() = longName.ifBlank { shortName }

    private fun confident(result: YahooSearchResult, symbol: String, basis: MatchBasis) =
        SymbolResolution.Confident(symbol, result.displayName, basis)
}
