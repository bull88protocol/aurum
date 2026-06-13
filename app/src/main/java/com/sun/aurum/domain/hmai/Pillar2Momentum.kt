package com.sun.aurum.domain.hmai

import com.sun.aurum.model.Candle
import com.sun.aurum.model.PillarResult

object Pillar2Momentum {
    fun score(candles: List<Candle>, p1Label: String): PillarResult {
        val closes = candles.map { it.close }
        val rsiSeries  = TechnicalIndicators.rsi(closes)
        val macdSeries = TechnicalIndicators.macd(closes)
        val rocSeries  = TechnicalIndicators.roc(closes, 10)
        val latestRsi  = rsiSeries.filterNotNull().lastOrNull() ?: 50.0
        val latestMacd = macdSeries.filterNotNull().lastOrNull()
        val prevMacd   = macdSeries.filterNotNull().dropLast(1).lastOrNull()
        val latestRoc  = rocSeries.filterNotNull().lastOrNull() ?: 0.0

        val (oversold, overbought) = when (p1Label) {
            "TRENDING_BULL" -> Pair(40.0, 80.0)
            "WEAK_BULL"     -> Pair(35.0, 75.0)
            "RANGE"         -> Pair(30.0, 70.0)
            "WEAK_BEAR"     -> Pair(25.0, 65.0)
            else            -> Pair(20.0, 60.0)
        }
        val midpoint = (oversold + overbought) / 2
        val rsiScore = when {
            latestRsi >= overbought -> 1
            latestRsi > midpoint    -> 5
            latestRsi > oversold    -> 3
            latestRsi <= oversold && latestRsi > oversold - 5 -> 4
            else -> 2
        }

        val macdSlopeScore = when {
            latestMacd == null || prevMacd == null -> 2
            latestMacd.hist > prevMacd.hist && latestMacd.hist > 0  -> 4
            latestMacd.hist > prevMacd.hist && latestMacd.hist <= 0 -> 3
            latestMacd.hist < prevMacd.hist && latestMacd.hist > 0  -> 2
            else -> 0
        }

        val rocScore = when {
            latestRoc > 5.0  -> 3; latestRoc > 1.0  -> 3; latestRoc > 0.0  -> 2
            latestRoc > -2.0 -> 1; else              -> 0
        }

        val recentCloses = closes.takeLast(20)
        val recentRsi = rsiSeries.takeLast(20).filterNotNull()
        val divergenceScore = when {
            recentRsi.size < 10 -> 1
            else -> {
                val priceDir = recentCloses.last() - recentCloses.first()
                val rsiDir   = recentRsi.last() - recentRsi.first()
                when {
                    priceDir > 0 && rsiDir > 0 -> 3
                    priceDir < 0 && rsiDir > 0 -> 3
                    priceDir > 0 && rsiDir < 0 -> 1
                    else -> 2
                }
            }
        }

        val total = (rsiScore + macdSlopeScore + rocScore + divergenceScore).toDouble().coerceIn(0.0, 15.0)
        val label = when { total >= 12 -> "HEALTHY_ALIGNED"; total >= 8 -> "FADING"; total >= 4 -> "DIVERGING"; else -> "EXHAUSTED" }
        val components = mapOf("rsi" to rsiScore.toDouble(), "macd_slope" to macdSlopeScore.toDouble(), "roc10" to rocScore.toDouble(), "divergence" to divergenceScore.toDouble())
        val details = "RSI=${String.format("%.1f", latestRsi)} MACD_hist=${latestMacd?.let { String.format("%.3f", it.hist) } ?: "n/a"} ROC10=${String.format("%.1f", latestRoc)}%"
        return PillarResult(2, "Momentum & Timing", total, 15.0, label, components, details)
    }
}
