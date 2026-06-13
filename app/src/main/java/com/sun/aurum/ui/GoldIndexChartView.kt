package com.sun.aurum.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.sun.aurum.R
import com.sun.aurum.model.DailyIndexPoint
import java.text.SimpleDateFormat
import java.util.*

class GoldIndexChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var points: List<DailyIndexPoint> = emptyList()
        set(value) { field = value; invalidate() }

    private val dp = context.resources.displayMetrics.density

    // Golden line color
    private val goldColor = Color.parseColor("#FFB300")
    private val gold70   = Color.argb(30, 38, 166, 154)   // green tint zone >70
    private val gold45   = Color.argb(20, 255, 167, 38)   // amber tint zone 45-70
    private val goldBear = Color.argb(20, 239, 83, 80)    // red tint zone <45

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * dp; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FFB300")
    }
    private val fillPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(35, 255, 179, 0)
    }
    private val zonePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 0.5f * dp; style = Paint.Style.STROKE
        color = Color.argb(40, 128, 128, 128)
        pathEffect = DashPathEffect(floatArrayOf(4f * dp, 4f * dp), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * dp; textAlign = Paint.Align.CENTER
    }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * dp; textAlign = Paint.Align.RIGHT
    }
    private val noDataPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * dp; textAlign = Paint.Align.CENTER
    }

    private val monthFmt = SimpleDateFormat("MMM", Locale.US)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)
        labelPaint.color = textSecondary
        scorePaint.color = textSecondary
        noDataPaint.color = textSecondary

        if (points.isEmpty()) {
            canvas.drawText("No index data", width / 2f, height / 2f, noDataPaint)
            return
        }

        val padL = 4f * dp; val padR = 32f * dp
        val padT = 8f * dp; val padB = 18f * dp
        val chartW = width - padL - padR
        val chartH = height - padT - padB

        // Fixed Y scale: 0-100
        fun yOf(score: Float) = (padT + chartH * (1f - score / 100f))
        val n = points.size
        fun xOf(i: Int) = padL + i * chartW / (n - 1).coerceAtLeast(1)

        // Zone fills
        zonePaint.color = gold70
        canvas.drawRect(padL, yOf(100f), padL + chartW, yOf(70f), zonePaint)
        zonePaint.color = gold45
        canvas.drawRect(padL, yOf(70f), padL + chartW, yOf(45f), zonePaint)
        zonePaint.color = goldBear
        canvas.drawRect(padL, yOf(45f), padL + chartW, yOf(0f), zonePaint)

        // Horizontal grid lines at 0, 25, 50, 75, 100
        for (line in listOf(0, 25, 45, 50, 70, 75, 100)) {
            val gy = yOf(line.toFloat())
            canvas.drawLine(padL, gy, padL + chartW, gy, gridPaint)
            canvas.drawText("$line", padL + chartW + padR - 2f * dp, gy + scorePaint.textSize / 3, scorePaint)
        }

        // Fill area under line
        val fillPath = Path()
        fillPath.moveTo(xOf(0), yOf(0f))
        fillPath.lineTo(xOf(0), yOf(points[0].score))
        for (i in 1 until n) fillPath.lineTo(xOf(i), yOf(points[i].score))
        fillPath.lineTo(xOf(n - 1), yOf(0f))
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // Golden line
        val linePath = Path()
        linePath.moveTo(xOf(0), yOf(points[0].score))
        for (i in 1 until n) linePath.lineTo(xOf(i), yOf(points[i].score))
        canvas.drawPath(linePath, linePaint)

        // X-axis monthly labels
        val labelY = padT + chartH + 13f * dp
        var lastMonth = -1
        for (i in points.indices) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = points[i].dateMs
            val month = cal.get(Calendar.MONTH)
            if (month != lastMonth) {
                lastMonth = month
                canvas.drawText(monthFmt.format(Date(points[i].dateMs)), xOf(i), labelY, labelPaint)
            }
        }
    }
}
