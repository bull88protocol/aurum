package com.sun.aurum.report

import com.sun.aurum.model.DailyIndexPoint
import com.sun.aurum.model.GoldComponentScore
import com.sun.aurum.model.GoldIndexReport
import com.sun.aurum.model.NewsItem
import com.sun.aurum.model.QuoteData
import com.sun.aurum.model.SymbolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PDF's *contents* — what the daily notification actually hands the user. Rendering needs a
 * device; the assembly here doesn't, so the parts that can silently go missing (a section dropped
 * because a key is absent, a caution that should carry over from the app) are pinned down on the JVM.
 */
class GoldReportContentTest {

    // 2026-08-09 13:30 UTC = 09:30 ET, i.e. just after a normal 9 AM run.
    private val atMs = 1786282200000L

    private fun quote() = QuoteData(
        symbol = "GLD", price = 312.40, change = 2.55, changePct = 0.82,
        high = 313.90, low = 309.10, open = 310.00, previousClose = 309.85,
        volume = 7_450_000L, marketState = "PRE", regularMarketPrice = 309.85,
    )

    private fun component(name: String, score: Float, available: Boolean = true, keyRequired: Boolean = false) =
        GoldComponentScore(name, score, if (score >= 70f) "BULLISH" else "NEUTRAL", "$name detail", available, keyRequired)

    private fun indexReport(
        composite: Float = 62f,
        components: List<GoldComponentScore> = listOf(component("Real Yields", 71f), component("USD", 55f)),
        forwardComponents: List<GoldComponentScore> = listOf(component("Real-Rate Regime", 80f)),
    ) = GoldIndexReport(
        compositeScore = composite,
        compositeLabel = if (composite >= 70f) "HOT" else "MIXED",
        components = components,
        historicalScores = listOf(DailyIndexPoint(atMs - 86_400_000L, 60f), DailyIndexPoint(atMs, composite)),
        timestamp = atMs,
        forwardScore = 68f,
        forwardLabel = "BULLISH",
        forwardComponents = forwardComponents,
    )

    private fun goldState(
        report: GoldIndexReport? = indexReport(),
        news: List<NewsItem> = emptyList(),
        geminiDescription: String? = null,
    ) = SymbolState(
        symbol = "GLD",
        quote = quote(),
        goldIndexReport = report,
        news = news,
        lastUpdated = atMs,
        geminiSignal = if (geminiDescription != null) "BULLISH" else null,
        geminiScore = if (geminiDescription != null) 72 else null,
        geminiDescription = geminiDescription,
        geminiYesterdayRecap = if (geminiDescription != null) "Gold rose on a softer dollar." else null,
        geminiTodayOutlook = if (geminiDescription != null) "CPI print is the swing factor." else null,
        geminiKeyFactors = if (geminiDescription != null) listOf("Real yields easing", "CB demand steady") else emptyList(),
        lastSessionLabel = "August 8",
        nextSessionLabel = "August 9",
    )

    private fun build(
        gold: SymbolState? = goldState(),
        hasFredKey: Boolean = true,
        hasGeminiKey: Boolean = true,
    ) = GoldReportContent.build(
        states = if (gold == null) emptyMap() else mapOf("GLD" to gold),
        atMs = atMs,
        hasFredKey = hasFredKey,
        hasGeminiKey = hasGeminiKey,
    )

    private fun sections(blocks: List<Block>) = blocks.filterIsInstance<Block.Section>().map { it.text }

    private fun allText(blocks: List<Block>): String = blocks.joinToString("\n") { b ->
        when (b) {
            is Block.Title    -> "${b.text} ${b.subtitle} ${b.meta}"
            is Block.Section  -> b.text
            is Block.Score    -> "${b.value} ${b.label} ${b.caption}"
            is Block.Stats    -> b.pairs.joinToString(" ") { "${it.first}=${it.second}" }
            is Block.Meter    -> "${b.name} ${b.value} ${b.label} ${b.detail}"
            is Block.Para     -> b.text
            is Block.Labeled  -> "${b.label} ${b.text}"
            is Block.Bullets  -> b.items.joinToString(" ")
            is Block.News     -> "${b.headline} ${b.summary} ${b.attribution}"
            is Block.Note     -> b.text
            is Block.Spark    -> b.caption
            is Block.Footnote -> b.text
        }
    }

    // ── Structure ─────────────────────────────────────────────────────────────

    @Test
    fun `no gold state produces no report`() {
        assertTrue(build(gold = null).isEmpty())
    }

    @Test
    fun `full report carries every section the app shows for gold`() {
        val blocks = build(
            goldState(
                news = listOf(NewsItem("Gold breaks out", "Spot cleared resistance.", "Reuters", "https://x.test/a", "2026-08-08")),
                geminiDescription = "Constructive backdrop.",
            )
        )
        assertEquals(
            listOf("Gold", "Gold Index — today's conditions", "Forward Signal — 3-6 month outlook", "AI Market Brief", "News"),
            sections(blocks),
        )
        val title = blocks.first() as Block.Title
        assertTrue(title.meta.contains("Sunday, August 9, 2026"))
        assertTrue(title.meta.endsWith("ET"))
    }

    @Test
    fun `index and forward components each become a meter`() {
        val blocks = build()
        val meters = blocks.filterIsInstance<Block.Meter>().map { it.name }
        assertEquals(listOf("Real Yields", "USD", "Real-Rate Regime"), meters)
    }

