package com.sun.aurum.domain.hmai

import com.sun.aurum.util.formatDecimals

import com.sun.aurum.model.Candle
import com.sun.aurum.model.GeminiResult
import com.sun.aurum.model.PillarResult

object Pillar4Sentiment {
    fun score(candles: List<Candle>, vixValue: Double?, gemini: GeminiResult?): PillarResult {
        val obvSeries = TechnicalIndicators.obv(candles)
        val obvTrend = run {
            val recent = obvSeries.takeLast(10)
            if (recent.size < 2) 1
            else {
                val direction = recent.last() - recent.first()
                when { direction > 0 && (recent.last() - recent.min()) > 0 -> 3; direction > 0 -> 2; direction < 0 -> 0; else -> 1 }
            }
        }
        val volPriceDivScore = run {
            val window = candles.takeLast(10)
            if (window.size < 5) 1
            else {
                val upVolAvg   = window.filter { it.close >= it.open }.map { it.volume }.average().let { if (it.isNaN()) 0.0 else it }
                val downVolAvg = window.filter { it.close < it.open  }.map { it.volume }.average().let { if (it.isNaN()) 0.0 else it }
                when { upVolAvg > downVolAvg * 1.3 -> 2; upVolAvg > downVolAvg -> 1; else -> 0 }
            }
        }
        val gapScore = run {
            val gapPct = TechnicalIndicators.lastGapPct(candles)
            when { gapPct > 1.5 -> 2; gapPct > 0.3 -> 2; gapPct > -0.3 -> 1; gapPct > -1.5 -> 1; else -> 0 }
        }
        val upDayRatio = TechnicalIndicators.upDayRatio(candles, 10)
        val upDayScore = when { upDayRatio >= 0.7 -> 2; upDayRatio >= 0.5 -> 2; upDayRatio >= 0.4 -> 1; else -> 0 }
        val tier1Total = (obvTrend + volPriceDivScore + gapScore + upDayScore).coerceIn(0, 9)

        val vixScore: Int? = vixValue?.let { vix ->
            when { vix < 15 -> 3; vix < 20 -> 3; vix < 25 -> 2; vix < 30 -> 1; else -> 0 }
        }
        val geminiScore: Int? = gemini?.let { g ->
            when (g.signal) {
                "BULLISH" -> when { g.score >= 70 -> 3; g.score >= 60 -> 2; else -> 1 }
                "BEARISH" -> when { g.score <= 30 -> 0; else -> 1 }
                else -> 1
            }
        }
        val hasTier2 = vixScore != null || geminiScore != null
        val tier2Total = ((vixScore ?: 0) + (geminiScore ?: 0)).coerceIn(0, 6)
        val total = if (hasTier2) (tier1Total + tier2Total).toDouble().coerceIn(0.0, 15.0)
                    else (tier1Total.toDouble() / 9.0 * 15.0).coerceIn(0.0, 15.0)

        val label = when { total >= 12 -> "RISK_APPETITE"; total >= 8 -> "CAUTIOUS_OPT"; total >= 4 -> "MIXED_SENTIMENT"; else -> "FEAR_DOMINANT" }
        val components = buildMap {
            put("obv_trend", obvTrend.toDouble()); put("vol_price_div", volPriceDivScore.toDouble())
            put("gap", gapScore.toDouble()); put("up_day", upDayScore.toDouble())
            vixScore?.let { put("vix", it.toDouble()) }; geminiScore?.let { put("gemini", it.toDouble()) }
        }
        val details = buildString {
            append("OBV=${obvTrend} UpDay=${formatDecimals(upDayRatio * 100, 0)}%")
            vixValue?.let { append(" VIX=${formatDecimals(it, 1)}") }
            gemini?.let { append(" Gemini=${it.signal}(${it.score})") }
        }
        return PillarResult(4, "Sentiment & Narrative", total, 15.0, label, components, details)
    }
}
