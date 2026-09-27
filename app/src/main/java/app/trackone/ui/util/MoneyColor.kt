package app.trackone.ui.util

import androidx.annotation.ColorRes
import app.trackone.R
import kotlin.math.abs

/**
 * The one rule for colouring a money move: up is [R.color.gain], down is [R.color.loss], and a
 * move that shows as 0.00 is neutral grey rather than a gain. Callers colour the TEXT and its
 * arrow; money direction never gets a filled pill.
 */
object MoneyColor {

    /** Anything smaller than half a paisa or cent rounds to 0.00 on screen. */
    private const val FLAT = 0.005

    @ColorRes
    fun forChange(change: Double): Int = when {
        change.isNaN() || abs(change) < FLAT -> R.color.text_secondary
        change > 0 -> R.color.gain
        else -> R.color.loss
    }
}
