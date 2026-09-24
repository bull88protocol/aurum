package com.sun.aurum.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The market fetch and the brief fetch run as two independent jobs writing one SymbolState, so
 * each must leave the other's fields alone. These are the two functions that guarantee that; get
 * them wrong and a refresh blanks the brief that has just been fetched, or vice versa.
 */
class BriefMergeTest {

    private val brief = GeminiResult(
        signal = "BULLISH", score = 72, description = "Real yields are falling.",
        keyFactors = listOf("TIPS down 12bp"),
        news = listOf(NewsItem("Gold jumps", "Bullion climbed.", "Reuters", "https://example.com/a", "2026-09-23")),
        yesterdayRecap = "Gold rose 2.26%.", todayOutlook = "Claims are the risk.",
        lastSessionLabel = "September 23", nextSessionLabel = "September 24",
    )

    private val quote = QuoteData(
        symbol = "GLD", price = 398.47, change = 8.81, changePct = 2.26,
        high = 399.10, low = 392.00, open = 393.00, previousClose = 389.66,
        volume = 1_000_000, marketState = "REGULAR", regularMarketPrice = 398.47,
    )

    private val withBrief = SymbolState("GLD").withBrief(brief, fromFeed = true, generatedUtc = "2026-09-23T22:54:58Z")

    @Test fun withBrief_fills_every_brief_field() {
        assertEquals("BULLISH", withBrief.geminiSignal)
        assertEquals(72, withBrief.geminiScore)
        assertEquals("Gold rose 2.26%.", withBrief.geminiYesterdayRecap)
        assertEquals("September 24", withBrief.nextSessionLabel)
        assertEquals(1, withBrief.news.size)
        assertTrue(withBrief.briefFromFeed)
        assertEquals("2026-09-23T22:54:58Z", withBrief.briefGeneratedUtc)
        assertFalse(withBrief.briefLoading)
    }

    @Test fun withBrief_leaves_the_market_data_alone() {
        val market = SymbolState("GLD", quote = quote, lastUpdated = 123L)
        val merged = market.withBrief(brief)
        assertEquals(quote, merged.quote)
        assertEquals(123L, merged.lastUpdated)
    }

    @Test fun a_null_brief_clears_the_fields_and_the_provenance_with_them() {
        val cleared = withBrief.withBrief(null, fromFeed = true, generatedUtc = "2026-09-23T22:54:58Z")
        assertNull(cleared.geminiSignal)
        assertEquals(emptyList<NewsItem>(), cleared.news)
        // Provenance describes a brief; with no brief there is nothing to date or attribute.
        assertFalse(cleared.briefFromFeed)
        assertNull(cleared.briefGeneratedUtc)
    }

    @Test fun carryingBriefFrom_keeps_the_brief_a_market_refresh_would_have_wiped() {
        // This is the regression the split could introduce: fetchAll builds a fresh SymbolState
        // with no brief fields, and assigning it straight into state would blank a brief already
        // on screen.
        val fresh = SymbolState("GLD", quote = quote, lastUpdated = 999L)
        val merged = fresh.carryingBriefFrom(withBrief)
        assertEquals("BULLISH", merged.geminiSignal)
        assertEquals(1, merged.news.size)
        assertTrue(merged.briefFromFeed)
        assertEquals("2026-09-23T22:54:58Z", merged.briefGeneratedUtc)
        // ...while taking the new market data.
        assertEquals(999L, merged.lastUpdated)
        assertEquals(quote, merged.quote)
    }

    @Test fun carryingBriefFrom_carries_the_loading_flag_so_the_indicator_survives_a_refresh() {
        val loading = SymbolState("GLD", briefLoading = true)
        assertTrue(SymbolState("GLD").carryingBriefFrom(loading).briefLoading)
    }

    @Test fun carryingBriefFrom_nothing_is_a_no_op() {
        val fresh = SymbolState("GLD", quote = quote)
        assertEquals(fresh, fresh.carryingBriefFrom(null))
    }
}
