package app.trackone.utils

/** One closing price, in INR per unit, on a calendar day (days counted in IST — see [PortfolioHistory.epochDayIst]). */
data class DailyClose(val epochDay: Long, val priceInr: Double)

/**
 * A holding as the history chart sees it. [closes] is null/empty for anything with no price
 * history — cash, bank, mutual funds, or a symbol that couldn't be looked up — which then
 * contributes its [currentValue] on every day instead.
 */
data class HistoryHolding(val quantity: Double, val currentValue: Double, val closes: List<DailyClose>?)

data class ValuePoint(val epochDay: Long, val valueInr: Double)

/**
 * "What would today's holdings have been worth on each day": quantity x that day's close, summed.
 * It assumes today's quantities were held throughout, so it says nothing about when anything was
 * actually bought — the screen has to label it that way.
 */
object PortfolioHistory {

    private const val IST_OFFSET_SECONDS = 19_800L
    private const val SECONDS_PER_DAY = 86_400L

    /** Indian, US and 24/7 markets all open on the same IST calendar day, so their bars line up. */
    fun epochDayIst(epochSeconds: Long): Long = Math.floorDiv(epochSeconds + IST_OFFSET_SECONDS, SECONDS_PER_DAY)

    fun epochDayIstToMillis(epochDay: Long): Long = (epochDay * SECONDS_PER_DAY - IST_OFFSET_SECONDS) * 1000L

    /**
     * One point per day on which at least one holding has a close. A holding with no close on a
     * given day (its market was shut) carries its previous close forward; before its first close it
     * uses that first close. Empty when no holding has any price history.
     */
    fun buildSeries(holdings: List<HistoryHolding>): List<ValuePoint> {
        val priced = holdings.filter { !it.closes.isNullOrEmpty() }
            .map { it to it.closes!!.sortedBy { c -> c.epochDay } }
        if (priced.isEmpty()) return emptyList()

        val days = priced.flatMap { (_, closes) -> closes.map { it.epochDay } }.distinct().sorted()
        val flatTotal = holdings.filter { it.closes.isNullOrEmpty() }.sumOf { it.currentValue }
        val cursors = IntArray(priced.size)   // index of the latest close on or before the current day

        return days.map { day ->
            var total = flatTotal
            priced.forEachIndexed { i, (holding, closes) ->
                while (cursors[i] + 1 < closes.size && closes[cursors[i] + 1].epochDay <= day) cursors[i]++
                total += holding.quantity * closes[cursors[i]].priceInr
            }
            ValuePoint(day, total)
        }
    }

    /** Ends the series on the live total (the number in the header) rather than on a stale close. */
    fun withLivePoint(series: List<ValuePoint>, todayEpochDay: Long, liveTotal: Double): List<ValuePoint> = when {
        series.isEmpty() -> series
        series.last().epochDay >= todayEpochDay -> series.dropLast(1) + ValuePoint(series.last().epochDay, liveTotal)
        else -> series + ValuePoint(todayEpochDay, liveTotal)
    }
}
