package com.sun.aurum.domain.hmai

import com.sun.aurum.util.formatDecimals

import com.sun.aurum.model.Candle
import com.sun.aurum.model.PillarResult

object Pillar1Technical {
    fun score(candles: List<Candle>): PillarResult {
        val closes = candles.map { it.close }
        val sma20  = TechnicalIndicators.sma(closes, 20)
        val sma50  = TechnicalIndicators.sma(closes, 50)
        val sma200 = TechnicalIndicators.sma(closes, 200)
        val atrPctile = TechnicalIndicators.atrPercentile(candles)
        val latestClose = closes.last()
        val s20  = sma20.lastOrNull { it != null }
        val s50  = sma50.lastOrNull { it != null }
        val s200 = sma200.lastOrNull { it != null }
        val sma20Values = sma20.filterNotNull()
        val sma20Slope = TechnicalIndicators.slope(sma20Values, 5)

        val maCrossScore = when {
            s50 != null && s200 != null && s50 > s200 && latestClose > s200 -> {
                val dist = (s50 - s200) / s200 * 100
                when { dist > 5.0 -> 8; dist > 2.0 -> 6; dist > 0.5 -> 4; else -> 2 }
            }
            s50 != null && s200 != null && s50 < s200 -> {
                val dist = (s200 - s50) / s200 * 100
                when { dist > 5.0 -> 0; dist > 2.0 -> 1; else -> 2 }
            }
            else -> 3
        }

        val pVs200Score = when {
            s200 == null -> 3
            latestClose > s200 -> {
                val pct = (latestClose - s200) / s200 * 100
                when { pct > 15 -> 4; pct > 5 -> 6; pct > 0 -> 5; else -> 3 }
            }
            else -> {
                val pct = (s200 - latestClose) / s200 * 100
                when { pct > 15 -> 0; pct > 5 -> 1; else -> 2 }
            }
        }

        val hhhlScore = TechnicalIndicators.hhhlScore(candles)
        val weeklyScore = maCrossScore + pVs200Score + hhhlScore

        val pVs20Score = when {
            s20 == null -> 2
            latestClose > s20 -> {
                val pct = (latestClose - s20) / s20 * 100
                when { pct > 5 -> 3; pct > 2 -> 5; pct > 0 -> 4; else -> 2 }
            }
            else -> {
                val pct = (s20 - latestClose) / s20 * 100
                when { pct > 5 -> 0; pct > 2 -> 1; else -> 2 }
            }
        }

        val sma20SlopeScore = when {
            sma20Slope == null  -> 2
            sma20Slope > 0.5    -> 4
            sma20Slope > 0.1    -> 3
            sma20Slope > -0.1   -> 2
            sma20Slope > -0.5   -> 1
            else                -> 0
        }

        val atrVolScore = when {
            atrPctile in 25.0..75.0 -> 3
            atrPctile < 15.0        -> 1
            atrPctile > 85.0        -> 1
            else                    -> 2
        }

        val dailyScore = pVs20Score + sma20SlopeScore + atrVolScore
        val weeklyBullish = weeklyScore >= 12; val weeklyBearish = weeklyScore < 8
        val dailyBullish  = dailyScore >= 8;   val dailyBearish  = dailyScore < 5
        val raw = weeklyScore + dailyScore
        val finalScore = when {
            weeklyBullish && dailyBullish  -> raw
            weeklyBullish && dailyBearish  -> minOf(raw, 18)
            weeklyBearish && dailyBullish  -> minOf(raw, 12)
            weeklyBearish && dailyBearish  -> minOf(raw, 8)
            else                           -> raw
        }.toDouble().coerceIn(0.0, 30.0)

        val label = when {
            finalScore >= 24 -> "TRENDING_BULL"
            finalScore >= 18 -> "WEAK_BULL"
            finalScore >= 12 -> "RANGE"
            finalScore >= 6  -> "WEAK_BEAR"
            else             -> "TRENDING_BEAR"
        }
        val components = mapOf("ma_cross" to maCrossScore.toDouble(), "p_vs_200ma" to pVs200Score.toDouble(),
            "hh_hl" to hhhlScore.toDouble(), "p_vs_20sma" to pVs20Score.toDouble(),
            "sma20_slope" to sma20SlopeScore.toDouble(), "atr_vol" to atrVolScore.toDouble())
        val details = buildString {
            s200?.let { append("SMA200=${it.toInt()} ") }
            s50?.let  { append("SMA50=${it.toInt()} ")  }
            s20?.let  { append("SMA20=${it.toInt()} ")  }
            append("ATRpct=${formatDecimals(atrPctile, 0)}")
        }
        return PillarResult(1, "Technical Structure", finalScore, 30.0, label, components, details)
    }
}
