package app.trackone.ui.util

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A grid of small dots filling its bounds: the area under a line chart, as in the Marker design.
 * MPAndroidChart clips a fill drawable to the area under the line before drawing it, so the grid
 * only shows below the line. The grid is anchored to the bounds, so dots don't swim on redraw.
 *
 * One dot is rendered once into a small tile that a shader repeats, so a frame costs a single
 * rectangle fill. Drawing every dot with its own drawCircle call is thousands of calls per frame,
 * and the chart's sweep animation re-draws this on every frame of its 700 ms.
 */
class DotGridDrawable(
    color: Int,
    spacingPx: Float,
    radiusPx: Float,
) : Drawable() {

    private val tileSize = max(1, spacingPx.roundToInt())

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(dotTile(color, radiusPx), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val matrix = Matrix()

    private fun dotTile(color: Int, radiusPx: Float): Bitmap {
        val tile = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        Canvas(tile).drawCircle(tileSize / 2f, tileSize / 2f, radiusPx, dot)
        return tile
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        // Shift the repeating tile to the bounds' corner so the grid stays anchored to them.
        matrix.setTranslate(b.left.toFloat(), b.top.toFloat())
        paint.shader.setLocalMatrix(matrix)
        canvas.drawRect(b, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
