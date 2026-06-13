package com.sun.aurum.domain.gold

import com.sun.aurum.model.Candle
import com.sun.aurum.model.DailyIndexPoint
import com.sun.aurum.model.GoldComponentScore
import com.sun.aurum.model.GoldIndexReport
import com.sun.aurum.network.FredClient
import java.text.SimpleDateFormat
import java.util.*

object GoldIndexEngine {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("America/New_York")
    }

    data class Inputs(
        val gldCandles: List<Candle>,
        val dxyCandles: List<Candle>,
        val realYield: List<FredClient.Obs>,    // FRED DFII10
        val inflation: List<FredClient.Obs>,    // FRED T10YIE
        val centralBankScore: Int? = null,      // 0-100 from Gemini; null = use proxy
    )

    data class HistoricalRow(
        val dateMs: Long,
        val composite: Float,
        val realYield: Float?,
        val usd: Float?,
        val inflation: Float?,
        val technical: Float?,
    )

    fun compute(inputs: Inputs): GoldIndexReport {
        val candles = inputs.gldCandles
        if (candles.size < 30) return emptyReport()
        val closes = candles.map { it.close }

        // Five components — macro-weighted (commodity trader / economist perspective)
        val comp1 = scoreRealYield(inputs.realYield)        // 35% — dominant driver
        val comp2 = scoreUSD(inputs.dxyCandles)             // 25% — inverse gold-dollar
        val comp3 = scoreCentralBank(inputs.centralBankScore) // 20% — structural demand
        val comp4 = scoreInflation(inputs.inflation)         // 15% — inflation hedge
        val comp5 = scoreTechnical(closes)                   //  5% — timing only

        val components = listOf(comp1, comp2, comp3, comp4, comp5)
        val weightPairs = listOf(comp1 to 0.35f, comp2 to 0.25f, comp3 to 0.20f, comp4 to 0.15f, comp5 to 0.05f)
        var wSum = 0f; var wTotal = 0f
        for ((comp, w) in weightPairs) {
            if (comp.available) { wSum += comp.score * w; wTotal += w }
        }
        val composite = if (wTotal > 0f) wSum / wTotal else 50f

        val historical = computeHistorical(inputs, minOf(candles.size - 1, 252))
        val (fwdScore, fwdLabel, fwdComponents) = computeForwardSignal(inputs)

        return GoldIndexReport(
            compositeScore    = composite,
            compositeLabel    = toLabel(composite),
            components        = components,
            historicalScores  = historical,
            timestamp         = System.currentTimeMillis(),
            forwardScore      = fwdScore,
            forwardLabel      = fwdLabel,
            forwardComponents = fwdComponents,
        )
    }

    // ── Component scorers (current snapshot) ────────────────────────────────

    private fun scoreRealYield(obs: List<FredClient.Obs>): GoldComponentScore {
        if (obs.size < 5) return GoldComponentScore(
            name = "Real Yield Pressure (35%)", score = 50f, label = "N/A",
            detail = "FRED API key required → Settings", available = false,
        )
        val vals = obs.map { it.value }
        val current = vals.last()
        val window = vals.takeLast(minOf(vals.size, 252))
        val score = invertedPct(current, window) * 100f
        val prev = if (vals.size >= 5) vals[vals.size - 5] else vals.first()
        val trend = when {
            current < prev - 0.05 -> "↓ Falling (Bullish)"
            current > prev + 0.05 -> "↑ Rising (Bearish)"
            else -> "→ Stable"
        }
        return GoldComponentScore(
            name = "Real Yield Pressure (35%)", score = score, label = toLabel(score),
            detail = "${String.format("%.2f", current)}%  $trend",
        )
    }

    private fun scoreUSD(dxy: List<Candle>): GoldComponentScore {
        if (dxy.size < 5) return GoldComponentScore(
            name = "USD Strength (25%)", score = 50f, label = "NEUTRAL",
            detail = if (dxy.isEmpty()) "Fetching DXY..." else "Insufficient data",
            available = dxy.isNotEmpty(),
        )
        val closes = dxy.map { it.close }
        val current = closes.last()
        val window = closes.takeLast(minOf(closes.size, 252))
        val score = invertedPct(current, window) * 100f
        val prev = if (closes.size >= 5) closes[closes.size - 5] else closes.first()
        val trend = when {
            current < prev - 0.1 -> "↓ Weaker (Bullish)"
            current > prev + 0.1 -> "↑ Stronger (Bearish)"
            else -> "→ Stable"
        }
        return GoldComponentScore(
            name = "USD Strength (25%)", score = score, label = toLabel(score),
            detail = "${String.format("%.1f", current)} (DXY)  $trend",
        )
    }

    private fun scoreInflation(obs: List<FredClient.Obs>): GoldComponentScore {
        if (obs.size < 5) return GoldComponentScore(
            name = "Inflation Expectations (15%)", score = 50f, label = "N/A",
            detail = "FRED API key required → Settings", available = false,
        )
        val vals = obs.map { it.value }
        val current = vals.last()
        val window = vals.takeLast(minOf(vals.size, 252))
        val score = directPct(current, window) * 100f
        val prev = if (vals.size >= 5) vals[vals.size - 5] else vals.first()
        val trend = when {
            current > prev + 0.05 -> "↑ Rising (Bullish)"
            current < prev - 0.05 -> "↓ Falling (Bearish)"
            else -> "→ Stable"
        }
        return GoldComponentScore(
            name = "Inflation Expectations (15%)", score = score, label = toLabel(score),
            detail = "${String.format("%.2f", current)}%  $trend",
        )
    }

    fun scoreTechnical(closes: List<Double>): GoldComponentScore {
        if (closes.size < 20) return GoldComponentScore(
            name = "Technical Momentum (5%)", score = 50f, label = "NEUTRAL",
            detail = "Insufficient data", available = false,
        )
        val n = closes.size
        val sma20 = closes.takeLast(20).average()
        val sma50 = if (n >= 50) closes.takeLast(50).average() else null
        val sma200 = if (n >= 200) closes.takeLast(200).average() else null
        val current = closes.last()

        var trendScore = 0f
        if (current > sma20) trendScore += 15f
        if (sma50 != null && sma20 > sma50) trendScore += 15f
        if (sma200 != null && (sma50 ?: sma20) > sma200) trendScore += 10f

        val rsi = computeRsi(closes)
        val rsiScore = when {
            rsi < 30 -> 28f; rsi < 40 -> 25f; rsi < 50 -> 18f
            rsi < 60 -> 22f; rsi < 70 -> 26f; rsi < 80 -> 18f; else -> 10f
        }

        val roc = if (n >= 21) (closes.last() / closes[n - 21] - 1) * 100 else 0.0
        val rocScore = when {
            roc > 5.0 -> 30f; roc > 2.0 -> 25f; roc > 0.0 -> 20f
            roc > -2.0 -> 15f; roc > -5.0 -> 8f; else -> 0f
        }

        val total = (trendScore + rsiScore + rocScore).coerceIn(0f, 100f)
        val dir = if (roc > 1.0) "↑ Uptrend" else if (roc < -1.0) "↓ Downtrend" else "→ Ranging"
        return GoldComponentScore(
            name = "Technical Momentum (5%)", score = total, label = toLabel(total),
            detail = "ROC(20): ${String.format("%.1f", roc)}%  $dir",
        )
    }

    private fun scoreCentralBank(geminiScore: Int?): GoldComponentScore {
        if (geminiScore == null) return GoldComponentScore(
            name = "Central Bank Demand (20%)", score = 50f, label = "N/A",
            detail = "Gemini API key required → Settings", available = false,
        )
        return GoldComponentScore(
            name = "Central Bank Demand (20%)", score = geminiScore.toFloat(),
            label = toLabel(geminiScore.toFloat()), detail = "AI-estimated score (Gemini)",
        )
    }

    // ── Forward Signal (3-6M outlook) — delta-based, macro-weighted ─────────

    private fun computeForwardSignal(inputs: Inputs): Triple<Float, String, List<GoldComponentScore>> {
        val closes = inputs.gldCandles.map { it.close }
        val c1 = scoreRealYieldDelta(inputs.realYield)
        val c2 = scoreUSDDelta(inputs.dxyCandles)
        val c3 = scoreInflationDelta(inputs.inflation, inputs.realYield)
        val c4 = scoreTechnicalLight(closes)
        val components = listOf(c1, c2, c3, c4)
        val weights    = listOf(0.40f, 0.30f, 0.20f, 0.10f)
        var wSum = 0f; var wTotal = 0f
        for ((comp, w) in components.zip(weights)) {
            if (comp.available) { wSum += comp.score * w; wTotal += w }
        }
        val score = if (wTotal > 0f) wSum / wTotal else 50f
        return Triple(score, toLabel(score), components)
    }

    private fun scoreRealYieldDelta(obs: List<FredClient.Obs>): GoldComponentScore {
        if (obs.size < 60) return GoldComponentScore(
            name = "Real Yield Delta (40%)", score = 50f, label = "N/A",
            detail = "FRED API key required → Settings", available = false,
        )
        val vals   = obs.map { it.value }
        val delta  = vals.last() - vals[maxOf(0, vals.size - 63)]  // 3M change; fall = bullish
        val score  = when {
            delta < -0.50 -> 90f;  delta < -0.25 -> 78f;  delta < -0.10 -> 65f
            delta <  0.10 -> 50f;  delta <  0.25 -> 35f;  delta <  0.50 -> 22f
            else          -> 10f
        }
        val dir = if (delta < -0.05) "↓ Falling (Bullish)" else if (delta > 0.05) "↑ Rising (Bearish)" else "→ Stable"
        return GoldComponentScore(
            name = "Real Yield Delta (40%)", score = score, label = toLabel(score),
            detail = "${if (delta >= 0) "+" else ""}${String.format("%.2f", delta)}% (3M)  $dir",
        )
    }

    private fun scoreUSDDelta(dxy: List<Candle>): GoldComponentScore {
        if (dxy.size < 60) return GoldComponentScore(
            name = "USD Delta (30%)", score = 50f, label = "NEUTRAL",
            detail = if (dxy.isEmpty()) "Fetching DXY..." else "Insufficient data",
            available = dxy.isNotEmpty(),
        )
        val closes  = dxy.map { it.close }
        val deltaPct = (closes.last() / closes[maxOf(0, closes.size - 63)] - 1) * 100  // fall = bullish
        val score   = when {
            deltaPct < -3.0 -> 90f;  deltaPct < -1.5 -> 77f;  deltaPct < -0.5 -> 63f
            deltaPct <  0.5 -> 50f;  deltaPct <  1.5 -> 37f;  deltaPct <  3.0 -> 23f
            else            -> 10f
        }
        val dir = if (deltaPct < -0.5) "↓ Weaker (Bullish)" else if (deltaPct > 0.5) "↑ Stronger (Bearish)" else "→ Stable"
        return GoldComponentScore(
            name = "USD Delta (30%)", score = score, label = toLabel(score),
            detail = "${if (deltaPct >= 0) "+" else ""}${String.format("%.1f", deltaPct)}% (3M)  $dir",
        )
    }

    private fun scoreInflationDelta(obs: List<FredClient.Obs>, ryObs: List<FredClient.Obs>): GoldComponentScore {
        if (obs.size < 60) return GoldComponentScore(
            name = "Inflation Delta (20%)", score = 50f, label = "N/A",
            detail = "FRED API key required → Settings", available = false,
        )
        val vals  = obs.map { it.value }
        val delta = vals.last() - vals[maxOf(0, vals.size - 63)]  // 3M change; rise = bullish
        val baseScore = when {
            delta >  0.30 -> 85f;  delta >  0.15 -> 72f;  delta >  0.05 -> 60f
            delta > -0.05 -> 50f;  delta > -0.15 -> 38f;  delta > -0.30 -> 25f
            else          -> 12f
        }
        // Mute bullish inflation signal when real yields are ALSO rising (Fed-hawkish regime)
        val ryDelta = if (ryObs.size >= 63) ryObs.last().value - ryObs[maxOf(0, ryObs.size - 63)].value else 0.0
        val muted   = ryDelta > 0.20 && delta > 0.10
        val score   = if (muted) (baseScore * 0.6f).coerceAtLeast(30f) else baseScore
        val dir     = if (delta > 0.05) "↑ Rising (Bullish)" else if (delta < -0.05) "↓ Falling (Bearish)" else "→ Stable"
        val note    = if (muted) " ⚠ muted (yields↑)" else ""
        return GoldComponentScore(
            name = "Inflation Delta (20%)", score = score, label = toLabel(score),
            detail = "${if (delta >= 0) "+" else ""}${String.format("%.2f", delta)}% (3M)  $dir$note",
        )
    }

    private fun scoreTechnicalLight(closes: List<Double>): GoldComponentScore {
        if (closes.size < 60) return GoldComponentScore(
            name = "Technical Trend (10%)", score = 50f, label = "NEUTRAL",
            detail = "Insufficient data", available = false,
        )
        val roc60 = (closes.last() / closes[maxOf(0, closes.size - 61)] - 1) * 100
        val score = when {
            roc60 >  8.0 -> 85f;  roc60 >  5.0 -> 75f;  roc60 >  2.0 -> 63f
            roc60 >  0.0 -> 53f;  roc60 > -2.0 -> 43f;  roc60 > -5.0 -> 30f
            else         -> 18f
        }
        val dir = if (roc60 > 1.0) "↑ Rising" else if (roc60 < -1.0) "↓ Falling" else "→ Flat"
        return GoldComponentScore(
            name = "Technical Trend (10%)", score = score, label = toLabel(score),
            detail = "ROC(60): ${if (roc60 >= 0) "+" else ""}${String.format("%.1f", roc60)}%  $dir",
        )
    }

    // ── Historical chart computation (1-year) ────────────────────────────────

    private fun computeHistorical(inputs: Inputs, days: Int): List<DailyIndexPoint> {
        val candles = inputs.gldCandles
        if (candles.size < 60) return emptyList()

        val ryMap  = buildFredMap(inputs.realYield)
        val infMap = buildFredMap(inputs.inflation)
        val dxyMap = buildCandleMap(inputs.dxyCandles)

        val result   = mutableListOf<DailyIndexPoint>()
        val startIdx = maxOf(50, candles.size - days)

        for (i in startIdx until candles.size) {
            val dateStr  = dateFmt.format(Date(candles[i].datetimeMs))
            val subClose = candles.subList(maxOf(0, i - 251), i + 1).map { it.close }

            // Historical chart uses 4 components (CB excluded — no historical Gemini scores)
            // Weights normalized from: RY 35%, USD 25%, Inf 15%, Tech 5% (sum=80%)
            data class W(val score: Float, val weight: Float)
            val scored = mutableListOf<W>()

            val ryWindow = ryMap.headMap(dateStr, true).values.toList().takeLast(252)
            if (ryWindow.size >= 5) scored.add(W(invertedPct(ryWindow.last(), ryWindow) * 100f, 0.35f))

            val dxyWindow = dxyMap.headMap(dateStr, true).values.toList().takeLast(252)
            if (dxyWindow.size >= 5) scored.add(W(invertedPct(dxyWindow.last(), dxyWindow) * 100f, 0.25f))

            val infWindow = infMap.headMap(dateStr, true).values.toList().takeLast(252)
            if (infWindow.size >= 5) scored.add(W(directPct(infWindow.last(), infWindow) * 100f, 0.15f))

            if (subClose.size >= 20) scored.add(W(scoreTechnical(subClose).score, 0.05f))

            if (scored.isEmpty()) continue
            val wTotal = scored.sumOf { it.weight.toDouble() }.toFloat()
            val wSum   = scored.sumOf { (it.score * it.weight).toDouble() }.toFloat()
            result.add(DailyIndexPoint(candles[i].datetimeMs, wSum / wTotal))
        }
        return result
    }

    // ── Full historical computation for CSV export (max available range) ────

    fun computeHistoricalFull(inputs: Inputs): List<HistoricalRow> {
        val candles = inputs.gldCandles
        if (candles.size < 60) return emptyList()

        val ryMap  = buildFredMap(inputs.realYield)
        val infMap = buildFredMap(inputs.inflation)
        val dxyMap = buildCandleMap(inputs.dxyCandles)

        val result   = mutableListOf<HistoricalRow>()
        val startIdx = 50

        for (i in startIdx until candles.size) {
            val dateStr  = dateFmt.format(Date(candles[i].datetimeMs))
            val subClose = candles.subList(maxOf(0, i - 251), i + 1).map { it.close }

            val ryWindow  = ryMap.headMap(dateStr, true).values.toList().takeLast(252)
            val ryScore   = if (ryWindow.size >= 5) invertedPct(ryWindow.last(), ryWindow) * 100f else null

            val dxyWindow = dxyMap.headMap(dateStr, true).values.toList().takeLast(252)
            val dxyScore  = if (dxyWindow.size >= 5) invertedPct(dxyWindow.last(), dxyWindow) * 100f else null

            val infWindow = infMap.headMap(dateStr, true).values.toList().takeLast(252)
            val infScore  = if (infWindow.size >= 5) directPct(infWindow.last(), infWindow) * 100f else null

            val techScore = if (subClose.size >= 20) scoreTechnical(subClose).score else null

            // Weighted composite — same profile as live index (CB excluded historically)
            data class W(val score: Float, val weight: Float)
            val scored = listOfNotNull(
                ryScore?.let   { W(it, 0.35f) },
                dxyScore?.let  { W(it, 0.25f) },
                infScore?.let  { W(it, 0.15f) },
                techScore?.let { W(it, 0.05f) },
            )
            if (scored.isEmpty()) continue
            val wTotal    = scored.sumOf { it.weight.toDouble() }.toFloat()
            val composite = scored.sumOf { (it.score * it.weight).toDouble() }.toFloat() / wTotal

            result.add(HistoricalRow(candles[i].datetimeMs, composite, ryScore, dxyScore, infScore, techScore))
        }
        return result
    }

    fun toCsv(rows: List<HistoricalRow>): String {
        if (rows.isEmpty()) return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val firstDate = fmt.format(Date(rows.first().dateMs))
        val lastDate  = fmt.format(Date(rows.last().dateMs))
        val sb = StringBuilder()
        sb.appendLine("# Gold Index History | GLD · DXY · FRED DFII10 · FRED T10YIE | $firstDate to $lastDate")
        sb.appendLine("# Weights: Real Yield 35%, USD 25%, Inflation 15%, Technical 5% (CB Demand excluded — Gemini real-time only)")
        sb.appendLine("Date,Gold Index,Real Yield Score (35%),USD Score (25%),Inflation Score (15%),Technical Score (5%)")
        for (r in rows) {
            sb.append(fmt.format(Date(r.dateMs))).append(',')
            sb.append(String.format("%.1f", r.composite)).append(',')
            sb.append(r.realYield?.let { String.format("%.1f", it) } ?: "").append(',')
            sb.append(r.usd?.let { String.format("%.1f", it) } ?: "").append(',')
            sb.append(r.inflation?.let { String.format("%.1f", it) } ?: "").append(',')
            sb.appendLine(r.technical?.let { String.format("%.1f", it) } ?: "")
        }
        return sb.toString()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun computeRsi(closes: List<Double>, period: Int = 14): Double {
        if (closes.size <= period) return 50.0
        var ag = 0.0; var al = 0.0
        for (i in closes.size - period until closes.size) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) ag += d else al -= d
        }
        ag /= period; al /= period
        return if (al == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + ag / al))
    }

    private fun invertedPct(current: Double, window: List<Double>): Float {
        if (window.isEmpty()) return 0.5f
        return (window.count { it > current }.toFloat() / window.size).coerceIn(0.05f, 0.95f)
    }

    private fun directPct(current: Double, window: List<Double>): Float {
        if (window.isEmpty()) return 0.5f
        return (window.count { it < current }.toFloat() / window.size).coerceIn(0.05f, 0.95f)
    }

    fun toLabel(score: Float) = when {
        score >= 70f -> "BULLISH"
        score >= 45f -> "NEUTRAL"
        else -> "BEARISH"
    }

    private fun buildFredMap(obs: List<FredClient.Obs>): TreeMap<String, Double> {
        val map = TreeMap<String, Double>()
        for (o in obs) map[o.dateStr] = o.value
        return map
    }

    private fun buildCandleMap(candles: List<Candle>): TreeMap<String, Double> {
        val map = TreeMap<String, Double>()
        for (c in candles) map[dateFmt.format(Date(c.datetimeMs))] = c.close
        return map
    }

    private fun emptyReport() = GoldIndexReport(50f, "NEUTRAL", emptyList(), emptyList(), System.currentTimeMillis())
}