    @Test
    fun `history chart is included with a dated caption`() {
        val spark = build().filterIsInstance<Block.Spark>().single()
        assertEquals(2, spark.points.size)
        assertTrue(spark.caption.contains("Aug 8, 2026"))
        assertTrue(spark.caption.contains("Aug 9, 2026"))
    }

    @Test
    fun `quote block reports the pre-market session against the previous close`() {
        val score = build().filterIsInstance<Block.Score>().first()
        assertEquals("$312.40", score.value)
        assertEquals("+2.55 (+0.82%)", score.label)
        assertEquals(Band.GOOD, score.band)
        assertTrue(score.caption.contains("Pre-market"))
        assertTrue(score.caption.contains("$309.85"))
    }

    // ── Key-gated sections ────────────────────────────────────────────────────

    @Test
    fun `AI brief and news appear when a Gemini key produced them`() {
        val blocks = build(
            goldState(
                news = listOf(NewsItem("Gold breaks out", "Spot cleared resistance.", "Reuters", "https://x.test/a", "2026-08-08")),
                geminiDescription = "Constructive backdrop.",
            )
        )
        val text = allText(blocks)
        assertTrue(text.contains("Constructive backdrop."))
        assertTrue(text.contains("AUGUST 8 SESSION"))
        assertTrue(text.contains("AUGUST 9 OUTLOOK"))
        assertTrue(text.contains("Real yields easing"))
        val news = blocks.filterIsInstance<Block.News>().single()
        assertEquals("Gold breaks out", news.headline)
        assertTrue(news.attribution.contains("Reuters"))
        assertTrue(news.attribution.contains("https://x.test/a"))
    }

    @Test
    fun `without a Gemini key the AI and news sections explain themselves instead of vanishing`() {
        val blocks = build(hasGeminiKey = false)
        assertTrue(sections(blocks).contains("AI Market Brief"))
        assertTrue(sections(blocks).contains("News"))
        val notes = blocks.filterIsInstance<Block.Note>().map { it.text }
        assertTrue(notes.any { it.contains("Gemini key") && it.contains("AI analysis") })
        assertTrue(notes.any { it.contains("Gemini key") && it.contains("headlines") })
    }

    @Test
    fun `a key-less component is reported as needing FRED, not as a load failure`() {
        val blocks = build(
            gold = goldState(indexReport(components = listOf(component("Real Yields", 0f, available = false)))),
            hasFredKey = false,
        )
        val note = blocks.filterIsInstance<Block.Note>().first { it.text.contains("Real Yields") }
        assertTrue(note.text.contains("FRED key"))
        assertFalse(note.text.contains("Couldn't load"))
    }

    @Test
    fun `an unavailable component with a key present is reported as a load failure`() {
        val blocks = build(
            gold = goldState(indexReport(components = listOf(component("USD", 0f, available = false)))),
            hasFredKey = true,
        )
        val note = blocks.filterIsInstance<Block.Note>().first { it.text.contains("USD") }
        assertTrue(note.text.contains("Couldn't load"))
        assertFalse(note.text.contains("FRED key"))
    }

    @Test
    fun `unavailable components get no bar`() {
        val blocks = build(gold = goldState(indexReport(components = listOf(component("USD", 0f, available = false)))))
        val meter = blocks.filterIsInstance<Block.Meter>().first { it.name == "USD" }
        assertNull(meter.fraction)
        assertEquals("N/A", meter.value)
    }

    // ── Research-backed caution ───────────────────────────────────────────────

    @Test
    fun `spot-HOT caution rides along with the forward signal`() {
        val hot = build(gold = goldState(indexReport(composite = 74f)))
        assertNotNull(hot.filterIsInstance<Block.Note>().firstOrNull { it.text.contains("Conditions stretched") })

        val mixed = build(gold = goldState(indexReport(composite = 62f)))
        assertNull(mixed.filterIsInstance<Block.Note>().firstOrNull { it.text.contains("Conditions stretched") })
    }

    // ── Notification surface ──────────────────────────────────────────────────

    @Test
    fun `notification summarises the numbers a user would otherwise open the app for`() {
        assertEquals("Gold Report · Aug 9", GoldReportContent.notificationTitle(atMs))
        assertEquals(
            "Index 62/100 MIXED · Outlook BULLISH · Gold $312.40 +0.82%",
            GoldReportContent.notificationSummary(goldState()),
        )
    }

    @Test
    fun `notification big text adds the AI signal and top headline`() {
        val big = GoldReportContent.notificationBigText(
            goldState(
                news = listOf(NewsItem("Gold breaks out", "Spot cleared resistance.", "Reuters", "", "2026-08-08")),
                geminiDescription = "Constructive backdrop.",
            )
        )
        assertTrue(big.contains("AI brief: BULLISH (72/100)"))
        assertTrue(big.contains("Top story: Gold breaks out"))
    }

    @Test
    fun `notification degrades to a prompt when the fetch produced nothing`() {
        assertEquals("Today's gold report is ready", GoldReportContent.notificationSummary(null))
    }

    @Test
    fun `filename is date-stamped so a day's report dedupes and sorts`() {
        assertEquals("Aurum88-Gold-Report-2026-08-09.pdf", GoldReportContent.fileName(atMs))
    }
}
