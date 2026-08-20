package com.sun.aurum.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.sun.aurum.model.SymbolState
import java.io.File

/**
 * Paints the daily report ([GoldReportContent]) onto an A4 PDF using only `android.graphics` — no
 * new dependency, no WebView, and safe to run from the 9 AM WorkManager job with no Activity alive.
 *
 * The file lands in `getExternalFilesDir("reports")`, which [file_paths.xml] already exposes through
 * the app's FileProvider, so the notification can hand it straight to a PDF viewer.
 */
object GoldReportPdf {

    /** Reports are small (~30-60 KB); keep a week so yesterday's is still tappable. */
    private const val KEEP_REPORTS = 7

    // A4 at 72 dpi — the unit PdfDocument works in.
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 44f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN
    private const val CONTENT_BOTTOM = PAGE_H - 54f

    // Print palette: the app's colours, darkened where needed to stay legible on white paper.
    private const val INK    = 0xFF16130C.toInt()
    private const val MUTED  = 0xFF6B5B3A.toInt()
    private const val FAINT  = 0xFF9C8A66.toInt()
    private const val RULE   = 0xFFE4DCC8.toInt()
    private const val TRACK  = 0xFFEDE7D8.toInt()
    private const val GOLD   = 0xFF9A6E10.toInt()
    private const val GOOD   = 0xFF1B7A5A.toInt()
    private const val MID    = 0xFFB7791F.toInt()
    private const val BAD    = 0xFFC0392B.toInt()

    private val BOLD    = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val REGULAR = Typeface.SANS_SERIF

    private fun colorOf(band: Band) = when (band) {
        Band.GOOD -> GOOD
        Band.MID  -> MID
        Band.BAD  -> BAD
    }

    /**
     * Builds today's report and returns the file, or null if there was nothing to report (no gold
     * state at all) or the write failed. Safe to call repeatedly for the same day — it overwrites.
     */
    fun generate(
        context: Context,
        states: Map<String, SymbolState>,
        hasFredKey: Boolean,
        hasGeminiKey: Boolean,
        atMs: Long = System.currentTimeMillis(),
    ): File? {
        val blocks = GoldReportContent.build(states, atMs, hasFredKey, hasGeminiKey)
        if (blocks.isEmpty()) return null

        // Null when external storage is unavailable (e.g. ejected) — nowhere the FileProvider can
        // serve from, so there is no report to hand over.
        val base = context.getExternalFilesDir(null) ?: return null
        val dir = File(base, "reports")
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, GoldReportContent.fileName(atMs))

