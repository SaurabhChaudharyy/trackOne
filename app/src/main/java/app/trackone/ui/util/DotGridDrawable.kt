package app.trackone.ui.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * A grid of small dots filling its bounds: the area under a line chart, as in the Marker design.
 * MPAndroidChart clips a fill drawable to the area under the line before drawing it, so the grid
 * only shows below the line. The grid is anchored to the bounds, so dots don't swim on redraw.
 */
class DotGridDrawable(
    color: Int,
    private val spacingPx: Float,
    private val radiusPx: Float,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    override fun draw(canvas: Canvas) {
        val b = bounds
        var y = b.top + spacingPx / 2
        while (y < b.bottom) {
            var x = b.left + spacingPx / 2
            while (x < b.right) {
                canvas.drawCircle(x, y, radiusPx, paint)
                x += spacingPx
            }
            y += spacingPx
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
