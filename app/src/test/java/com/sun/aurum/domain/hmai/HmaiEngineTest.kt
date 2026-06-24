package com.sun.aurum.domain.hmai

import com.sun.aurum.model.CbAction
import com.sun.aurum.model.Candle
import com.sun.aurum.model.CircuitBreakerResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Pure-JVM tests for the HMAI engine — the scoring behind the v2.0 second instrument (the Dollar
 * tab). Like the Gold Index, HMAI has no Android deps and runs as a fast unit test. Candles are
 * synthetic; assertions target direction and invariants (composite bounds, the 6-pillar contract,
 * circuit-breaker overrides) rather than exact magic numbers, so refactors that preserve behavior
 * don't churn the suite.
 */
class HmaiEngineTest {

    /** A daily series whose open is the prior close (no artificial gaps) and high/low hug the body. */
    private fun series(n: Int, close: (Int) -> Double): List<Candle> {
        var prevClose = close(0)
        return (0 until n).map { i ->
            val c = close(i)
            val open = prevClose
            prevClose = c
            Candle(
                open = open,
                high = maxOf(open, c) * 1.003,
                low = minOf(open, c) * 0.997,
                close = c,
                volume = 1_000_000L,
                datetimeMs = i.toLong() * 86_400_000L,
            )
        }
    }

    private fun uptrend(n: Int = 260)   = series(n) { 100.0 + 0.4 * it + 0.5 * sin(it / 4.0) }
    private fun downtrend(n: Int = 260) = series(n) { 360.0 - 0.4 * it + 0.5 * sin(it / 4.0) }

    private fun labelFor(composite: Double) =
        when { composite >= 70 -> "RISK ON"; composite >= 45 -> "CAUTION"; else -> "RISK OFF" }

    // ── CircuitBreaker.applyOverride: deterministic mapping per action ──────────
    @Test fun circuitBreaker_applyOverride_maps_each_action() {
        fun cb(a: CbAction) = CircuitBreakerResult(emptyList(), a, "")
        assertEquals(88.0, CircuitBreaker.applyOverride(88.0, cb(CbAction.PASS_THROUGH)), 0.0)
        assertEquals(50.0, CircuitBreaker.applyOverride(88.0, cb(CbAction.CAP_50)), 0.0)
        assertEquals(40.0, CircuitBreaker.applyOverride(40.0, cb(CbAction.CAP_50)), 0.0)   // below cap → untouched
        assertEquals(30.0, CircuitBreaker.applyOverride(88.0, cb(CbAction.FORCE_20_30)), 0.0)
        assertEquals(20.0, CircuitBreaker.applyOverride(5.0,  cb(CbAction.FORCE_20_30)), 0.0)
        assertEquals(10.0, CircuitBreaker.applyOverride(88.0, cb(CbAction.FORCE_0_10)), 0.0)
    }

    // ── CircuitBreaker.evaluate: trigger detection + escalation ─────────────────
    @Test fun circuitBreaker_calm_series_has_no_triggers() {
        val cb = CircuitBreaker.evaluate(uptrend(), vixValue = 15.0)
        assertTrue("calm uptrend should not trip any breaker, got ${cb.triggers}", cb.triggers.isEmpty())
        assertEquals(CbAction.PASS_THROUGH, cb.action)
    }

    @Test fun circuitBreaker_insufficient_data_passes_through() {
        val cb = CircuitBreaker.evaluate(uptrend(20), vixValue = null)
        assertEquals(CbAction.PASS_THROUGH, cb.action)
        assertTrue(cb.triggers.isEmpty())
    }

    @Test fun circuitBreaker_vix_crisis_is_flagged() {
        val cb = CircuitBreaker.evaluate(uptrend(), vixValue = 45.0)
        assertTrue("VIX>40 should trip the crisis trigger, got ${cb.triggers}",
            cb.triggers.any { it.contains("VIX") })
    }

    @Test fun circuitBreaker_down_streak_is_flagged() {
        // 254 calm rising bars, then 6 strictly-down days at the tail.
        val rising = series(254) { 100.0 + 0.4 * it }
        var c = rising.last().close
        val falling = (0 until 6).map {
            val open = c; c *= 0.985
            Candle(open, open * 1.001, c * 0.999, c, 1_000_000L, (254 + it).toLong() * 86_400_000L)
        }
        val cb = CircuitBreaker.evaluate(rising + falling, vixValue = null)
        assertTrue("5+ consecutive down days should trip, got ${cb.triggers}",
            cb.triggers.any { it.contains("consecutive down days") })
        assertTrue("any trigger forces an override action", cb.action != CbAction.PASS_THROUGH)
    }

    // ── Pillar 1 (Technical Structure): direction ───────────────────────────────
    @Test fun pillar1_uptrend_is_bullish() {
        val p = Pillar1Technical.score(uptrend())
        assertTrue("uptrend technical score should be high, was ${p.score}", p.score >= 18.0)
        assertTrue("label should be bullish, was ${p.label}", p.label in setOf("TRENDING_BULL", "WEAK_BULL"))
    }

    @Test fun pillar1_downtrend_is_bearish() {
        val p = Pillar1Technical.score(downtrend())
        assertTrue("downtrend technical score should be low, was ${p.score}", p.score <= 12.0)
        assertTrue("label should be bearish, was ${p.label}", p.label in setOf("TRENDING_BEAR", "WEAK_BEAR"))
    }

    // ── HmaiEngine.compute: the 6-pillar contract + composite invariants ────────
    @Test fun compute_is_wellformed() {
        val report = HmaiEngine.compute("dx-y.nyb", uptrend(), vixValue = 15.0, gemini = null)

        assertEquals("symbol is uppercased", "DX-Y.NYB", report.symbol)
        assertEquals("exactly 6 pillars", 6, report.pillars.size)
        assertEquals("pillars numbered 1..6 in order", (1..6).toList(), report.pillars.map { it.pillar })
        assertEquals("max scores sum to 100", 100.0, report.pillars.sumOf { it.maxScore }, 1e-6)
        report.pillars.forEach { p ->
            assertTrue("${p.name} score in [0, ${p.maxScore}], was ${p.score}", p.score in 0.0..p.maxScore)
        }
        assertTrue("composite in [0,100], was ${report.composite}", report.composite in 0.0..100.0)
        assertEquals("label matches composite", labelFor(report.composite), report.compositeLabel)
        // No Gemini supplied → all AI-derived fields stay null/empty.
        assertEquals(null, report.geminiSignal)
        assertEquals(null, report.geminiScore)
        assertTrue(report.geminiKeyFactors.isEmpty())
    }

    @Test fun compute_uptrend_scores_higher_than_downtrend() {
        val up   = HmaiEngine.compute("X", uptrend(),   vixValue = 15.0, gemini = null).composite
        val down = HmaiEngine.compute("X", downtrend(), vixValue = 15.0, gemini = null).composite
        assertTrue("bullish structure should beat bearish ($up vs $down)", up > down)
    }

    @Test fun compute_vix_crisis_caps_the_composite() {
        val report = HmaiEngine.compute("X", uptrend(), vixValue = 45.0, gemini = null)
        assertTrue("breaker should have a VIX trigger, got ${report.circuitBreaker.triggers}",
            report.circuitBreaker.triggers.any { it.contains("VIX") })
        // At least one trigger ⇒ score is capped to 50 or lower, never above the raw composite.
        assertTrue("override never raises the score", report.composite <= report.rawComposite)
        assertTrue("a tripped breaker caps the headline at 50, was ${report.composite}",
            report.composite <= 50.0)
    }
}
