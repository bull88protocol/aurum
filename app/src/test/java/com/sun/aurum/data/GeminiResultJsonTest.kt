package com.sun.aurum.data

import com.sun.aurum.model.GeminiResult
import com.sun.aurum.model.NewsItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brief wire format, which the disk cache and the hosted feed both use. The feed's generator
 * (.github/brief-feed/build_brief.py) writes these same field names, so a change here that is not
 * mirrored there silently blanks the AI Brief tab for every keyless user — hence the explicit
 * field-name test below.
 */
class GeminiResultJsonTest {

    private val brief = GeminiResult(
        signal = "BULLISH", score = 72,
        description = "Real yields are falling.",
        keyFactors = listOf("TIPS down 12bp", "DXY off 0.6%"),
        news = listOf(NewsItem("Gold jumps", "Bullion climbed.", "Reuters", "https://example.com/a", "2026-09-23")),
        yesterdayRecap = "Gold rose 2.26%.",
        todayOutlook = "Jobless claims are the risk.",
        lastSessionLabel = "September 23",
        nextSessionLabel = "September 24",
    )

    @Test fun round_trips_every_field() {
        assertEquals(brief, GeminiResultJson.decode(GeminiResultJson.encode(brief)))
    }

    @Test fun round_trips_the_optional_central_bank_score() {
        val withCb = brief.copy(goldCentralBankScore = 61)
        assertEquals(61, GeminiResultJson.decode(GeminiResultJson.encode(withCb)).goldCentralBankScore)
        assertNull(GeminiResultJson.decode(GeminiResultJson.encode(brief)).goldCentralBankScore)
    }

    @Test fun uses_the_field_names_the_feed_generator_writes() {
        val json = GeminiResultJson.encode(brief)
        assertEquals(
            setOf("sig", "score", "desc", "yr", "to", "lsl", "nsl", "kf", "news"),
            json.keys().asSequence().toSet(),
        )
        assertEquals(
            setOf("h", "s", "src", "url", "dt"),
            json.getJSONArray("news").getJSONObject(0).keys().asSequence().toSet(),
        )
    }

    @Test fun decodes_a_brief_with_missing_fields_to_safe_defaults() {
        val sparse = GeminiResultJson.decode(JSONObject("""{"desc": "Only a description."}"""))
        assertEquals("NEUTRAL", sparse.signal)
        assertEquals(50, sparse.score)
        assertEquals(emptyList<NewsItem>(), sparse.news)
        assertEquals(emptyList<String>(), sparse.keyFactors)
        assertEquals("", sparse.todayOutlook)
    }

    @Test fun usable_means_at_least_one_piece_of_prose() {
        assertTrue(GeminiResultJson.isUsable(brief))
        assertTrue(GeminiResultJson.isUsable(brief.copy(description = "", yesterdayRecap = "")))
        assertFalse(GeminiResultJson.isUsable(
            brief.copy(description = "", yesterdayRecap = "", todayOutlook = "")))
    }
}
