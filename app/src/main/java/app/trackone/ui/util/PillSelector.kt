package app.trackone.ui.util

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils

/**
 * Keeps one selected item among several, drawing the selection as a [SelectionPillDrawable] that
 * grows in on the new item and shrinks out of the old one with the bottom tabs' own motion
 * ([MotionSpec]). The label colour blends with the same progress. The first state an item gets, and
 * any item added later, is set at once: only a change the user makes animates.
 *
 * [K] identifies an item (a range, a tab id). [newIndicator] makes each item's drawable.
 */
class PillSelector<K : Any>(
    private val context: Context,
    @ColorInt private val labelOff: Int,
    @ColorInt private val labelOn: Int,
    private val newIndicator: () -> SelectionPillDrawable
) {
    private class Item(val host: View, val label: TextView, val indicator: SelectionPillDrawable) {
        var fresh = true
        var animator: ValueAnimator? = null
    }

    private val items = LinkedHashMap<K, Item>()

    /** Starts managing [host] (which gets the indicator as its background) and its [label]. */
    fun add(key: K, host: View, label: TextView) {
        val existing = items[key]
        if (existing != null && existing.host === host && host.background === existing.indicator) return
        existing?.animator?.cancel()
        val indicator = newIndicator()
        host.background = indicator
        items[key] = Item(host, label, indicator)
    }

    /** Stops managing every item whose key is not in [keys]. */
    fun retainOnly(keys: Set<K>) {
        val gone = items.keys - keys
        gone.forEach { items.remove(it)?.animator?.cancel() }
    }

    /** Makes [selected] the selected item, or none when it is null or unknown. */
    fun select(selected: K?) {
        items.forEach { (key, item) ->
            val target = if (key == selected) 1f else 0f
            item.host.isSelected = key == selected
            item.animator?.cancel()
            item.animator = null
            when {
                item.fresh -> { item.fresh = false; show(item, target, target) }
                item.indicator.progress == target -> show(item, target, target)
                else -> item.animator = ValueAnimator.ofFloat(item.indicator.progress, target).apply {
                    duration = MotionSpec.selectionDuration(context)
                    interpolator = MotionSpec.interpolator(context)
                    addUpdateListener { show(item, it.animatedValue as Float, target) }
                    start()
                }
            }
        }
    }

    /** Stops every animation in flight; call when the views are going away. */
    fun release() {
        items.values.forEach { it.animator?.cancel() }
        items.clear()
    }

    private fun show(item: Item, progress: Float, target: Float) {
        item.indicator.setProgress(progress, target)
        item.label.setTextColor(ColorUtils.blendARGB(labelOff, labelOn, progress))
    }
}
