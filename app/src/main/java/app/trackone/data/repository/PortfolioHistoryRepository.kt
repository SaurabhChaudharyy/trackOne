package app.trackone.data.repository

import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.database.hasLivePrice
import app.trackone.ui.home.PortfolioChartPoint
import app.trackone.utils.ChartRange
import app.trackone.utils.CurrencyConversion
import app.trackone.utils.DailyClose
import app.trackone.utils.HistoryHolding
import app.trackone.utils.PortfolioGainLoss
import app.trackone.utils.PortfolioHistory
import app.trackone.utils.SymbolUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Home chart: what the holdings you have TODAY would have been worth on each day of a
 * window, from Yahoo's daily closes. It says nothing about when anything was actually bought.
 *
 * Market-priced holdings use their price history; anything that can't be looked up (cash, bank,
 * mutual funds, or a holding whose name isn't a ticker) stays flat at its current value, so the
 * chart never invents a price movement it doesn't have.
 */
@Singleton
class PortfolioHistoryRepository @Inject constructor(
    private val apiService: YahooFinanceApiService,
    private val netWorthRepository: NetWorthRepository
) {
    /** Native-currency daily closes for one symbol, as [epochDay to close] (IST days). */
    private class RawSeries(val currency: String, val points: List<Pair<Long, Double>>)

    private data class CacheKey(val symbol: String, val range: ChartRange)
    private class CacheEntry(val fetchedAtMs: Long, val series: RawSeries?)

    // Keyed by symbol+range only (never by quantity or value), so the reactive rebuilds that follow
    // every price refresh recompute from memory instead of re-hitting the network.
    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()

    /** Bounds the concurrent requests: a big portfolio would otherwise fire dozens at once. */
    private val fetchLimiter = Semaphore(MAX_PARALLEL_FETCHES)

    suspend fun history(
        assets: List<NetWorthAssetEntity>,
        range: ChartRange,
        nowMs: Long = System.currentTimeMillis()
    ): List<PortfolioChartPoint> = withContext(Dispatchers.IO) {
        val priced = assets.filter { it.assetType.hasLivePrice && it.name.isNotBlank() }
        if (priced.isEmpty()) return@withContext emptyList()

        val (fx, rawByAsset) = coroutineScope {
            val fxDeferred = async { fetchRaw(FX_SYMBOL, range, nowMs) }
            val assetDeferred = priced.map { asset -> asset to async { fetchRaw(symbolFor(asset), range, nowMs) } }
            fxDeferred.await() to assetDeferred.map { (asset, d) -> asset to d.await() }.toMap()
        }

        val fxPoints = fx?.points.orEmpty()
        // No FX history (fetch failed): fall back to the last rate this device saw, once.
        val fallbackRate = if (fxPoints.isEmpty()) netWorthRepository.fetchUsdInrRate() else 0.0
        fun usdInrOn(day: Long): Double =
            if (fxPoints.isEmpty()) fallbackRate
            else (fxPoints.lastOrNull { it.first <= day } ?: fxPoints.first()).second

        val holdings = assets.map { asset ->
            val raw = rawByAsset[asset]
            val isMetal = asset.assetType == AssetType.GOLD || asset.assetType == AssetType.SILVER
            val closes = raw?.points?.map { (day, native) ->
                DailyClose(day, CurrencyConversion.perUnitInr(native, raw.currency, usdInrOn(day), isMetal))
            }
            HistoryHolding(asset.quantity, asset.currentValue, closes)
        }

        val liveTotal = assets.sumOf { it.currentValue }
        val invested = PortfolioGainLoss.compute(assets).invested
        val today = PortfolioHistory.epochDayIst(nowMs / 1000)

        PortfolioHistory.withLivePoint(PortfolioHistory.buildSeries(holdings), today, liveTotal).map {
            PortfolioChartPoint(
                timestamp = PortfolioHistory.epochDayIstToMillis(it.epochDay),
                invested = invested,
                current = it.valueInr
            )
        }
    }

    /** Same symbol mapping as the live quote (gold/silver futures, NSE suffix for Indian stocks). */
    private fun symbolFor(asset: NetWorthAssetEntity): String = when (asset.assetType) {
        AssetType.GOLD     -> "GC=F"
        AssetType.SILVER   -> "SI=F"
        AssetType.STOCK_IN -> SymbolUtils.normaliseIndianSymbol(asset.name)
        else               -> asset.name.trim().uppercase()
    }

    private suspend fun fetchRaw(symbol: String, range: ChartRange, nowMs: Long): RawSeries? {
        val key = CacheKey(symbol, range)
        cache[key]?.let { entry ->
            val ttl = if (entry.series != null) SUCCESS_TTL_MS else FAILURE_TTL_MS
            if (nowMs - entry.fetchedAtMs < ttl) return entry.series
        }

        val series = try {
            fetchLimiter.withPermit { download(symbol, range) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        cache[key] = CacheEntry(nowMs, series)
        return series
    }

    private suspend fun download(symbol: String, range: ChartRange): RawSeries? {
        val response = apiService.getChartData(symbol, interval = range.interval, range = range.yahooRange)
        if (!response.isSuccessful) return null
        val result = response.body()?.chart?.result?.firstOrNull() ?: return null
        val timestamps = result.timestamps
        val closes = result.indicators?.quote?.firstOrNull()?.close
        if (timestamps.isNullOrEmpty() || closes == null) return null

        val points = timestamps.mapIndexedNotNull { i, ts ->
            val close = closes.getOrNull(i)
            if (close == null || close <= 0.0) null else PortfolioHistory.epochDayIst(ts) to close
        }
            .groupBy({ it.first }, { it.second })          // one bar per IST day: keep the last
            .map { (day, prices) -> day to prices.last() }
            .sortedBy { it.first }

        return if (points.isEmpty()) null else RawSeries(result.meta.currency, points)
    }

    private companion object {
        const val FX_SYMBOL = "USDINR=X"
        const val MAX_PARALLEL_FETCHES = 4
        const val SUCCESS_TTL_MS = 15 * 60 * 1000L
        /** Failures are retried sooner, but not on every rebuild — that would hammer a rate-limited API. */
        const val FAILURE_TTL_MS = 2 * 60 * 1000L
    }
}
