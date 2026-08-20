package com.sun.aurum.report

import com.sun.aurum.model.DailyIndexPoint
import com.sun.aurum.model.GoldComponentScore
import com.sun.aurum.model.SymbolState
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Colour band for a score — the renderer maps these onto the app's green / amber / red. */
enum class Band { GOOD, MID, BAD }

/**
 * One renderable element of the daily report.
 *
 * The report is assembled as a flat list of these by [GoldReportContent] (pure Kotlin) and painted
 * onto PDF pages by [GoldReportPdf] (Android canvas). Splitting the two means *what the report
 * says* is unit-testable on the JVM, with no device and no PdfDocument.
 */
sealed class Block {
    data class Title(val text: String, val subtitle: String, val meta: String) : Block()
    data class Section(val text: String) : Block()
    /** Big headline number, e.g. the Gold Index composite: "68 / 100" + "HOT". */
    data class Score(val value: String, val label: String, val band: Band, val caption: String) : Block()
    data class Stats(val pairs: List<Pair<String, String>>) : Block()
    /** A 0-100 component row with a bar. [fraction] null = unavailable (no bar drawn). */
    data class Meter(
        val name: String,
        val value: String,
        val label: String,
        val fraction: Float?,
        val detail: String,
    ) : Block()
    data class Para(val text: String) : Block()
    data class Labeled(val label: String, val text: String) : Block()
    data class Bullets(val items: List<String>) : Block()
    data class News(val headline: String, val summary: String, val attribution: String) : Block()
    data class Note(val text: String, val band: Band = Band.MID) : Block()
    data class Spark(val points: List<DailyIndexPoint>, val caption: String) : Block()
    data class Footnote(val text: String) : Block()
}

/**
 * Turns a finished daily fetch into the ordered contents of the PDF the 9 AM notification hands the
 * user. Mirrors what the app itself shows for gold — the Gold Index tab (spot conditions, component
 * breakdown, history, Forward Signal), the AI Brief tab and the News tab — so the PDF is the report,
 * not a summary of it. Sections that need a key the user hasn't added degrade to a one-line note
 * rather than vanishing silently.
 */
object GoldReportContent {

    /** The hero symbol. The Dollar tab is a separate instrument and is not part of the gold report. */
    const val GOLD = "GLD"

    private val ET: TimeZone = TimeZone.getTimeZone("America/New_York")