        val doc = PdfDocument()
        val writer = Writer(doc, "Aurum88 Protocol · Daily Gold Report · ${GoldReportContent.fileDate(atMs)}")
        try {
            writer.begin()
            for (block in blocks) draw(writer, block)
            writer.finish()
            file.outputStream().use { doc.writeTo(it) }
        } catch (e: Exception) {
            runCatching { writer.finish() }
            runCatching { file.delete() }
            return null
        } finally {
            doc.close()
        }
        prune(dir)
        return file
    }

    /** Keeps the newest [KEEP_REPORTS] reports so the folder can't grow without bound. */
    private fun prune(dir: File) {
        runCatching {
            dir.listFiles { f -> f.name.startsWith("Aurum88-Gold-Report-") && f.name.endsWith(".pdf") }
                ?.sortedByDescending { it.name }
                ?.drop(KEEP_REPORTS)
                ?.forEach { it.delete() }
        }
    }

    // ── Block rendering ───────────────────────────────────────────────────────

    private fun draw(w: Writer, block: Block) {
        when (block) {
            is Block.Title    -> drawTitle(w, block)
            is Block.Section  -> drawSection(w, block)
            is Block.Score    -> drawScore(w, block)
            is Block.Stats    -> drawStats(w, block)
            is Block.Meter    -> drawMeter(w, block)
            is Block.Para     -> { w.paragraph(block.text, w.body, MARGIN, CONTENT_W); w.y += 8f }
            is Block.Labeled  -> drawLabeled(w, block)
            is Block.Bullets  -> drawBullets(w, block)
            is Block.News     -> drawNews(w, block)
            is Block.Note     -> drawNote(w, block)
            is Block.Spark    -> drawSpark(w, block)
            is Block.Footnote -> drawFootnote(w, block)
        }
    }

    private fun drawTitle(w: Writer, b: Block.Title) {
        w.canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 6f, w.fill(GOLD))
        w.y += 14f
        w.line(b.text, w.paint(24f, BOLD, INK))
        w.y += 4f
        w.line(b.subtitle, w.paint(10.5f, REGULAR, GOLD))
        w.y += 2f
        w.line(b.meta, w.paint(9f, REGULAR, MUTED))
        w.y += 14f
        w.rule()
        w.y += 6f
    }

    private fun drawSection(w: Writer, b: Block.Section) {
        // Reserve the header *and* the start of its content, so a section title can never be
        // orphaned alone at the foot of a page.
        w.need(120f)
        w.y += 16f
        w.line(b.text, w.paint(13.5f, BOLD, INK))
        w.y += 5f
        w.canvas.drawRect(MARGIN, w.y, MARGIN + 46f, w.y + 2f, w.fill(GOLD))
        w.y += 12f
    }

    private fun drawScore(w: Writer, b: Block.Score) {
        w.need(74f)
        val valuePaint = w.paint(25f, BOLD, INK)
        val labelPaint = w.paint(12f, BOLD, colorOf(b.band))
        val baseline = w.y + 24f
        w.canvas.drawText(b.value, MARGIN, baseline, valuePaint)
        val valueW = valuePaint.measureText(b.value)
        w.canvas.drawText(b.label, MARGIN + valueW + 12f, baseline, labelPaint)
        w.y = baseline + 8f
        if (b.caption.isNotBlank()) w.paragraph(b.caption, w.caption, MARGIN, CONTENT_W)
        w.y += 10f
    }

    private fun drawStats(w: Writer, b: Block.Stats) {
        val cols = 3
        val colW = CONTENT_W / cols
        val labelPaint = w.paint(8.5f, REGULAR, FAINT)
        val valuePaint = w.paint(11f, BOLD, INK)
        val rows = (b.pairs.size + cols - 1) / cols
        w.need(rows * 32f + 6f)
        for ((i, pair) in b.pairs.withIndex()) {
            if (i % cols == 0 && i > 0) w.y += 32f
            val x = MARGIN + (i % cols) * colW
            w.canvas.drawText(pair.first.uppercase(), x, w.y + 9f, labelPaint)
            w.canvas.drawText(pair.second, x, w.y + 24f, valuePaint)
        }
        w.y += 32f + 6f
    }

    private fun drawMeter(w: Writer, b: Block.Meter) {
        val namePaint   = w.paint(10f, BOLD, INK)
        val valuePaint  = w.paint(10f, BOLD, INK)
        val labelPaint  = w.paint(8.5f, REGULAR, FAINT)
        w.need(46f)
        val baseline = w.y + 9f
        w.canvas.drawText(b.name, MARGIN, baseline, namePaint)
        // Score right-aligned, with the BULLISH/NEUTRAL/BEARISH word to its left.
        val valueW = valuePaint.measureText(b.value)
        w.canvas.drawText(b.value, MARGIN + CONTENT_W - valueW, baseline, valuePaint)
        if (b.label.isNotBlank()) {
            val labelW = labelPaint.measureText(b.label)
            w.canvas.drawText(b.label, MARGIN + CONTENT_W - valueW - 10f - labelW, baseline, labelPaint)
        }
        w.y = baseline + 6f
        if (b.fraction != null) {
            val barColor = when {
                b.fraction >= 0.70f -> GOOD
                b.fraction >= 0.45f -> MID
                else                -> BAD
            }
            val top = w.y
            w.canvas.drawRoundRect(RectF(MARGIN, top, MARGIN + CONTENT_W, top + 5f), 2.5f, 2.5f, w.fill(TRACK))
            w.canvas.drawRoundRect(
                RectF(MARGIN, top, MARGIN + CONTENT_W * b.fraction, top + 5f), 2.5f, 2.5f, w.fill(barColor),
            )
            w.y += 5f + 4f
        }
        if (b.detail.isNotBlank()) w.paragraph(b.detail, w.detail, MARGIN, CONTENT_W)
        w.y += 10f
    }

    private fun drawLabeled(w: Writer, b: Block.Labeled) {
        w.need(30f)
        w.y += 4f
        w.line(b.label, w.paint(8.5f, BOLD, GOLD).apply { letterSpacing = 0.08f })
        w.y += 4f
        if (b.text.isNotBlank()) {
            w.paragraph(b.text, w.body, MARGIN, CONTENT_W)
            w.y += 8f
        }
    }

    private fun drawBullets(w: Writer, b: Block.Bullets) {
        for (item in b.items) {
            w.need(22f)
            w.canvas.drawText("•", MARGIN + 2f, w.y + 9.5f, w.paint(10f, REGULAR, GOLD))
            w.paragraph(item, w.body, MARGIN + 14f, CONTENT_W - 14f)
            w.y += 5f
        }
        w.y += 6f
    }

    private fun drawNews(w: Writer, b: Block.News) {
        w.need(56f)
        w.y += 4f
        val top = w.y
        val startPage = w.pageNo
        w.paragraph(b.headline, w.newsHead, MARGIN + 10f, CONTENT_W - 10f)
        w.y += 3f
        if (b.summary.isNotBlank()) {
            w.paragraph(b.summary, w.detail, MARGIN + 10f, CONTENT_W - 10f)
            w.y += 3f
        }
        w.paragraph(b.attribution, w.attribution, MARGIN + 10f, CONTENT_W - 10f)
        w.accentBar(top, startPage, 2f, RULE)
        w.y += 12f
    }

    private fun drawNote(w: Writer, b: Block.Note) {
        val accent = colorOf(b.band)
        w.need(34f)
        val top = w.y
        val startPage = w.pageNo
        w.paragraph(b.text, w.note.apply { color = accent }, MARGIN + 10f, CONTENT_W - 10f)
        w.accentBar(top, startPage, 2.5f, accent)
        w.y += 12f
    }

    private fun drawFootnote(w: Writer, b: Block.Footnote) {
        w.need(46f)
        w.y += 18f
        w.rule()
        w.y += 8f
        w.paragraph(b.text, w.footnote, MARGIN, CONTENT_W)
    }

    /**
     * The Gold Index history chart — the same 0-100 scale and WEAK / MIXED / HOT zone bands as the
     * in-app chart, so the PDF and the app read identically.
     */
    private fun drawSpark(w: Writer, b: Block.Spark) {
        val points = b.points
        if (points.isEmpty()) return
        val chartH = 108f
        w.need(chartH + 30f)
        val left = MARGIN
        val top = w.y
        val right = MARGIN + CONTENT_W - 26f   // gutter for the 0/50/100 scale labels
        val chartW = right - left

        fun yOf(score: Float) = top + chartH * (1f - score / 100f)
        fun xOf(i: Int) = left + i * chartW / (points.size - 1).coerceAtLeast(1)

        w.canvas.drawRect(left, yOf(100f), right, yOf(70f), w.fill(Color.argb(26, 27, 122, 90)))
        w.canvas.drawRect(left, yOf(70f), right, yOf(45f), w.fill(Color.argb(22, 183, 121, 31)))
        w.canvas.drawRect(left, yOf(45f), right, yOf(0f), w.fill(Color.argb(20, 192, 57, 43)))

        val gridPaint = w.paint(1f, REGULAR, RULE).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f }
        val scalePaint = w.paint(7.5f, REGULAR, FAINT)
        for (level in listOf(0, 45, 70, 100)) {
            val gy = yOf(level.toFloat())
            w.canvas.drawLine(left, gy, right, gy, gridPaint)
            w.canvas.drawText("$level", right + 5f, gy + 2.7f, scalePaint)
        }

        val fill = Path().apply {
            moveTo(xOf(0), yOf(0f))
            lineTo(xOf(0), yOf(points[0].score))
            for (i in 1 until points.size) lineTo(xOf(i), yOf(points[i].score))
            lineTo(xOf(points.size - 1), yOf(0f))
            close()
        }
        w.canvas.drawPath(fill, w.fill(Color.argb(38, 154, 110, 16)))

        val line = Path().apply {
            moveTo(xOf(0), yOf(points[0].score))
            for (i in 1 until points.size) lineTo(xOf(i), yOf(points[i].score))
        }
        w.canvas.drawPath(line, w.paint(1f, REGULAR, GOLD).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.6f
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        })
        // Mark today's reading.
        w.canvas.drawCircle(xOf(points.size - 1), yOf(points.last().score), 2.6f, w.fill(GOLD))

        w.y = top + chartH + 4f
        w.line(b.caption, w.paint(8f, REGULAR, FAINT))
        w.y += 10f
    }

    // ── Page writer ───────────────────────────────────────────────────────────

    /** A wrapped-text style: the paint plus the extra leading [StaticLayout] needs at build time. */
    private class Ink(val paint: TextPaint, val lineSpacing: Float) {
        var color: Int
            get() = paint.color
            set(value) { paint.color = value }
    }

    /**
     * Cursor over the document: owns the current page, the y position, and the page breaks. Text
     * that overflows a page is split line-by-line rather than clipped ([paragraph]).
     */
    private class Writer(private val doc: PdfDocument, private val runningHead: String) {

        private var page: PdfDocument.Page? = null
        var pageNo = 0
            private set
        lateinit var canvas: Canvas
            private set
        var y = 0f

        val body        = ink(10f, REGULAR, INK, 3f)
        val caption     = ink(9f, REGULAR, MUTED, 2f)
        val detail      = ink(9f, REGULAR, MUTED, 2f)
        val note        = ink(9.5f, REGULAR, MID, 2f)
        val newsHead    = ink(11f, BOLD, INK, 2f)
        val attribution = ink(8f, REGULAR, FAINT, 2f)
        val footnote    = ink(8f, REGULAR, FAINT, 2f)

        fun begin() = startPage()

        fun paint(size: Float, face: Typeface, color: Int): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; typeface = face; this.color = color
            }

        fun fill(color: Int): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

        /**
         * Draws the left-edge accent for a block that began at [top] on page [startPage]. Skipped
         * when the block flowed onto a later page — [top] belongs to the page before, so painting it
         * at the current cursor would strike a bar through unrelated text.
         */
        fun accentBar(top: Float, startPage: Int, width: Float, color: Int) {
            if (pageNo != startPage || y <= top) return
            canvas.drawRect(MARGIN, top, MARGIN + width, y, fill(color))
        }

        /** Draws one non-wrapping line at the left margin and advances past it. */
        fun line(text: String, paint: Paint) {
            need(paint.textSize + 6f)
            canvas.drawText(text, MARGIN, y + paint.textSize, paint)
            y += paint.textSize + 2f
        }

        fun rule() {
            need(2f)
            canvas.drawRect(MARGIN, y, MARGIN + CONTENT_W, y + 0.8f, fill(RULE))
            y += 0.8f
        }

        /** Breaks to a new page unless [h] still fits below the cursor. */
        fun need(h: Float) {
            if (y + h > CONTENT_BOTTOM) newPage()
        }

        /**
         * Draws wrapped text at [x] within [width], flowing across page breaks. Lines are emitted in
         * whole-line chunks: measure how many fit, clip to those, then break and continue.
         */
        fun paragraph(text: String, ink: Ink, x: Float, width: Float) {
            if (text.isBlank()) return
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, ink.paint, width.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(ink.lineSpacing, 1f)
                .setIncludePad(false)
                .build()

            var start = 0
            while (start < layout.lineCount) {
                if (CONTENT_BOTTOM - y < ink.paint.textSize * 1.6f) { newPage(); continue }
                val topOfChunk = layout.getLineTop(start)
                var end = start
                while (end < layout.lineCount &&
                       layout.getLineBottom(end) - topOfChunk <= CONTENT_BOTTOM - y) end++
                if (end == start) end = start + 1   // a single line taller than the page: draw it anyway
                val bottomOfChunk = layout.getLineBottom(end - 1)

                canvas.save()
                canvas.translate(x, y - topOfChunk)
                canvas.clipRect(0f, topOfChunk.toFloat(), width, bottomOfChunk.toFloat())
                layout.draw(canvas)
                canvas.restore()

                y += (bottomOfChunk - topOfChunk).toFloat()
                start = end
                if (start < layout.lineCount) newPage()
            }
        }

        fun newPage() {
            endPage()
            startPage()
        }

        fun finish() = endPage()

        private fun startPage() {
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            page = p
            canvas = p.canvas
            canvas.drawColor(Color.WHITE)
            y = MARGIN
            if (pageNo > 1) {
                canvas.drawText(runningHead, MARGIN, y + 8f, paint(8f, REGULAR, FAINT))
                y += 14f
                canvas.drawRect(MARGIN, y, MARGIN + CONTENT_W, y + 0.8f, fill(RULE))
                y += 14f
            }
        }

        private fun endPage() {
            val p = page ?: return
            val footer = paint(8f, REGULAR, FAINT)
            val label = "Page $pageNo"
            p.canvas.drawText("Aurum88 Protocol · The Macro Pulse of Gold", MARGIN, PAGE_H - 32f, footer)
            p.canvas.drawText(label, MARGIN + CONTENT_W - footer.measureText(label), PAGE_H - 32f, footer)
            doc.finishPage(p)
            page = null
        }

        private fun ink(size: Float, face: Typeface, color: Int, lineSpacing: Float) = Ink(
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; typeface = face; this.color = color
            },
            lineSpacing,
        )
    }
}
