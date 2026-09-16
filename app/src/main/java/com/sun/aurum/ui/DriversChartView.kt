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

/**
 * History of the 20-day drivers read on a centred scale: -100 (headwind) to +100 (tailwind), with
 * the +/-30 bands where the label leaves MIXED. Same look as [GoldIndexChartView].
 */
class DriversChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var points: List<DailyIndexPoint> = emptyList()
        set(value) { field = value; invalidate() }

    private val dp = context.resources.displayMetrics.density

    private val tailwindZone = Color.argb(30, 38, 166, 154)   // >= +30
    private val headwindZone = Color.argb(24, 239, 83, 80)    // <= -30

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f * dp; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FFB300")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(35, 255, 179, 0)
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f * dp; style = Paint.Style.STROKE
        color = Color.argb(90, 128, 128, 128)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    // Gold's daily bars are US sessions, so label months in Eastern Time (as GoldIndexChartView does).
    private val etZone = TimeZone.getTimeZone("America/New_York")
    private val monthFmt = SimpleDateFormat("MMM", Locale.US).apply { timeZone = etZone }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)
        labelPaint.color = textSecondary
        scorePaint.color = textSecondary
        noDataPaint.color = textSecondary

        if (points.size < 2) {
            canvas.drawText("No history yet", width / 2f, height / 2f, noDataPaint)
            return
        }

        val padL = 4f * dp; val padR = 32f * dp
        val padT = 8f * dp; val padB = 18f * dp
        val chartW = width - padL - padR
        val chartH = height - padT - padB

        fun yOf(score: Float) = padT + chartH * (1f - (score.coerceIn(-100f, 100f) + 100f) / 200f)
        val n = points.size
        fun xOf(i: Int) = padL + i * chartW / (n - 1)

        zonePaint.color = tailwindZone
        canvas.drawRect(padL, yOf(100f), padL + chartW, yOf(30f), zonePaint)
        zonePaint.color = headwindZone
        canvas.drawRect(padL, yOf(-30f), padL + chartW, yOf(-100f), zonePaint)

        for (line in listOf(-100, -30, 30, 100)) {
            val gy = yOf(line.toFloat())
            canvas.drawLine(padL, gy, padL + chartW, gy, gridPaint)
            canvas.drawText(if (line > 0) "+$line" else "$line", padL + chartW + padR - 2f * dp, gy + scorePaint.textSize / 3, scorePaint)
        }
        val zeroY = yOf(0f)
        canvas.drawLine(padL, zeroY, padL + chartW, zeroY, zeroPaint)
        canvas.drawText("0", padL + chartW + padR - 2f * dp, zeroY + scorePaint.textSize / 3, scorePaint)

        // Fill between the zero line and the curve
        val fillPath = Path()
        fillPath.moveTo(xOf(0), zeroY)
        for (i in 0 until n) fillPath.lineTo(xOf(i), yOf(points[i].score))
        fillPath.lineTo(xOf(n - 1), zeroY)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        val linePath = Path()
        linePath.moveTo(xOf(0), yOf(points[0].score))
        for (i in 1 until n) linePath.lineTo(xOf(i), yOf(points[i].score))
        canvas.drawPath(linePath, linePaint)

        // X-axis monthly labels
        val labelY = padT + chartH + 13f * dp
        var lastMonth = -1
        val cal = Calendar.getInstance(etZone)
        for (i in points.indices) {
            cal.timeInMillis = points[i].dateMs
            val month = cal.get(Calendar.MONTH)
            if (month != lastMonth) {
                lastMonth = month
                canvas.drawText(monthFmt.format(Date(points[i].dateMs)), xOf(i), labelY, labelPaint)
            }
        }
    }
}
