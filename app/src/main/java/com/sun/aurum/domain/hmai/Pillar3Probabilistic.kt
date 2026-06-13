package com.sun.aurum.domain.hmai

import com.sun.aurum.model.Candle
import com.sun.aurum.model.PillarResult
import kotlin.math.sqrt

object Pillar3Probabilistic {
    fun score(candles: List<Candle>): PillarResult {
        val closes = candles.map { it.close }
        if (closes.size < 60) return PillarResult(3, "Probabilistic Engine", 10.0, 20.0, "INCONCLUSIVE",
            mapOf("directional" to 4.0, "tail_risk" to 3.5, "regime_sim" to 2.5), "Insufficient data")

        val logRets = (1 until closes.size).map { i -> (closes[i] - closes[i - 1]) / closes[i - 1] }

        val horizons = listOf(5, 10, 20)
        val probs = horizons.map { h ->
            if (logRets.size < h * 2) return@map 0.5
            val trials = minOf(200, logRets.size - h)
            val positives = (0 until trials).count { i -> logRets.subList(i, i + h).sum() > 0 }
            positives.toDouble() / trials
        }
        val avgProb = probs.average()
        val directionalScore = when {
            avgProb >= 0.70 -> 8; avgProb >= 0.60 -> 7; avgProb >= 0.55 -> 6
            avgProb >= 0.50 -> 5; avgProb >= 0.45 -> 3; avgProb >= 0.40 -> 2; else -> 0
        }

        val window = if (logRets.size > 252) logRets.takeLast(252) else logRets
        val sorted = window.sorted()
        val var95Idx = (sorted.size * 0.05).toInt().coerceAtLeast(1)
        val var95 = sorted[var95Idx]
        val cvar95 = sorted.take(var95Idx).average()
        var peak = Double.MIN_VALUE; var maxDd = 0.0; var runProd = 1.0
        for (r in window) {
            runProd *= (1 + r); if (runProd > peak) peak = runProd
            val dd = (peak - runProd) / peak; if (dd > maxDd) maxDd = dd
        }
        val tailRiskScore = when {
            var95 > -0.02 && maxDd < 0.08  -> 7; var95 > -0.02 -> 6
            var95 > -0.03 && maxDd < 0.12  -> 5; var95 > -0.03 -> 4
            var95 > -0.04 && maxDd < 0.18  -> 3; var95 > -0.05 -> 2; else -> 0
        }

        val lookback20 = minOf(20, logRets.size)
        val ret20 = logRets.takeLast(lookback20).sum()
        val vol20 = run {
            val w = logRets.takeLast(lookback20); val mean = w.average()
            sqrt(w.sumOf { (it - mean) * (it - mean) } / w.size) * sqrt(252.0)
        }
        val k = 20
        val historicalFeatures = (lookback20 until closes.size - 5).map { i ->
            val r = logRets.subList(i - lookback20, i).sum()
            val v = run {
                val w = logRets.subList(i - lookback20, i); val m = w.average()
                sqrt(w.sumOf { (it - m) * (it - m) } / w.size) * sqrt(252.0)
            }
            val fwdRet = logRets.subList(i, minOf(i + 5, logRets.size)).sum()
            Triple(r, v, fwdRet)
        }
        val nearest = historicalFeatures.sortedBy { (r, v, _) -> kotlin.math.abs(r - ret20) + kotlin.math.abs(v - vol20) }.take(k)
        val hitRate = nearest.count { (_, _, fwd) -> fwd > 0 }.toDouble() / nearest.size.coerceAtLeast(1)
        val avgFwdRet = if (nearest.isEmpty()) 0.0 else nearest.map { (_, _, fwd) -> fwd }.average()
        val regimeScore = when {
            hitRate >= 0.70 && avgFwdRet > 0.01 -> 5; hitRate >= 0.60 && avgFwdRet > 0 -> 4
            hitRate >= 0.50 -> 3; hitRate >= 0.40 -> 2; else -> 1
        }

        val total = (directionalScore + tailRiskScore + regimeScore).toDouble().coerceIn(0.0, 20.0)
        val label = when { total >= 16 -> "STRONG_FAVORABLE"; total >= 12 -> "MILD_FAVORABLE"; total >= 8 -> "INCONCLUSIVE"; total >= 4 -> "MILD_UNFAVORABLE"; else -> "STRONG_UNFAVORABLE" }
        val components = mapOf("directional" to directionalScore.toDouble(), "tail_risk" to tailRiskScore.toDouble(), "regime_sim" to regimeScore.toDouble())
        val details = "FwdProb=${String.format("%.0f", avgProb * 100)}% VaR95=${String.format("%.1f", var95 * 100)}% MaxDD=${String.format("%.1f", maxDd * 100)}% KNN=${String.format("%.0f", hitRate * 100)}%"
        return PillarResult(3, "Probabilistic Engine", total, 20.0, label, components, details)
    }
}