    private fun fmt(pattern: String, ms: Long) =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = ET }.format(Date(ms))

    /** `2026-08-09` — used for the filename, so a day's report sorts and dedupes naturally. */
    fun fileDate(atMs: Long): String = fmt("yyyy-MM-dd", atMs)

    fun fileName(atMs: Long): String = "Aurum88-Gold-Report-${fileDate(atMs)}.pdf"

    /** Notification title, e.g. "Gold Report · Aug 9". */
    fun notificationTitle(atMs: Long): String = "Gold Report · ${fmt("MMM d", atMs)}"

    /**
     * One-line glance summary for the notification — the numbers a user would otherwise open the
     * app to read. Falls back to a plain prompt when the fetch produced nothing usable.
     */
    fun notificationSummary(gold: SymbolState?): String {
        val parts = mutableListOf<String>()
        gold?.goldIndexReport?.let {
            parts += "Index ${round(it.compositeScore)}/100 ${it.compositeLabel}"
            parts += "Outlook ${it.forwardLabel}"
        }
        gold?.quote?.let { parts += "Gold ${money(it.price)} ${signedPct(it.changePct)}" }
        return if (parts.isEmpty()) "Today's gold report is ready" else parts.joinToString(" · ")
    }

    /** Expanded notification text: the summary plus the AI signal and the top headline. */
    fun notificationBigText(gold: SymbolState?): String {
        val lines = mutableListOf(notificationSummary(gold))
        gold?.geminiSignal?.takeIf { it.isNotBlank() }?.let { signal ->
            val score = gold.geminiScore?.let { " ($it/100)" } ?: ""
            lines += "AI brief: $signal$score"
        }
        gold?.news?.firstOrNull()?.let { lines += "Top story: ${it.headline}" }
        lines += "Tap to open the full PDF report."
        return lines.joinToString("\n")
    }

    /**
     * Builds the report body. [hasFredKey] / [hasGeminiKey] come from SecurePrefs so a missing
     * component can be explained as "needs a key" rather than "couldn't load" — the cached
     * [GoldComponentScore.keyRequired] flag doesn't survive a round-trip through DataCache.
     */
    fun build(
        states: Map<String, SymbolState>,
        atMs: Long,
        hasFredKey: Boolean,
        hasGeminiKey: Boolean,
    ): List<Block> {
        val gold = states[GOLD] ?: return emptyList()
        val blocks = mutableListOf<Block>()

        blocks += Block.Title(
            text     = "Daily Gold Report",
            subtitle = "Aurum88 Protocol · The Macro Pulse of Gold",
            meta     = "${fmt("EEEE, MMMM d, yyyy", atMs)} · generated ${fmt("h:mm a", atMs)} ET",
        )

        addQuote(blocks, gold)
        addGoldIndex(blocks, gold, hasFredKey)
        addForwardSignal(blocks, gold, hasFredKey)
        addAiBrief(blocks, gold, hasGeminiKey)
        addNews(blocks, gold, hasGeminiKey)

        blocks += Block.Footnote(
            "Generated on-device by Aurum88 Protocol from your own data keys. Market data via Yahoo " +
            "Finance and FRED; central-bank demand via the World Gold Council feed; AI sections are " +
            "model-generated and may be inaccurate or delayed. Informational only — not financial advice."
        )
        return blocks
    }

    // ── Sections ──────────────────────────────────────────────────────────────

    private fun addQuote(blocks: MutableList<Block>, gold: SymbolState) {
        val q = gold.quote ?: return
        blocks += Block.Section("Gold")
        val session = when (q.marketState) {
            "PRE"               -> "Pre-market (vs prev close ${money(q.previousClose)})"
            "POST", "POSTPOST"  -> "After hours (vs regular close ${money(q.regularMarketPrice)})"
            "CLOSED"            -> "Closed"
            else                -> "Regular session"
        }
        blocks += Block.Score(
            value   = money(q.price),
            label   = "${signed(q.change)} (${signedPct(q.changePct)})",
            band    = if (q.change >= 0) Band.GOOD else Band.BAD,
            caption = "GLD · ${session}",
        )
        blocks += Block.Stats(listOf(
            "Day high"   to money(q.high),
            "Day low"    to money(q.low),
            "Open"       to money(q.open),
            "Prev close" to money(q.previousClose),
            "Volume"     to NumberFormat.getNumberInstance(Locale.US).format(q.volume),
            "As of"      to "${fmt("MMM d, h:mm a", gold.lastUpdated)} ET",
        ))
    }

    private fun addGoldIndex(blocks: MutableList<Block>, gold: SymbolState, hasFredKey: Boolean) {
        val report = gold.goldIndexReport
        blocks += Block.Section("Gold Index — today's conditions")
        if (report == null) {
            blocks += Block.Note("The Gold Index couldn't be computed for this report. Open the app and pull to refresh.", Band.BAD)
            return
        }
        blocks += Block.Score(
            value   = "${round(report.compositeScore)} / 100",
            label   = report.compositeLabel,
            band    = bandOf(report.compositeLabel),
            caption = "A nowcast of gold's macro backdrop right now: real yields, the dollar, " +
                      "central-bank demand, inflation expectations and technicals, scored 0-100.",
        )
        missingNote(report.components, hasFredKey)?.let { blocks += it }
        if (report.historicalScores.isNotEmpty()) {
            val pts = report.historicalScores
            blocks += Block.Spark(pts, "Gold Index, ${fmt("MMM d, yyyy", pts.first().dateMs)} – ${fmt("MMM d, yyyy", pts.last().dateMs)} (${pts.size} sessions)")
        }
        for (c in report.components) blocks += meter(c)
    }

    private fun addForwardSignal(blocks: MutableList<Block>, gold: SymbolState, hasFredKey: Boolean) {
        val report = gold.goldIndexReport ?: return
        blocks += Block.Section("Forward Signal — 3-6 month outlook")
        blocks += Block.Score(
            value   = "${round(report.forwardScore)} / 100",
            label   = report.forwardLabel,
            band    = bandOf(report.forwardLabel),
            caption = "Where the macro backdrop points over the next quarter or two: 0.55 real-rate " +
                      "regime + 0.25 twelve-month trend + 0.20 Fed cycle. A different question from " +
                      "today's conditions above — the two can disagree.",
        )
        // research/VALIDATION_2026-07-10.md §5 — stretched spot conditions systematically degrade
        // forward outcomes, so carry the app's caution chip into the PDF. Informational, never an
        // override of the label.
        if (report.compositeScore >= 70f) {
            blocks += Block.Note(
                "Conditions stretched: after HOT readings gold averaged −0.3% over 3M (47% up) vs +3.2% base.",
                Band.MID,
            )
        }
        missingNote(report.forwardComponents, hasFredKey)?.let { blocks += it }
        for (c in report.forwardComponents) blocks += meter(c)
    }

    private fun addAiBrief(blocks: MutableList<Block>, gold: SymbolState, hasGeminiKey: Boolean) {
        blocks += Block.Section("AI Market Brief")
        val hasBrief = !gold.geminiDescription.isNullOrBlank() ||
                       !gold.geminiYesterdayRecap.isNullOrBlank() ||
                       !gold.geminiTodayOutlook.isNullOrBlank()
        if (!hasBrief) {
            blocks += Block.Note(
                if (hasGeminiKey) "No AI brief was available at generation time. Open the app and pull to refresh."
                else "Add a free Gemini key in Settings to include daily AI analysis in this report.",
            )
            return
        }
        val signal = gold.geminiSignal?.takeIf { it.isNotBlank() } ?: "NEUTRAL"
        blocks += Block.Score(
            value   = gold.geminiScore?.let { "$it / 100" } ?: "--",
            label   = signal,
            band    = bandOf(signal),
            caption = "Gemini's read of the current gold tape.",
        )
        gold.geminiDescription?.takeIf { it.isNotBlank() }?.let { blocks += Block.Para(it) }
        gold.geminiYesterdayRecap?.takeIf { it.isNotBlank() }?.let {
            val label = gold.lastSessionLabel?.takeIf { l -> l.isNotBlank() }?.let { l -> "${l.uppercase(Locale.US)} SESSION" }
                ?: "LAST SESSION"
            blocks += Block.Labeled(label, it)
        }
        gold.geminiTodayOutlook?.takeIf { it.isNotBlank() }?.let {
            val label = gold.nextSessionLabel?.takeIf { l -> l.isNotBlank() }?.let { l -> "${l.uppercase(Locale.US)} OUTLOOK" }
                ?: "NEXT SESSION OUTLOOK"
            blocks += Block.Labeled(label, it)
        }
        if (gold.geminiKeyFactors.isNotEmpty()) {
            blocks += Block.Labeled("KEY FACTORS", "")
            blocks += Block.Bullets(gold.geminiKeyFactors)
        }
    }

    private fun addNews(blocks: MutableList<Block>, gold: SymbolState, hasGeminiKey: Boolean) {
        blocks += Block.Section("News")
        if (gold.news.isEmpty()) {
            blocks += Block.Note(
                if (hasGeminiKey) "No headlines were available at generation time. Open the app and pull to refresh."
                else "Add a free Gemini key in Settings to include the day's gold headlines in this report.",
            )
            return
        }
        for (item in gold.news) {
            val attribution = buildString {
                append(item.source)
                if (item.date.isNotBlank()) append("  ·  ${item.date}")
                if (item.url.startsWith("http")) append("  ·  ${item.url}")
            }
            blocks += Block.News(item.headline, item.summary, attribution)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun meter(c: GoldComponentScore) = Block.Meter(
        name     = c.name,
        value    = if (c.available) "${round(c.score)}/100" else "N/A",
        label    = if (c.available) c.label else "",
        fraction = if (c.available) (c.score / 100f).coerceIn(0f, 1f) else null,
        detail   = c.detail,
    )

    /** Mirrors the app's Gold Index banner: "needs a key" stays distinct from "couldn't load". */
    private fun missingNote(components: List<GoldComponentScore>, hasFredKey: Boolean): Block.Note? {
        val unavailable = components.filter { !it.available }
        if (unavailable.isEmpty()) return null
        val (needKey, noData) = unavailable.partition { it.keyRequired || !hasFredKey }
        fun names(list: List<GoldComponentScore>) = list.joinToString(" · ") { it.name.substringBefore(" (") }
        val lines = buildList {
            if (needKey.isNotEmpty()) add("Add a free FRED key in Settings to score: ${names(needKey)}.")
            if (noData.isNotEmpty())  add("Couldn't load: ${names(noData)}. Open the app and pull to refresh.")
        }
        return Block.Note(lines.joinToString(" "), Band.MID)
    }

    /** Handles both vocabularies: conditions (HOT/MIXED/WEAK) and signals (BULLISH/NEUTRAL/BEARISH). */
    private fun bandOf(label: String) = when (label) {
        "HOT", "BULLISH"  -> Band.GOOD
        "WEAK", "BEARISH" -> Band.BAD
        else              -> Band.MID
    }

    private fun round(v: Float) = String.format(Locale.US, "%.0f", v)
    private fun money(v: Double) = "$" + String.format(Locale.US, "%,.2f", v)
    private fun signed(v: Double) = (if (v >= 0) "+" else "") + String.format(Locale.US, "%.2f", v)
    private fun signedPct(v: Double) = (if (v >= 0) "+" else "") + String.format(Locale.US, "%.2f", v) + "%"
}
