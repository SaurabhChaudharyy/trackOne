package app.trackone.ui.util

import android.graphics.Canvas
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.interfaces.dataprovider.LineDataProvider
import com.github.mikephil.charting.renderer.LineChartRenderer
import com.github.mikephil.charting.utils.ViewPortHandler

/**
 * Draws the line (and its fill) only up to [reveal] of the chart's width, so a left-to-right sweep
 * moves a pixel at a time.
 *
 * MPAndroidChart's own animateX reveals by *data point*: `phaseX` only changes how many entries are
 * drawn. A 1-week chart has 6 points, so its sweep advanced in ~6 visible jumps of ~100 ms, which
 * looked like stutter, while a 260-point year chart looked smooth. Clipping by pixel is smooth
 * whatever the point count. Axes and grid stay visible; only the data is revealed.
 */
class RevealLineChartRenderer(
    chart: LineDataProvider,
    animator: ChartAnimator,
    viewPortHandler: ViewPortHandler
) : LineChartRenderer(chart, animator, viewPortHandler) {

    /** 0 = nothing drawn, 1 = the whole line. */
    var reveal: Float = 1f

    override fun drawData(c: Canvas) {
        if (reveal >= 1f) {
            super.drawData(c)
            return
        }
        c.save()
        c.clipRect(
            mViewPortHandler.contentLeft(),
            0f,
            mViewPortHandler.contentLeft() + mViewPortHandler.contentWidth() * reveal,
            c.height.toFloat()
        )
        super.drawData(c)
        c.restore()
    }
}
