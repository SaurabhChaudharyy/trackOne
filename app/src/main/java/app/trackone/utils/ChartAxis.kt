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
}
