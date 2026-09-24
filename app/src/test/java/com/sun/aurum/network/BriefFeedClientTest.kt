package com.sun.aurum.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Parsing and freshness rules for the hosted AI brief feed (runs on the real org.json test dep). */
class BriefFeedClientTest {

    private val now: Instant = Instant.parse("2026-09-23T23:00:00Z")

    private val news = """
        {"h": "Gold jumps on soft inflation", "s": "Bullion climbed.", "src": "Reuters",
         "url": "https://example.com/a", "dt": "2026-09-23"},
        {"h": "Dollar slips", "s": "DXY fell 0.6%.", "src": "FT",
         "url": "https://example.com/b", "dt": "2026-09-22"}
    """.trimIndent()

    private fun feed(
        schema: Int = 1,
        generated: String = "2026-09-23T22:54:58Z",
        brief: String = """"sig": "BULLISH", "score": 72, "desc": "Real yields are falling.",
            "yr": "Gold rose 2.26%.", "to": "Jobless claims are the risk.",
            "lsl": "September 23", "nsl": "September 24",
            "kf": ["TIPS down 12bp", "DXY off 0.6%"], "news": [$news]""",
        quote: String = """, "quote_at_generation": {"price": 398.47, "changePct": 2.26}""",
    ) = """{"schema": $schema, "generated_utc": "$generated", "model": "gemini-2.5-flash",
        "symbol": "GLD"$quote, "brief": {$brief}}"""

    @Test fun parses_a_published_brief() {
        val feed = BriefFeedClient.parse(feed(), now)!!
        assertEquals("BULLISH", feed.result.signal)
        assertEquals(72, feed.result.score)
        assertEquals("September 23", feed.result.lastSessionLabel)
        assertEquals(listOf("TIPS down 12bp", "DXY off 0.6%"), feed.result.keyFactors)
        assertEquals("2026-09-23T22:54:58Z", feed.generatedUtc)
        assertEquals(398.47, feed.anchorPrice!!, 1e-9)
    }

    @Test fun carries_the_news_items_through() {
        val news = BriefFeedClient.parse(feed(), now)!!.result.news
        assertEquals(2, news.size)
        assertEquals("Gold jumps on soft inflation", news[0].headline)
        assertEquals("https://example.com/a", news[0].url)
        assertEquals("Reuters", news[0].source)
    }

    @Test fun an_unknown_schema_is_refused() {
        assertNull(BriefFeedClient.parse(feed(schema = 2), now))
    }

    @Test fun malformed_json_is_refused_rather_than_thrown() {
        assertNull(BriefFeedClient.parse("not json at all", now))
        assertNull(BriefFeedClient.parse("""{"schema": 1}""", now))
    }

    @Test fun a_brief_with_no_prose_is_refused() {
        // All three prose fields empty: the tab would render a signal chip over nothing.
        val empty = """"sig": "NEUTRAL", "score": 50, "desc": "", "yr": "", "to": "", "kf": []"""
        assertNull(BriefFeedClient.parse(feed(brief = empty), now))
    }

    @Test fun a_feed_with_no_quote_still_parses_without_an_anchor() {
        val feed = BriefFeedClient.parse(feed(quote = ""), now)!!
        assertNull(feed.anchorPrice)
        assertEquals("BULLISH", feed.result.signal)
    }

    @Test fun twelve_hours_old_is_stale() {
        assertTrue(BriefFeedClient.isFresh("2026-09-23T11:30:00Z", now))   // 11h30m
        assertFalse(BriefFeedClient.isFresh("2026-09-23T10:30:00Z", now))  // 12h30m
        assertNull(BriefFeedClient.parse(feed(generated = "2026-09-23T10:30:00Z"), now))
    }

    @Test fun an_unparseable_or_missing_timestamp_is_not_fresh() {
        assertFalse(BriefFeedClient.isFresh(null, now))
        assertFalse(BriefFeedClient.isFresh("", now))
        assertFalse(BriefFeedClient.isFresh("yesterday evening", now))
    }

    @Test fun a_timestamp_far_in_the_future_is_not_fresh() {
        // A skewed clock on either end would otherwise read as fresh forever.
        assertFalse(BriefFeedClient.isFresh("2026-09-24T06:00:00Z", now))
        assertTrue(BriefFeedClient.isFresh("2026-09-23T23:30:00Z", now))   // small skew is fine
    }

    /**
     * The generator and this client are the two halves of one contract, written in different
     * languages, and nothing at build time links them. This fixture is real output from
     * .github/brief-feed/build_brief.py (run against .github/brief-feed/mock_server.py, with the
     * timestamp pinned). If a change to either side breaks the other, this is what catches it —
     * regenerate the fixture the same way when the schema genuinely changes.
     */
    @Test fun parses_real_output_from_the_feed_generator() {
        val json = javaClass.classLoader!!.getResourceAsStream("brief_daily_sample.json")!!
            .bufferedReader().readText()
        val feed = BriefFeedClient.parse(json, Instant.parse("2026-09-23T23:30:00Z"))!!
        assertEquals("BULLISH", feed.result.signal)
        assertEquals(72, feed.result.score)
        assertEquals(3, feed.result.news.size)           // the generator dropped the stale 4th item
        assertEquals(3, feed.result.keyFactors.size)
        assertTrue(feed.result.yesterdayRecap.isNotBlank())
        assertTrue(feed.result.todayOutlook.isNotBlank())
        assertEquals("September 23", feed.result.lastSessionLabel)
        assertEquals("September 24", feed.result.nextSessionLabel)
        assertEquals(398.47, feed.anchorPrice!!, 1e-9)
    }
}
