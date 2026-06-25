package com.sun.aurum.domain.hmai

import com.sun.aurum.model.Candle
import com.sun.aurum.model.GeminiResult
import com.sun.aurum.model.HmaiReport

object HmaiEngine {
    fun compute(symbol: String, candles: List<Candle>, vixValue: Double?, gemini: GeminiResult?): HmaiReport {
        val p1 = Pillar1Technical.score(candles)
        val p2 = Pillar2Momentum.score(candles, p1.label)
        val p3 = Pillar3Probabilistic.score(candles)
        val p4 = Pillar4Sentiment.score(candles, vixValue, gemini)
        val p5 = Pillar5Valuation.score(candles)
        val p6 = Pillar6Coherence.score(listOf(p1, p2, p3, p4, p5))
        val pillars = listOf(p1, p2, p3, p4, p5, p6)
        val rawComposite = (p1.score + p2.score + p3.score + p4.score + p5.score + p6.score).coerceIn(0.0, 100.0)
        val cb = CircuitBreaker.evaluate(candles, vixValue)
        val composite = CircuitBreaker.applyOverride(rawComposite, cb)
        val compositeLabel = when { composite >= 70 -> "RISK ON"; composite >= 45 -> "CAUTION"; else -> "RISK OFF" }
        return HmaiReport(
            symbol         = symbol.uppercase(),
            composite      = composite,
            compositeLabel = compositeLabel,
            pillars        = pillars,
            circuitBreaker = cb,
            rawComposite   = rawComposite,
            vixValue       = vixValue,
            geminiSignal          = gemini?.signal,
            geminiScore           = gemini?.score,
            geminiDescription     = gemini?.description?.takeIf { it.isNotBlank() },
            geminiKeyFactors      = gemini?.keyFactors ?: emptyList(),
            geminiYesterdayRecap  = gemini?.yesterdayRecap?.takeIf { it.isNotBlank() },
            geminiTodayOutlook    = gemini?.todayOutlook?.takeIf { it.isNotBlank() },
            lastSessionLabel      = gemini?.lastSessionLabel?.takeIf { it.isNotBlank() },
            nextSessionLabel      = gemini?.nextSessionLabel?.takeIf { it.isNotBlank() },
        )
    }
}
