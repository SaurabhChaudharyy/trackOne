package app.trackone.utils

/**
 * How a selection pill grows and fades, copied from the bottom tabs' indicator (Material's
 * NavigationBarItemView) so anything else that selects with a pill moves the same way. Progress runs
 * 0 (hidden) to 1 (shown); [alpha] also needs the [target] it is heading to, because a pill fades in
 * at the start of showing but out at the start of hiding.
 */
object IndicatorMotion {
    private const val SCALE_X_HIDDEN = 0.4f
    private const val ALPHA_FRACTION = 0.2f

    fun scaleX(progress: Float): Float = SCALE_X_HIDDEN + (1f - SCALE_X_HIDDEN) * progress

    fun alpha(progress: Float, target: Float): Float {
        val start = if (target == 0f) 1f - ALPHA_FRACTION else 0f
        val end = if (target == 0f) 1f else ALPHA_FRACTION
        return ((progress - start) / (end - start)).coerceIn(0f, 1f)
    }
}
