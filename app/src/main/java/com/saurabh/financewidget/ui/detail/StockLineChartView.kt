package com.saurabh.financewidget.ui.detail

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StockLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var dataPoints: List<Float> = emptyList()
    private var timestamps: List<Long> = emptyList()
    private var isPositive: Boolean = true
    private var noDataText: String = "Loading chart data..."
    private var noDataTextColor: Int = Color.WHITE

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
        color = Color.parseColor("#2A2A2A")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3B3B3")
        textSize = 28f
    }

    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    private val animProgress = 1f

    fun setData(points: List<Float>, timestamps: List<Long>, isPositive: Boolean) {
        this.dataPoints = points
        this.timestamps = timestamps
        this.isPositive = isPositive
        val color = if (isPositive) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
        linePaint.color = color
        invalidate()
    }

    fun setNoDataText(text: String) {
        noDataText = text
        invalidate()
    }

    fun setNoDataTextColor(color: Int) {
        noDataTextColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (dataPoints.size < 2) {
            noDataPaint.color = noDataTextColor
            canvas.drawText(noDataText, width / 2f, height / 2f, noDataPaint)
            return
        }

        val paddingLeft = paddingLeft.toFloat() + 8f
        val paddingRight = paddingRight.toFloat() + 8f
        val paddingTop = paddingTop.toFloat() + 16f
        val paddingBottom = paddingBottom.toFloat() + 40f

        val chartWidth = width - paddingLeft - paddingRight
        val chartHeight = height - paddingTop - paddingBottom

        val minVal = dataPoints.min()
        val maxVal = dataPoints.max()
        val range = (maxVal - minVal).takeIf { it > 0f } ?: 1f

        val stepX = chartWidth / (dataPoints.size - 1)

        fun xAt(i: Int) = paddingLeft + i * stepX
        fun yAt(v: Float) = paddingTop + chartHeight - ((v - minVal) / range) * chartHeight

        // Draw horizontal grid lines (5 lines, evenly spaced)
        for (i in 0..4) {
            val y = paddingTop + (chartHeight / 4f) * i
            canvas.drawLine(paddingLeft, y, paddingLeft + chartWidth, y, gridPaint)
        }

        // Build path
        val path = Path()
        val fillPath = Path()

        path.moveTo(xAt(0), yAt(dataPoints[0]))
        fillPath.moveTo(xAt(0), paddingTop + chartHeight)
        fillPath.lineTo(xAt(0), yAt(dataPoints[0]))

        for (i in 1 until dataPoints.size) {
            val x0 = xAt(i - 1)
            val y0 = yAt(dataPoints[i - 1])
            val x1 = xAt(i)
            val y1 = yAt(dataPoints[i])
            val cx = (x0 + x1) / 2f
            path.cubicTo(cx, y0, cx, y1, x1, y1)
            fillPath.cubicTo(cx, y0, cx, y1, x1, y1)
        }

        fillPath.lineTo(xAt(dataPoints.size - 1), paddingTop + chartHeight)
        fillPath.close()

        // Draw fill gradient
        val color = if (isPositive) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
        val shader = LinearGradient(
            0f, paddingTop, 0f, paddingTop + chartHeight,
            intArrayOf(Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)),
                Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.shader = shader
        canvas.drawPath(fillPath, fillPaint)

        // Draw line
        canvas.drawPath(path, linePaint)

        // Draw X-axis labels (first, middle, last)
        if (timestamps.isNotEmpty()) {
            // Use time format for intraday (span < 2 days), date for longer ranges
            val spanMs = (timestamps.last() - timestamps.first()) * 1000L
            val isIntraday = spanMs < 2 * 24 * 60 * 60 * 1000L
            val sdf = if (isIntraday)
                SimpleDateFormat("h:mm a", Locale.getDefault())
            else
                SimpleDateFormat("MMM dd", Locale.getDefault())

            val labelIndices = listOf(0, timestamps.size / 2, timestamps.size - 1)
            labelIndices.forEach { idx ->
                if (idx < timestamps.size) {
                    val label = sdf.format(Date(timestamps[idx] * 1000))
                    val x = xAt(idx).coerceIn(paddingLeft, paddingLeft + chartWidth - 60f)
                    canvas.drawText(label, x, height - paddingBottom + 30f, labelPaint)
                }
            }
        }
    }
}
