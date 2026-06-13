package com.sun.aurum.domain.hmai

import com.sun.aurum.model.CbAction
import com.sun.aurum.model.Candle
import com.sun.aurum.model.CircuitBreakerResult
import kotlin.math.abs

object CircuitBreaker {

    fun evaluate(candles: List<Candle>, vixValue: Double?): CircuitBreakerResult {
        val triggers = mutableListOf<String>()
        if (candles.size < 25) return CircuitBreakerResult(emptyList(), CbAction.PASS_THROUGH, "Insufficient data")

        val closes = candles.map { it.close }
        val volumes = candles.map { it.volume }

        val atrSeries = TechnicalIndicators.atr(candles, 14).filterNotNull()
        if (atrSeries.size >= 15) {
            val latestAtr = atrSeries.last()
            val baseAtr = atrSeries.dropLast(1).takeLast(14).average()
            if (baseAtr > 0 && latestAtr / baseAtr > 2.0)
                triggers.add("Volatility spike (ATR ×${String.format("%.1f", latestAtr / baseAtr)})")
        }

        val volSma = TechnicalIndicators.volSma(volumes, 20).filterNotNull()
        if (volSma.isNotEmpty()) {
            val curVol = volumes.last().toDouble()
            val avgVol = volSma.last()
            if (avgVol > 0 && curVol / avgVol < 0.30)
                triggers.add("Volume collapse (${String.format("%.0f", curVol / avgVol * 100)}% of avg)")
        }

        val gapPct = TechnicalIndicators.lastGapPct(candles)
        if (abs(gapPct) > 3.0) triggers.add("Large gap (${String.format("%.1f", gapPct)}%)")

        val recent = candles.takeLast(10)
        var downStreak = 0; var maxStreak = 0
        for (i in 1 until recent.size) {
            if (recent[i].close < recent[i - 1].close) { downStreak++; if (downStreak > maxStreak) maxStreak = downStreak }
            else downStreak = 0
        }
        if (maxStreak >= 5) triggers.add("$maxStreak consecutive down days")

        val hvPctile = TechnicalIndicators.hvPercentile(closes, period = 10, lookback = 252)
        if (hvPctile > 90.0) triggers.add("Realized vol spike (${String.format("%.0f", hvPctile)}th pctile)")

        if (vixValue != null) {
            when {
                vixValue > 40 -> triggers.add("VIX crisis (${String.format("%.1f", vixValue)})")
                vixValue > 30 -> triggers.add("VIX elevated (${String.format("%.1f", vixValue)})")
            }
        }

        val action = when (triggers.size) {
            0 -> CbAction.PASS_THROUGH
            1 -> CbAction.CAP_50
            2 -> CbAction.FORCE_20_30
            else -> CbAction.FORCE_0_10
        }
        val description = when (action) {
            CbAction.PASS_THROUGH -> "No overrides triggered"
            CbAction.CAP_50       -> "Score capped at 50 (1 trigger)"
            CbAction.FORCE_20_30  -> "Score forced to 20-30 range (2 triggers)"
            CbAction.FORCE_0_10   -> "Score forced to 0-10 range (${triggers.size} triggers)"
        }
        return CircuitBreakerResult(triggers, action, description)
    }

    fun applyOverride(rawScore: Double, result: CircuitBreakerResult): Double {
        return when (result.action) {
            CbAction.PASS_THROUGH -> rawScore
            CbAction.CAP_50       -> minOf(rawScore, 50.0)
            CbAction.FORCE_20_30  -> rawScore.coerceIn(20.0, 30.0)
            CbAction.FORCE_0_10   -> rawScore.coerceIn(0.0, 10.0)
        }
    }
}
