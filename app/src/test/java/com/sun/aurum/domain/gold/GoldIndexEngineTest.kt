package com.sun.aurum.domain.gold

import com.sun.aurum.model.Candle
import com.sun.aurum.network.FredClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pure-JVM tests for the Gold Index engine. The engine has no Android deps, so it runs as a
 * fast unit test. The headline guard here is P0-3: the CSV "full history" must stay DAILY —
 * the old `range=max` fetch silently returned monthly/quarterly bars.
 */
class GoldIndexEngineTest {

    private val ny = TimeZone.getTimeZone("America/New_York")
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = ny }

    /** n consecutive daily calendar dates (NY tz) as (epochMs at noon, "yyyy-MM-dd"). */
    private fun dailyDates(n: Int): List<Pair<Long, String>> {
        val cal = Calendar.getInstance(ny).apply {
            set(2016, Calendar.JANUARY, 1, 12, 0, 0); set(Calendar.MILLISECOND, 0)
        }
        return (0 until n).map {
            val pair = cal.timeInMillis to fmt.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            pair
        }
    }

    private fun candles(dates: List<Pair<Long, String>>, close: (Int) -> Double): List<Candle> =
        dates.mapIndexed { i, (ms, _) ->
            val c = close(i)
            Candle(open = c, high = c * 1.01, low = c * 0.99, close = c, volume = 1_000_000L, datetimeMs = ms)
        }

    private fun obs(dates: List<Pair<Long, String>>, value: (Int) -> Double): List<FredClient.Obs> =
        dates.mapIndexed { i, (_, s) -> FredClient.Obs(s, value(i)) }

    // ── toLabel thresholds ──────────────────────────────────────────────────
    @Test fun toLabel_thresholds() {
        assertEquals("BULLISH", GoldIndexEngine.toLabel(70f))
        assertEquals("NEUTRAL", GoldIndexEngine.toLabel(69.9f))
        assertEquals("NEUTRAL", GoldIndexEngine.toLabel(45f))
        assertEquals("BEARISH", GoldIndexEngine.toLabel(44.9f))
    }

    // ── scoreTechnical direction ────────────────────────────────────────────
    @Test fun scoreTechnical_uptrend_is_bullish() {
        val s = GoldIndexEngine.scoreTechnical((0 until 260).map { 100.0 + it })   // strictly rising
        assertTrue("uptrend should be bullish, was ${s.score}", s.score >= 70f)
        assertEquals("BULLISH", s.label)
    }

    @Test fun scoreTechnical_downtrend_is_bearish() {
        val s = GoldIndexEngine.scoreTechnical((0 until 260).map { 360.0 - it })   // strictly falling
        assertTrue("downtrend should be bearish, was ${s.score}", s.score < 45f)
        assertEquals("BEARISH", s.label)
    }

    // ── P0-3: CSV history stays DAILY (one row per day after a 50-bar warmup) ─
    @Test fun computeHistoricalFull_is_daily_and_well_formed() {
        val n = 300
        val dates = dailyDates(n)
        val gld = candles(dates) { 150.0 + 20.0 * Math.sin(it / 15.0) + it * 0.05 }
        val dxy = candles(dates) { 100.0 + 5.0 * Math.sin(it / 25.0) }
        val ry  = obs(dates) { 1.0 + 0.5 * Math.sin(it / 20.0) }
        val inf = obs(dates) { 2.2 + 0.2 * Math.sin(it / 18.0) }

        val rows = GoldIndexEngine.computeHistoricalFull(
            GoldIndexEngine.Inputs(gldCandles = gld, dxyCandles = dxy, realYield = ry, inflation = inf)
        )

        // Daily contract: exactly one row per candle after the 50-bar warmup. If the input were
        // monthly (the old range=max bug), 300 bars would be ~25 years and this would collapse.
        assertEquals(n - 50, rows.size)

        for (i in rows.indices) {
            val r = rows[i]
            assertTrue("composite in [0,100], was ${r.composite}", r.composite in 0f..100f)
            assertTrue("technical present on daily data", r.technical != null)
            assertTrue("real yield present", r.realYield != null)
            assertTrue("usd present", r.usd != null)
            assertTrue("inflation present", r.inflation != null)
            if (i > 0) assertTrue("dates strictly ascending", rows[i].dateMs > rows[i - 1].dateMs)
        }

        // CSV = 2 comment lines + 1 header + one line per row.
        val csv = GoldIndexEngine.toCsv(rows).trim().lines()
        assertTrue(csv[0].startsWith("# Gold Index History"))
        assertTrue(csv[2].startsWith("Date,Gold Index,Real Yield Score,USD Score,Central Bank Score"))
        assertEquals(3 + rows.size, csv.size)
    }

    // ── Perf canary: full daily history (~5.5k bars) must not stall the CSV export. ─
    @Test fun computeHistoricalFull_scales_to_full_history() {
        val n = 5500                                   // ~ GLD daily bars since 2004
        val dates = dailyDates(n)
        val gld = candles(dates) { 80.0 + 40.0 * Math.sin(it / 200.0) + it * 0.03 }
        val dxy = candles(dates) { 95.0 + 10.0 * Math.sin(it / 300.0) }
        val ry  = obs(dates) { 0.8 + 0.6 * Math.sin(it / 250.0) }
        val inf = obs(dates) { 2.0 + 0.3 * Math.sin(it / 220.0) }

        val t0 = System.nanoTime()
        val rows = GoldIndexEngine.computeHistoricalFull(
            GoldIndexEngine.Inputs(gldCandles = gld, dxyCandles = dxy, realYield = ry, inflation = inf)
        )
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("computeHistoricalFull($n) -> ${rows.size} rows in ${ms}ms")
        assertEquals(n - 50, rows.size)
        assertTrue("CSV history generation too slow at full scale: ${ms}ms", ms < 15_000)
    }

    // ── P1-1: Central Bank publication lag is look-ahead-free ───────────────
    @Test fun cb_effectiveYear_has_no_lookahead() {
        // WGC publishes year Y in ~Q1 of Y+1: from April Y+1 the latest KNOWN full year is Y.
        assertEquals(2024, GoldIndexEngine.cbEffectiveYear(2025, 6))   // Jun 2025 -> 2024 actual
        assertEquals(2024, GoldIndexEngine.cbEffectiveYear(2025, 4))   // Apr: 2024 just published
        assertEquals(2023, GoldIndexEngine.cbEffectiveYear(2025, 3))   // Mar: 2024 not out yet
        assertEquals(2023, GoldIndexEngine.cbEffectiveYear(2024, 12))
        // A point in time never consumes a figure published later: eff year always precedes as-of.
        for (y in 2012..2030) for (m in 1..12)
            assertTrue("eff year must precede as-of year", GoldIndexEngine.cbEffectiveYear(y, m) < y)
    }

    @Test fun cb_tonnes_track_published_year() {
        assertEquals(1045.0, GoldIndexEngine.cbTonnesEffective(2025, 6), 0.0)   // 2024 actual
        assertEquals(1037.0, GoldIndexEngine.cbTonnesEffective(2025, 2), 0.0)   // 2023 actual
        assertEquals(1000.0, GoldIndexEngine.cbTonnesEffective(2026, 6), 0.0)   // 2025 estimate
    }

    @Test fun cb_score_anchors_are_monotonic_and_clamped() {
        assertEquals(10f, GoldIndexEngine.cbScoreFromTonnes(-500.0))   // net-selling clamp
        assertEquals(95f, GoldIndexEngine.cbScoreFromTonnes(2000.0))   // record-buying clamp
        var prev = GoldIndexEngine.cbScoreFromTonnes(-200.0)
        var t = -100.0
        while (t <= 1400.0) {
            val s = GoldIndexEngine.cbScoreFromTonnes(t)
            assertTrue("score must be non-decreasing in tonnes at $t ($prev -> $s)", s >= prev - 1e-4f)
            prev = s; t += 50.0
        }
    }

    // ── P1-1c: no-dominance guardrail halves CB weight when it is the only macro voice ──
    @Test fun guardrail_caps_cb_when_no_fred_or_dxy() {
        val dates = dailyDates(80)
        val gld = candles(dates) { 100.0 + it }            // rising -> Technical available
        val report = GoldIndexEngine.compute(
            GoldIndexEngine.Inputs(gldCandles = gld, dxyCandles = emptyList(), realYield = emptyList(), inflation = emptyList())
        )
        // Only CB + Technical available; CB weight halved (0.22 -> 0.11).
        val cal = Calendar.getInstance(ny)
        val cb = GoldIndexEngine.cbScoreFromTonnes(
            GoldIndexEngine.cbTonnesEffective(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        )
        val tech = GoldIndexEngine.scoreTechnical(gld.map { it.close }).score
        val capped   = (cb * 0.11f + tech * 0.12f) / 0.23f
        val uncapped = (cb * 0.22f + tech * 0.12f) / 0.34f
        assertEquals(capped, report.compositeScore, 0.1f)
        assertTrue("guardrail should pull the headline off the CB-dominated value", capped <= uncapped + 1e-3f)
    }
}
