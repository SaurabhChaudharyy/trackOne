package app.trackone.utils

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView

object AnimationUtils {

    /**
     * Animates a [TextView] number from [from] to [to], formatting the
     * intermediate value on each frame using [format].
     *
     * @param from        Starting numeric value (use 0.0 for "count-up from zero").
     * @param to          Target numeric value.
     * @param durationMs  Animation duration in ms (default 600ms).
     * @param format      How to convert the animated Double to a display String.
     *                    Placed last so Kotlin trailing-lambda syntax works cleanly.
     */
    fun TextView.animateNumber(
        from: Double,
        to: Double,
        durationMs: Long = 600L,
        format: (Double) -> String
    ) {
        // Cancel any running animator stored on this view
        (tag as? ValueAnimator)?.cancel()

        // Animate the 0..1 fraction (Float is fine for that) and do the money arithmetic in
        // Double: animating the amount itself in Float capped it at ~7 significant digits, so a
        // total like 738439.70 finished as 738439.6875 and disagreed with other screens.
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                text = format(interpolate(from, to, anim.animatedFraction))
            }
        }
        tag = animator
        animator.start()
    }

    /** Exactly [to] on the last frame (fraction 1), otherwise a straight-line blend. */
    internal fun interpolate(from: Double, to: Double, fraction: Float): Double =
        if (fraction >= 1f) to else from + (to - from) * fraction

    /**
     * Convenience overload: counts from 0 to [to].
     */
    fun TextView.animateNumberFromZero(
        to: Double,
        durationMs: Long = 600L,
        format: (Double) -> String
    ) = animateNumber(0.0, to, durationMs, format)
}
