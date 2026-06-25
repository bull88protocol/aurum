package com.sun.aurum.domain.hmai

import com.sun.aurum.util.formatDecimals

import com.sun.aurum.model.Candle
import com.sun.aurum.model.PillarResult

object Pillar5Valuation {
    fun score(candles: List<Candle>): PillarResult {
        val closes = candles.map { it.close }
        val rangePos = TechnicalIndicators.range52WeekPosition(closes)
        val rangeScore = when {
            rangePos < 20 -> 3; rangePos < 40 -> 3; rangePos < 60 -> 2
            rangePos < 80 -> 1; else           -> 0
        }
        val hvPctile = TechnicalIndicators.hvPercentile(closes, period = 20, lookback = 252)
        val hvScore = when {
            hvPctile in 25.0..75.0 -> 3; hvPctile < 10.0 -> 1; hvPctile > 90.0 -> 1; else -> 2
        }

        val closes20 = closes.takeLast(20)
        val closes60 = closes.takeLast(60)
        val momentumScore = if (closes.size >= 60) {
            val ret20 = (closes.last() - closes20.first()) / closes20.first() * 100
            val ret60 = (closes.last() - closes60.first()) / closes60.first() * 100
            when {
                ret20 > 5 && ret60 > 10 -> 4
                ret20 > 0 && ret60 > 0  -> 3
                ret20 < -5              -> 1
                else                    -> 2
            }
        } else 2

        val total = (rangeScore + hvScore + momentumScore).toDouble().coerceIn(0.0, 10.0)
        val label = when { total >= 7 -> "FAVORABLE"; total >= 4 -> "FAIR_VALUE"; else -> "STRETCHED" }
        val components = mapOf("range_pos" to rangeScore.toDouble(), "hv_pctile" to hvScore.toDouble(), "momentum" to momentumScore.toDouble())
        val details = "Range52w=${formatDecimals(rangePos, 0)}% HVpctile=${formatDecimals(hvPctile, 0)}%"
        return PillarResult(5, "Valuation Context", total, 10.0, label, components, details)
    }
}
