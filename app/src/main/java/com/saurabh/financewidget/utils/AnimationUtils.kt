package com.saurabh.financewidget.utils

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

        val animator = ValueAnimator.ofFloat(from.toFloat(), to.toFloat()).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val value = (anim.animatedValue as Float).toDouble()
                text = format(value)
            }
        }
        tag = animator
        animator.start()
    }

    /**
     * Convenience overload: counts from 0 to [to].
     */
    fun TextView.animateNumberFromZero(
        to: Double,
        durationMs: Long = 600L,
        format: (Double) -> String
    ) = animateNumber(0.0, to, durationMs, format)
}
