package com.sun.aurum.domain.gold

import com.sun.aurum.model.Candle
import com.sun.aurum.model.FredObs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-JVM tests for the 20-day drivers engine. Parity with the backtested Python replica over the
 * full real history is checked separately (EngineHistoryDumpTest + research/scripts/drivers-2026-09).
 */
class GoldDriversEngineTest {

    private val ny = TimeZone.getTimeZone("America/New_York")
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = ny }

    /** n consecutive daily calendar dates (NY tz) as (epochMs at noon, "yyyy-MM-dd"). */
    private fun dailyDates(n: Int): List<Pair<Long, String>> {
        val cal = Calendar.getInstance(ny).apply {
            set(2020, Calendar.JANUARY, 1, 12, 0, 0); set(Calendar.MILLISECOND, 0)
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
            Candle(open = c, high = c, low = c, close = c, volume = 1_000_000L, datetimeMs = ms)
        }

    private fun obs(dates: List<Pair<Long, String>>, value: (Int) -> Double): List<FredObs> =
        dates.mapIndexed { i, (_, s) -> FredObs(s, value(i)) }

    private val n = 300
    private val dates = dailyDates(n)

    /** Wiggles around [base] (so the vol window is non-degenerate), then moves [move] over the last 20 obs. */
    private fun path(base: Double, wiggle: Double, move: Double): (Int) -> Double = { i ->
        val ramp = if (i >= n - 20) move * (i - (n - 21)) / 20.0 else 0.0
        base + wiggle * sin(i * 0.7) + ramp
    }

    private val gld = candles(dates) { 180.0 + it * 0.05 }

    private fun inputs(ryMove: Double, dxyMovePts: Double) = GoldDriversEngine.Inputs(
        gldCandles = gld,
        dxyCandles = candles(dates, path(100.0, 0.5, dxyMovePts)),
        realYield  = obs(dates, path(1.5, 0.03, ryMove)),
    )

    @Test fun toLabel_thresholds() {
        assertEquals("STRONG TAILWIND", GoldDriversEngine.toLabel(70f))
        assertEquals("TAILWIND", GoldDriversEngine.toLabel(69.9f))
        assertEquals("TAILWIND", GoldDriversEngine.toLabel(30f))
        assertEquals("MIXED", GoldDriversEngine.toLabel(29.9f))
        assertEquals("MIXED", GoldDriversEngine.toLabel(-29.9f))
        assertEquals("HEADWIND", GoldDriversEngine.toLabel(-30f))
        assertEquals("HEADWIND", GoldDriversEngine.toLabel(-69.9f))
        assertEquals("STRONG HEADWIND", GoldDriversEngine.toLabel(-70f))
    }

    @Test fun rising_yields_and_a_rising_dollar_read_as_a_strong_headwind() {
        val r = GoldDriversEngine.compute(inputs(ryMove = 0.6, dxyMovePts = 6.0))
        assertEquals(-100f, r.legs[0].score, 0.01f)
        assertEquals(-100f, r.legs[1].score, 0.01f)
        assertEquals(-100f, r.score, 0.01f)
        assertEquals("STRONG HEADWIND", r.label)
    }

    @Test fun falling_yields_and_a_weaker_dollar_read_as_a_strong_tailwind() {
        val r = GoldDriversEngine.compute(inputs(ryMove = -0.6, dxyMovePts = -6.0))
        assertEquals(100f, r.score, 0.01f)
        assertEquals("STRONG TAILWIND", r.label)
    }

    // The proposal's own design point: when the two drivers fight, the read must not pick a side.
    @Test fun drivers_pulling_opposite_ways_read_mixed() {
        val r = GoldDriversEngine.compute(inputs(ryMove = 0.6, dxyMovePts = -6.0))
        assertEquals(-100f, r.legs[0].score, 0.01f)
        assertEquals(100f, r.legs[1].score, 0.01f)
        assertEquals(0f, r.score, 0.01f)
        assertEquals("MIXED", r.label)
    }

    @Test fun each_leg_is_its_20_obs_change_over_the_sample_std_of_252_changes_halved() {
        // Engineer the changes directly: v[i] = v[i-20] + c[i], c alternating +1 / -1. Any 252
        // consecutive changes are then 126 of each, so mean 0 and sample std sqrt(252/251); the
        // last change is +1, so z = 1/sqrt(252/251) and the leg reads -100 * z / 2.
        val m = 301
        val d = dailyDates(m)
        val v = DoubleArray(m)
        for (i in 20 until m) v[i] = v[i - 20] + if (i % 2 == 0) 1.0 else -1.0
        val r = GoldDriversEngine.compute(
            GoldDriversEngine.Inputs(candles(d) { 100.0 }, emptyList(), obs(d) { v[it] })
        )
        val expected = (-100.0 * (1.0 / sqrt(252.0 / 251.0)) / 2.0).toFloat()
        assertEquals(expected, r.legs[0].score, 0.001f)
        assertEquals(1.0, r.legs[0].change, 1e-12)
    }

    @Test fun a_leg_needs_126_changes_before_it_scores() {
        val short = dailyDates(145)   // 145 obs -> 125 changes at the last index
        val enough = dailyDates(146)  // 146 obs -> 126
        fun ryLeg(d: List<Pair<Long, String>>) = GoldDriversEngine.compute(
            GoldDriversEngine.Inputs(candles(d) { 100.0 }, emptyList(), obs(d) { 1.0 + 0.1 * sin(it * 0.7) })
        ).legs[0]
        assertFalse(ryLeg(short).available)
        assertFalse("a short series is not a missing key", ryLeg(short).keyRequired)
        assertTrue(ryLeg(enough).available)
    }

    @Test fun without_fred_data_the_real_yield_leg_needs_a_key_and_the_dollar_carries_the_read() {
        val base = inputs(ryMove = 0.0, dxyMovePts = 6.0)
        val r = GoldDriversEngine.compute(base.copy(realYield = emptyList()))
        assertFalse(r.legs[0].available)
        assertTrue(r.legs[0].keyRequired)
        assertTrue(r.legs[1].available)
        assertEquals(r.legs[1].score, r.score, 0.001f)
        assertTrue(r.available)
    }

    @Test fun no_legs_at_all_reads_na() {
        val r = GoldDriversEngine.compute(GoldDriversEngine.Inputs(gld, emptyList(), emptyList()))
        assertFalse(r.available)
        assertEquals("N/A", r.label)
    }

    @Test fun the_breakdown_adds_back_up_to_gold_s_own_move() {
        val r = GoldDriversEngine.compute(inputs(ryMove = 0.3, dxyMovePts = -2.0))
        val gold = r.goldChangePct!!
        assertEquals(((gld.last().close / gld[gld.size - 21].close) - 1) * 100, gold, 1e-9)
        assertEquals(GoldDriversEngine.BETA_REAL_YIELD * r.legs[0].change, r.legs[0].goldImpactPct, 1e-9)
        assertEquals(GoldDriversEngine.BETA_DOLLAR * r.legs[1].change, r.legs[1].goldImpactPct, 1e-9)
        assertEquals(gold, r.legs.sumOf { it.goldImpactPct } + r.otherPct!!, 1e-9)
    }

    @Test fun the_breakdown_needs_21_gold_sessions() {
        val r = GoldDriversEngine.compute(inputs(0.3, 2.0).copy(gldCandles = gld.take(20)))
        assertNull(r.goldChangePct)
        assertNull(r.otherPct)
    }

    @Test fun history_ends_on_the_headline_and_covers_at_most_a_year() {
        val r = GoldDriversEngine.compute(inputs(ryMove = 0.3, dxyMovePts = -2.0))
        assertTrue(r.history.size <= 252)
        assertEquals(r.score, r.history.last().score, 0.001f)
        assertEquals(gld.last().datetimeMs, r.history.last().dateMs)
        assertTrue(r.history.zipWithNext().all { (a, b) -> a.dateMs < b.dateMs })
    }

    @Test fun legs_are_read_as_of_gold_s_latest_session() {
        // A real-yield print dated after gold's last bar must not leak into the read.
        val base = inputs(ryMove = 0.0, dxyMovePts = 0.0)
        val later = FredObs("2099-01-01", 9.0)
        val r = GoldDriversEngine.compute(base.copy(realYield = base.realYield + later))
        assertEquals(dates.last().second, r.legs[0].asOf)
    }
}
