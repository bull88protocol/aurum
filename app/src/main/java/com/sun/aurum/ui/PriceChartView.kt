package com.sun.aurum.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.sun.aurum.R
import com.sun.aurum.model.IntradayPoint

class PriceChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var points: List<IntradayPoint> = emptyList()
        set(value) { field = value; invalidate() }

    var isUp: Boolean = true
        set(value) { field = value; invalidate() }

    private val dp = context.resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * dp
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * dp
        textAlign = Paint.Align.CENTER
    }
    private val priceLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * dp
        textAlign = Paint.Align.RIGHT
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 0.5f * dp
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f * dp, 4f * dp), 0f)
    }
    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * dp
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val upColor   = ContextCompat.getColor(context, R.color.up_green)
        val downColor = ContextCompat.getColor(context, R.color.down_red)
        val color = if (isUp) upColor else downColor

        linePaint.color       = color
        fillPaint.color       = Color.argb(40, Color.red(color), Color.green(color), Color.blue(color))
        labelPaint.color      = ContextCompat.getColor(context, R.color.text_secondary)
        priceLabelPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        gridPaint.color       = ContextCompat.getColor(context, R.color.chart_grid)
        noDataPaint.color     = ContextCompat.getColor(context, R.color.text_secondary)

        if (points.isEmpty()) {
            canvas.drawText("No data", width / 2f, height / 2f, noDataPaint)
            return
        }

        val padL = 4f * dp; val padR = 40f * dp
        val padT = 8f * dp; val padB = 20f * dp
        val chartW = width - padL - padR
        val chartH = height - padT - padB

        val prices = points.map { it.price }
        val minP = prices.min(); val maxP = prices.max()
        val range = if (maxP == minP) 1.0 else maxP - minP

        val n = points.size
        fun xOf(i: Int) = padL + i * chartW / (n - 1).coerceAtLeast(1)
        fun yOf(v: Double) = (padT + chartH * (1.0 - (v - minP) / range)).toFloat()

        // Horizontal grid lines
        val gridCount = 3
        for (g in 0..gridCount) {
            val gy = padT + g * chartH / gridCount
            canvas.drawLine(padL, gy, padL + chartW, gy, gridPaint)
            val priceAtGrid = maxP - g * range / gridCount
            canvas.drawText("$${String.format("%.2f", priceAtGrid)}", padL + chartW + padR - 2f * dp, gy + priceLabelPaint.textSize / 3, priceLabelPaint)
        }

        // Fill area under line
        val fillPath = Path()
        fillPath.moveTo(xOf(0), padT + chartH)
        fillPath.lineTo(xOf(0), yOf(prices[0]))
        for (i in 1 until n) fillPath.lineTo(xOf(i), yOf(prices[i]))
        fillPath.lineTo(xOf(n - 1), padT + chartH)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // Price line
        val linePath = Path()
        linePath.moveTo(xOf(0), yOf(prices[0]))
        for (i in 1 until n) linePath.lineTo(xOf(i), yOf(prices[i]))
        canvas.drawPath(linePath, linePaint)

        // X-axis time labels
        val labelY = padT + chartH + 14f * dp
        val labelInterval = maxOf(1, n / 5)
        for (i in points.indices) {
            if (i == 0 || i == n - 1 || i % labelInterval == 0) {
                val ms = points[i].timestampMs
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"))
                cal.timeInMillis = ms
                val label = String.format("%d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
                canvas.drawText(label, xOf(i), labelY, labelPaint)
            }
        }
    }
}
