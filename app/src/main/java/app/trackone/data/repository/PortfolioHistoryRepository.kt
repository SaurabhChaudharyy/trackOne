package app.trackone.data.repository

import app.trackone.data.api.RequestLimits
import app.trackone.data.api.YahooFinanceApiService
import app.trackone.data.database.AssetType
import app.trackone.data.database.NetWorthAssetEntity
import app.trackone.data.database.hasLivePrice
import app.trackone.ui.home.PortfolioChartPoint
import app.trackone.utils.ChartRange
import app.trackone.utils.CurrencyConversion
import app.trackone.utils.DailyClose
import app.trackone.utils.HistoryHolding
import app.trackone.utils.ValuePoint
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
import java.util.concurrent.atomic.AtomicBoolean
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
    private val netWorthRepository: NetWorthRepository,
    private val seriesStore: ChartSeriesStore
) {
    /**
     * Native-currency closes for one symbol, as [time key to close]: IST day numbers for daily
     * ranges, epoch seconds for the intraday one (see [timeKey]).
     */
    private class RawSeries(val currency: String, val points: List<Pair<Long, Double>>)

    private data class CacheKey(val symbol: String, val range: ChartRange)
    private class CacheEntry(val fetchedAtMs: Long, val series: RawSeries?)

    // Keyed by symbol+range only (never by quantity or value), so the reactive rebuilds that follow
    // every price refresh recompute from memory instead of re-hitting the network. Below it, the same
    // series are kept on disk ([seriesStore]) so a cold start does not download them all again.
    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()

    private val pruned = AtomicBoolean(false)

    /** Bounds the concurrent requests: a big portfolio would otherwise fire dozens at once. */
    private val fetchLimiter = Semaphore(RequestLimits.MAX_PARALLEL)

    suspend fun history(
        assets: List<NetWorthAssetEntity>,
        range: ChartRange,
        nowMs: Long = System.currentTimeMillis()
    ): List<PortfolioChartPoint> = withContext(Dispatchers.IO) {
        val priced = assets.filter { it.assetType.hasLivePrice && it.name.isNotBlank() }
        if (priced.isEmpty()) return@withContext emptyList()

        // Once per process: sold holdings leave series on disk that nothing reads again.
        if (pruned.compareAndSet(false, true)) seriesStore.prune(olderThanMs = nowMs - PRUNE_AFTER_MS)

        val (fx, rawByAsset) = coroutineScope {
            val fxDeferred = async { fetchRaw(FX_SYMBOL, range, nowMs) }
            val assetDeferred = priced.map { asset -> asset to async { fetchRaw(symbolFor(asset), range, nowMs) } }
            fxDeferred.await() to assetDeferred.map { (asset, d) -> asset to d.await() }.toMap()
        }

        // Each currency a holding is quoted in needs its own →INR history: USD is fetched above
        // alongside the holdings; any other (GBP for a pence quote, CAD, ...) is fetched now.
        val majors = rawByAsset.values.mapNotNull { it?.currency?.let(CurrencyConversion::majorCurrency) }
            .filter { it != "INR" }.toSet()
        val fxByMajor: Map<String, List<Pair<Long, Double>>> = coroutineScope {
            majors.map { major ->
                major to (if (major == "USD") async { fx } else async { fetchRaw("${major}INR=X", range, nowMs) })
            }.associate { (major, d) -> major to d.await()?.points.orEmpty() }
        }
        // No FX history (fetch failed): fall back to the latest live rate, once per currency.
        // A currency with neither has no rate at all (null), and its holdings stay flat.
        val fallbackRates: Map<String, Double?> = fxByMajor.filterValues { it.isEmpty() }.keys
            .associateWith { netWorthRepository.fetchRateToInr(it) }
        fun rateOn(major: String, day: Long): Double? {
            if (major == "INR") return 1.0
            val points = fxByMajor[major].orEmpty()
            return if (points.isEmpty()) fallbackRates[major]
            else (points.lastOrNull { it.first <= day } ?: points.first()).second
        }

        val holdings = assets.map { asset ->
            val raw = rawByAsset[asset]
            val isMetal = asset.assetType == AssetType.GOLD || asset.assetType == AssetType.SILVER
            val closes = raw?.let { series ->
                val major = CurrencyConversion.majorCurrency(series.currency)
                series.points.map { (day, native) ->
                    val rate = rateOn(major, day) ?: return@let null
                    DailyClose(day, CurrencyConversion.perUnitInr(native, series.currency, rate, isMetal))
                }
            }
            HistoryHolding(asset.quantity, asset.currentValue, closes)
        }

        val liveTotal = assets.sumOf { it.currentValue }
        val invested = PortfolioGainLoss.compute(assets).invested
        val full = PortfolioHistory.buildSeries(holdings)
        val series = if (range.fullHistory) trimToCoverage(full, holdings) else full
        // A daily chart ends on today's point. An intraday one ends on its last bar: the market may
        // be shut (a weekend), and a point stamped "now" would stretch the axis past the session.
        val liveKey = if (range.intraday) series.lastOrNull()?.epochDay ?: 0L else PortfolioHistory.epochDayIst(nowMs / 1000)

        PortfolioHistory.withLivePoint(series, liveKey, liveTotal).map {
            PortfolioChartPoint(
                timestamp = if (range.intraday) it.epochDay * 1000L else PortfolioHistory.epochDayIstToMillis(it.epochDay),
                invested = invested,
                current = it.valueInr
            )
        }
    }

    /** Same symbol mapping as the live quote. */
    private fun symbolFor(asset: NetWorthAssetEntity): String = SymbolUtils.yahooSymbol(asset.name, asset.assetType)

    private suspend fun fetchRaw(symbol: String, range: ChartRange, nowMs: Long): RawSeries? {
        val key = CacheKey(symbol, range)
        cache[key]?.let { entry ->
            val ttl = when {
                entry.series == null -> FAILURE_TTL_MS
                range.intraday -> INTRADAY_TTL_MS
                else -> SUCCESS_TTL_MS
            }
            if (nowMs - entry.fetchedAtMs < ttl) return entry.series
        }

        savedSeries(symbol, range, nowMs)?.let { saved ->
            // Checked on disk this once: memory answers for the usual window from now. Today's chart
            // keeps the age it was downloaded at, since its window is short.
            cache[key] = CacheEntry(if (range.intraday) saved.fetchedAtMs else nowMs, saved.series)
            return saved.series
        }

        val series = try {
            fetchLimiter.withPermit { download(symbol, range, nowMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        cache[key] = CacheEntry(nowMs, series)
        // A failure is not saved: the next cold start should try again, not inherit it.
        if (series != null) seriesStore.save(symbol, range, ChartSeriesStore.Stored(nowMs, series.currency, series.points))
        return series
    }

    private class SavedSeries(val fetchedAtMs: Long, val series: RawSeries)

    /** The series saved on disk for [symbol] and [range], when it is still recent enough to use. */
    private fun savedSeries(symbol: String, range: ChartRange, nowMs: Long): SavedSeries? {
        val stored = seriesStore.load(symbol, range) ?: return null
        val ttl = if (range.intraday) INTRADAY_TTL_MS else PERSISTED_TTL_MS
        // A stamp from the future means the clock was set back; nothing can be said about its age.
        if (nowMs - stored.fetchedAtMs !in 0 until ttl) return null
        return SavedSeries(stored.fetchedAtMs, RawSeries(stored.currency, stored.points))
    }

    private suspend fun download(symbol: String, range: ChartRange, nowMs: Long): RawSeries? {
        val response = apiService.getChartData(
            symbol,
            interval = range.interval,
            range = range.yahooRange,
            period1 = if (range.fullHistory) 0L else null,
            period2 = if (range.fullHistory) nowMs / 1000 else null
        )
        if (!response.isSuccessful) return null
        val result = response.body()?.chart?.result?.firstOrNull() ?: return null
        val timestamps = result.timestamps
        val closes = result.indicators?.quote?.firstOrNull()?.close
        if (timestamps.isNullOrEmpty() || closes == null) return null

        val points = timestamps.mapIndexedNotNull { i, ts ->
            val close = closes.getOrNull(i)
            if (close == null || close <= 0.0) null else timeKey(ts, range) to close
        }
            .groupBy({ it.first }, { it.second })          // one bar per time key (per IST day, for daily ranges): keep the last
            .map { (day, prices) -> day to prices.last() }
            .sortedBy { it.first }

        return if (points.isEmpty()) null else RawSeries(result.meta.currency, points)
    }

    /**
     * A full history starts where holdings worth [MIN_COVERED_SHARE] of the portfolio have real
     * prices, not at the oldest holding's listing: before that most of the line would be holdings
     * that didn't exist yet, valued at their first price, and the % change would be meaningless.
     * Falls back to the whole series if trimming would leave nothing to draw.
     */
    private fun trimToCoverage(series: List<ValuePoint>, holdings: List<HistoryHolding>): List<ValuePoint> {
        val from = PortfolioHistory.coveredFrom(holdings, MIN_COVERED_SHARE) ?: return series
        return series.filter { it.epochDay >= from }.takeIf { it.size >= 2 } ?: series
    }

    /** Daily ranges collapse to one bar per IST day; intraday bars keep their own timestamp (seconds). */
    private fun timeKey(epochSeconds: Long, range: ChartRange): Long =
        if (range.intraday) epochSeconds else PortfolioHistory.epochDayIst(epochSeconds)

    companion object {
        private const val FX_SYMBOL = "USDINR=X"
        private const val MIN_COVERED_SHARE = 0.9
        private const val SUCCESS_TTL_MS = 15 * 60 * 1000L

        /**
         * How long a series saved on disk is used by a cold start. A daily series only ever changes on
         * its last bar, and the chart replaces that bar with the live total, so a few hours old is not
         * visible; it is the same window the stock detail chart already keeps a month of closes for.
         */
        internal const val PERSISTED_TTL_MS = 4 * 60 * 60 * 1000L

        /** Series nobody asked for in this long belong to holdings that are gone. */
        private const val PRUNE_AFTER_MS = 24 * 60 * 60 * 1000L

        /** Today's chart moves with the market, so it goes stale faster than a month of daily closes. */
        private const val INTRADAY_TTL_MS = 5 * 60 * 1000L
        /** Failures are retried sooner, but not on every rebuild — that would hammer a rate-limited API. */
        private const val FAILURE_TTL_MS = 2 * 60 * 1000L
    }
}
