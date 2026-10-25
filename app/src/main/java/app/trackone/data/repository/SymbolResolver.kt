package app.trackone.data.repository

import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.AssetType
import app.trackone.data.model.YahooSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns "what the broker calls it" (an ISIN or a company name) into the ticker the app can price.
 * The ISIN is tried first because it names exactly one security; the name is the fallback.
 *
 * Never throws. When no search could be made at all it returns [SymbolResolution.Unavailable] and
 * remembers nothing, so the next attempt tries again; a definite answer is cached so a large import
 * or repeated refreshes don't repeat searches.
 */
@Singleton
class SymbolResolver @Inject constructor(
    private val apiService: YahooFinanceApiService
) {
    private class Entry(val atMs: Long, val result: SymbolResolution)

    private val cache = ConcurrentHashMap<String, Entry>()

    suspend fun resolve(
        name: String,
        isin: String?,
        type: AssetType,
        nowMs: Long = System.currentTimeMillis()
    ): SymbolResolution {
        if (type != AssetType.STOCK_IN && type != AssetType.STOCK_US && type != AssetType.CRYPTO) return SymbolResolution.NotFound

        val cleanIsin = isin?.trim()?.uppercase()?.takeIf { SymbolMatcher.isIsin(it) }
        val cleanName = name.trim()
        val key = "$type|${cleanIsin.orEmpty()}|${cleanName.uppercase()}"
        cache[key]?.let { if (nowMs - it.atMs < ttlFor(it.result)) return it.result }

        var answered = false   // did at least one search actually return, as opposed to failing?
        var best: SymbolResolution = SymbolResolution.NotFound

        for (query in listOfNotNull(cleanIsin, cleanName.takeIf { it.isNotEmpty() })) {
            val candidates = search(query) ?: continue
            answered = true
            val result = SymbolMatcher.choose(query, candidates, type)
            if (result is SymbolResolution.Confident) { best = result; break }
            if (result is SymbolResolution.Ambiguous && best is SymbolResolution.NotFound) best = result
        }

        if (!answered) return SymbolResolution.Unavailable
        cache[key] = Entry(nowMs, best)
        return best
    }

    /** null = the search itself failed (offline, HTTP error, timeout), which is not the same as "no results". */
    private suspend fun search(query: String): List<YahooSearchResult>? = try {
        withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            val response = apiService.searchSymbol(query)
            if (response.isSuccessful) response.body()?.quotes.orEmpty() else null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun ttlFor(result: SymbolResolution) =
        if (result is SymbolResolution.Confident) CONFIDENT_TTL_MS else UNRESOLVED_TTL_MS

    private companion object {
        const val SEARCH_TIMEOUT_MS = 6_000L
        const val CONFIDENT_TTL_MS = 24 * 60 * 60 * 1000L
        const val UNRESOLVED_TTL_MS = 10 * 60 * 1000L
    }
}
