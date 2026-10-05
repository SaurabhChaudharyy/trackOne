package app.trackone.ui.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import app.trackone.utils.IndicatorMotion

/**
 * A quiet [tile] with an [ink] indicator on top that grows in and out like the bottom tabs'
 * selection indicator (see [IndicatorMotion]). [progress] 0 shows only the tile, 1 the whole
 * indicator. By default it is a fully rounded pill filling the bounds; with [underlinePx] above zero
 * it is a rounded bar of that thickness along the bottom edge instead.
 */
class SelectionPillDrawable(
    private val tile: Drawable,
    @ColorInt ink: Int,
    private val underlinePx: Float = 0f
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val pill = RectF()

    var progress = 0f
        private set
    var target = 0f
        private set

    fun setProgress(progress: Float, target: Float) {
        this.progress = progress
        this.target = target
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        tile.bounds = bounds
    }

    override fun draw(canvas: Canvas) {
        tile.draw(canvas)
        val alpha = IndicatorMotion.alpha(progress, target)
        if (alpha <= 0f) return
        val b = bounds
        pill.set(b)
        if (underlinePx > 0f) pill.top = pill.bottom - underlinePx
        paint.alpha = (alpha * 255).toInt()
        val save = canvas.save()
        canvas.scale(IndicatorMotion.scaleX(progress), 1f, pill.centerX(), pill.centerY())
        canvas.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { tile.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { tile.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
