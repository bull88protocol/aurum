package com.sun.aurum.network

import com.sun.aurum.model.FredObs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Parsing and freshness rules for the hosted FRED feed (runs on the real org.json test dependency). */
class FredFeedClientTest {

    private val today = LocalDate.parse("2026-09-16")

    private fun feed(schema: Int = 1, series: String) = """{"schema": $schema, "generated_utc": "2026-09-16T21:30:00Z",
        "notice": "This product uses the FRED® API but is not endorsed or certified by the Federal Reserve Bank of St. Louis.",
        "series": {$series}}"""

    private val dfii10 = """"DFII10": {"dates": ["2026-09-14", "2026-09-15"], "values": [2.6, 2.62]}"""
    private val dgs2 = """"DGS2": {"dates": ["2026-09-14", "2026-09-15"], "values": [4.65, 4.67]}"""

    @Test fun parses_every_fresh_series() {
        val out = FredFeedClient.parse(feed(series = "$dfii10, $dgs2"), today)!!
        assertEquals(setOf("DFII10", "DGS2"), out.keys)
        assertEquals(listOf(FredObs("2026-09-14", 2.6), FredObs("2026-09-15", 2.62)), out["DFII10"])
    }

    @Test fun a_stale_series_is_dropped_and_the_rest_kept() {
        val stale = """"T10YIE": {"dates": ["2026-09-04", "2026-09-05"], "values": [2.3, 2.31]}"""
        val out = FredFeedClient.parse(feed(series = "$dfii10, $stale"), today)!!
        assertEquals(setOf("DFII10"), out.keys)
    }

    @Test fun ten_days_old_is_still_fresh_eleven_is_not() {
        fun one(date: String) = listOf(FredObs(date, 1.0))
        assertTrue(FredFeedClient.isUsable(one("2026-09-06"), today))
        assertFalse(FredFeedClient.isUsable(one("2026-09-05"), today))
    }

    @Test fun rejects_unordered_or_empty_series() {
        assertFalse(FredFeedClient.isUsable(emptyList(), today))
        val unordered = listOf(FredObs("2026-09-15", 1.0), FredObs("2026-09-14", 1.0))
        assertFalse(FredFeedClient.isUsable(unordered, today))
        val duplicate = listOf(FredObs("2026-09-15", 1.0), FredObs("2026-09-15", 1.0))
        assertFalse(FredFeedClient.isUsable(duplicate, today))
    }

    @Test fun a_series_with_mismatched_arrays_is_skipped() {
        val broken = """"DGS2": {"dates": ["2026-09-14", "2026-09-15"], "values": [4.65]}"""
        val out = FredFeedClient.parse(feed(series = "$dfii10, $broken"), today)!!
        assertEquals(setOf("DFII10"), out.keys)
    }

    @Test fun an_unknown_schema_or_garbage_reads_as_no_feed() {
        assertNull(FredFeedClient.parse(feed(schema = 2, series = dfii10), today))
        assertNull(FredFeedClient.parse("<html>rate limited</html>", today))
        assertNull(FredFeedClient.parse("""{"schema": 1}""", today))
    }
}
