package app.trackone.ui.util

import android.widget.LinearLayout

/**
 * Layout rules for large system text. Rows built for normal text (a gain line with "Invested" beside
 * it, a fixed-width card) clip money when the user raises their text size: "Invested ₹14,73,158.29"
 * became "Invested ₹14". These keep the figures whole instead.
 */
object LargeText {
    private const val STACK_FROM_SCALE = 1.3f
    private const val MAX_CARD_GROWTH = 1.8f

    /** True when two figures that share a row at normal sizes no longer fit and should stack. */
    fun stackSideBySideRows(fontScale: Float): Boolean = fontScale >= STACK_FROM_SCALE

    /** A card of [baseDp] width, widened in step with the text (never narrower, and capped). */
    fun scaledWidth(baseDp: Float, fontScale: Float): Float = baseDp * fontScale.coerceIn(1f, MAX_CARD_GROWTH)

    /** Puts [row]'s children one under another, left-aligned, when the system text is large. */
    fun stackIfLarge(row: LinearLayout, fontScale: Float) {
        if (!stackSideBySideRows(fontScale)) return
        row.orientation = LinearLayout.VERTICAL
        row.gravity = android.view.Gravity.START
        // The spacer that pushed "Invested" to the right has nothing to push in a column.
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child !is android.widget.TextView && child !is LinearLayout) child.visibility = android.view.View.GONE
        }
        row.findViewWithTag<android.view.View>("invested")?.let {
            (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = (4 * row.resources.displayMetrics.density).toInt()
        }
    }
}
