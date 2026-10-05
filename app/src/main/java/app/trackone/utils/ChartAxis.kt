package app.trackone.utils

/**
 * Axis bounds for a value-over-time line. A portfolio that moves a couple of percent needs an axis
 * that hugs its range: pinned to zero, the line is a flat stroke at the top and every label is
 * noise. A little padding keeps the line off the edges, and a flat series still gets a visible axis.
 */
object ChartAxis {
    private const val SWING_PADDING = 0.15
    private const val MIN_PADDING_OF_VALUE = 0.01   // 1% of the value when there is no swing at all

    fun bounds(min: Double, max: Double): Pair<Double, Double> {
        val swing = max - min
        val pad = if (swing > 0.0) swing * SWING_PADDING else kotlin.math.abs(max) * MIN_PADDING_OF_VALUE
        val safePad = if (pad > 0.0) pad else 1.0      // an all-zero series
        return (min - safePad) to (max + safePad)
    }

    /** A value axis whose ends are gridlines: [count] labels from [lo] to [hi], [step] apart. */
    data class Ticks(val lo: Double, val hi: Double, val step: Double) {
        val count: Int get() = kotlin.math.round((hi - lo) / step).toInt() + 1
    }

    /**
     * Round gridlines (1, 2, 2.5 or 5 x 10^n apart) with the lowest and highest labels exactly on
     * the chart's bottom and top edges, so the area fill ends at the bottom gridline instead of
     * spilling below the last label. Picks the finest step that fits in [maxCount] labels, which
     * keeps the band of empty chart above and below the line as thin as possible.
     */
    fun ticks(min: Double, max: Double, maxCount: Int = 6): Ticks {
        val swing = max - min
        val pad = (if (swing > 0.0) swing * TICK_PADDING else kotlin.math.abs(max) * MIN_PADDING_OF_VALUE)
            .takeIf { it > 0.0 } ?: 1.0
        val lo = min - pad
        val hi = max + pad
        var magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10((hi - lo) / (maxCount - 1))))
        while (true) {
            for (nice in NICE_STEPS) {
                val step = nice * magnitude
                val t = Ticks(kotlin.math.floor(lo / step) * step, kotlin.math.ceil(hi / step) * step, step)
                if (t.count <= maxCount) return t
            }
            magnitude *= 10
        }
    }

    private const val TICK_PADDING = 0.05     // snapping to a round gridline adds the rest
    private val NICE_STEPS = listOf(1.0, 2.0, 2.5, 5.0)

    private const val MS_PER_HOUR = 60L * 60 * 1000
    private const val SINGLE_SESSION_MAX_HOURS = 18L
    private const val DAY_AND_MONTH_MAX_DAYS = 200L

    /**
     * The date pattern for the time axis. A single intraday session reads as times of day; one that
     * spans a night or a weekend (India's session plus the US one) needs the date as well. Daily
     * ranges read day and month until they are long enough that "Sep 25" would be mistaken for a day.
     */
    fun labelPattern(spanMs: Long, intraday: Boolean): String = when {
        intraday -> if (spanMs < SINGLE_SESSION_MAX_HOURS * MS_PER_HOUR) "h:mm a" else "d MMM, h:mm a"
        spanMs < DAY_AND_MONTH_MAX_DAYS * 24 * MS_PER_HOUR -> "d MMM"
        else -> "MMM ''yy"
    }
}
