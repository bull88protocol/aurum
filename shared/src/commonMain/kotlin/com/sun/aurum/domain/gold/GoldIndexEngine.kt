package com.sun.aurum.domain.gold

import com.sun.aurum.model.Candle
import com.sun.aurum.model.CbQuarter
import com.sun.aurum.model.DailyIndexPoint
import com.sun.aurum.model.FredObs
import com.sun.aurum.model.GoldComponentScore
import com.sun.aurum.model.GoldIndexReport
import com.sun.aurum.util.formatDecimals
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

object GoldIndexEngine {

    private val NY = TimeZone.of("America/New_York")
    // epoch millis -> "yyyy-MM-dd" in the US trading-session zone (matches the prior SimpleDateFormat).
    private fun epochMsToDate(ms: Long): String =
        Instant.fromEpochMilliseconds(ms).toLocalDateTime(NY).date.toString()
    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    // Component weights for the spot index (Real Yield / USD / Central Bank / Inflation / Technical).
    // Reweighted from 35/25/20/15/5 after validating against monthly LBMA gold (2009-2026): the old
    // mix over-weighted real yields (insignificant for forward returns) and starved Technical/CB.
    private const val W_REAL_YIELD = 0.30f
    private const val W_USD        = 0.23f
    private const val W_CENTRAL    = 0.22f
    private const val W_INFLATION  = 0.13f
    private const val W_TECHNICAL  = 0.12f

    data class Inputs(
        val gldCandles: List<Candle>,
        val dxyCandles: List<Candle>,
        val realYield: List<FredObs>,    // FRED DFII10 (fetch >= 5y: the forward signal ranks the level in a 5y window)
        val inflation: List<FredObs>,    // FRED T10YIE
        val centralBankScore: Int? = null,      // deprecated: ignored; CB now from the WGC series below
        val cbQuarterly: List<CbQuarter> = emptyList(),  // hosted WGC quarterly feed; empty → bundled annual
        val dgs2: List<FredObs> = emptyList(),  // FRED DGS2 — Fed-cycle sleeve of the forward signal
    )

    data class HistoricalRow(
        val dateMs: Long,
        val composite: Float,
        val realYield: Float?,
        val usd: Float?,
        val centralBank: Float?,
        val inflation: Float?,
        val technical: Float?,
    )

    fun compute(inputs: Inputs): GoldIndexReport {
        val candles = inputs.gldCandles
        if (candles.size < 30) return emptyReport()
        val closes = candles.map { it.close }

        // Five components — macro-weighted (commodity trader / economist perspective)
        val comp1 = scoreRealYield(inputs.realYield)         // dominant driver
        val comp2 = scoreUSD(inputs.dxyCandles)              // inverse gold-dollar
        val comp3 = scoreCentralBank(inputs.cbQuarterly)     // structural demand (WGC net purchases)
        val comp4 = scoreInflation(inputs.inflation)         // inflation hedge
        val comp5 = scoreTechnical(closes)                   // timing only

        val components = listOf(comp1, comp2, comp3, comp4, comp5)
        // No-dominance guardrail (P1-1c): when none of the FRED/DXY macro drivers are available
        // (no FRED key AND DXY missing), Central Bank — a slow, mostly-static series — would
        // otherwise dominate the headline (~39% of a re-normalized 2-component composite). Halve
        // its effective weight in that degraded case so the live Technical read isn't drowned out
        // and a no-key view isn't dressed up as a confident macro call.
        val macroAvailable = comp1.available || comp2.available || comp4.available
        val cbWeight = if (!macroAvailable && comp3.available) W_CENTRAL * 0.5f else W_CENTRAL
        val weightPairs = listOf(
            comp1 to W_REAL_YIELD, comp2 to W_USD, comp3 to cbWeight,
            comp4 to W_INFLATION, comp5 to W_TECHNICAL,
        )
        var wSum = 0f; var wTotal = 0f
        for ((comp, w) in weightPairs) {
            if (comp.available) { wSum += comp.score * w; wTotal += w }
        }
        val composite = if (wTotal > 0f) wSum / wTotal else 50f

        val historical = computeHistorical(inputs, minOf(candles.size - 1, 252))
        val (fwdScore, fwdLabel, fwdComponents) = computeForwardSignal(inputs)

        return GoldIndexReport(
            compositeScore    = composite,
            compositeLabel    = toConditionsLabel(composite),
            components        = components,
            historicalScores  = historical,
            timestamp         = nowMs(),
            forwardScore      = fwdScore,
            forwardLabel      = fwdLabel,
            forwardComponents = fwdComponents,
        )
    }

    // ── Component scorers (current snapshot) ────────────────────────────────

    private fun scoreRealYield(obs: List<FredObs>): GoldComponentScore {
        if (obs.size < 5) return GoldComponentScore(
            name = "Real Yield Pressure", score = 50f, label = "N/A",
            detail = "FRED API key required → Settings", available = false, keyRequired = true,
        )
        val vals = obs.map { it.value }
        val current = vals.last()
        val window = vals.takeLast(minOf(vals.size, 252))
        val score = invertedPct(current, window) * 100f
        val prev = if (vals.size >= 5) vals[vals.size - 5] else vals.first()
        // Direction only — the component's BULLISH/NEUTRAL/BEARISH label already states gold sentiment.
        val trend = when {
            current < prev - 0.05 -> "↓ Falling"
            current > prev + 0.05 -> "↑ Rising"
            else -> "→ Stable"
        }
        return GoldComponentScore(
            name = "Real Yield Pressure", score = score, label = toLabel(score),
            detail = "${formatDecimals(current, 2)}%  $trend",
        )
    }

    private fun scoreUSD(dxy: List<Candle>): GoldComponentScore {
        if (dxy.size < 5) return GoldComponentScore(
            name = "USD Strength", score = 50f, label = "NEUTRAL",
            detail = if (dxy.isEmpty()) "Fetching DXY..." else "Insufficient data",
            available = dxy.isNotEmpty(),
        )
        val closes = dxy.map { it.close }
        val current = closes.last()
        // Blend a structural absolute-DXY level with a rolling 2y percentile. The absolute anchor
        // keeps a historically middling dollar (~100) reading near neutral instead of pinning to an
        // extreme just because DXY moved within a short window (the old 1y-only percentile read 99.8
        // as 9/100). The percentile keeps it responsive to the current regime.
        val window   = closes.takeLast(minOf(closes.size, 504))
        val score    = usdScore(current, window)
        val prev = if (closes.size >= 5) closes[closes.size - 5] else closes.first()
        val trend = when {
            current < prev - 0.1 -> "↓ Weaker"
            current > prev + 0.1 -> "↑ Stronger"
            else -> "→ Stable"
        }
        return GoldComponentScore(
            name = "USD Strength", score = score, label = toLabel(score),
            detail = "${formatDecimals(current, 1)} (DXY)  $trend${freshnessNote(dxy.last().datetimeMs)}",
        )
    }

    private fun scoreInflation(obs: List<FredObs>): GoldComponentScore {
        if (obs.size < 5) return GoldComponentScore(
            name = "Inflation Expectations", score = 50f, label = "N/A",
            detail = "FRED API key required → Settings", available = false, keyRequired = true,
        )
        val vals = obs.map { it.value }
        val current = vals.last()
        val window = vals.takeLast(minOf(vals.size, 252))
        val score = directPct(current, window) * 100f
        val prev = if (vals.size >= 5) vals[vals.size - 5] else vals.first()
        // Direction only — the BULLISH/NEUTRAL/BEARISH label already states gold sentiment.
        val trend = when {
            current > prev + 0.05 -> "↑ Rising"
            current < prev - 0.05 -> "↓ Falling"
            else -> "→ Stable"
        }
        return GoldComponentScore(
            name = "Inflation Expectations", score = score, label = toLabel(score),
            detail = "${formatDecimals(current, 2)}%  $trend",
        )
    }

    fun scoreTechnical(closes: List<Double>): GoldComponentScore {
        if (closes.size < 20) return GoldComponentScore(
            name = "Technical Momentum", score = 50f, label = "NEUTRAL",
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
            name = "Technical Momentum", score = total, label = toLabel(total),
            detail = "ROC(20): ${formatDecimals(roc, 1)}%  $dir",
        )
    }

    // WGC net central-bank gold purchases (tonnes/yr), keyed by the CALENDAR YEAR of the flow.
    // Full-year figures publish in ~Q1 of the FOLLOWING year, so year Y's number only becomes
    // public ~April of Y+1 — cbEffectiveYear() encodes exactly that lag (no look-ahead). Values
    // from WGC Gold Demand Trends; years >= CB_ESTIMATE_FROM_YEAR are round placeholders until the
    // actuals publish. Replaces the prior pinned Gemini estimate (a proven constant bias).
    //
    // LIVE-FEED SEAM (P1-1): cbTonnesEffective() is the single override point for a live quarterly
    // WGC (or hosted-JSON / IMF-reserves-derived) series. See release-2.0/NEXT_RELEASE_PLAN.md.
    // WGC net central-bank purchases (tonnes/yr) by calendar year of the flow, ascending order.
    private val cbByYear: List<Pair<Int, Double>> = listOf(
        2009 to -34.0, 2010 to 79.0, 2011 to 481.0, 2012 to 544.0, 2013 to 409.0,
        2014 to 584.0, 2015 to 580.0, 2016 to 395.0, 2017 to 379.0, 2018 to 656.0,
        2019 to 605.0, 2020 to 255.0, 2021 to 463.0, 2022 to 1082.0, 2023 to 1037.0,
        2024 to 1045.0, 2025 to 863.0, 2026 to 1000.0,
    )
    // First year whose figure is a forward estimate (not yet a published WGC actual).
    private const val CB_ESTIMATE_FROM_YEAR = 2026

    /**
     * Calendar year whose net-purchase figure is publicly known as of (year, month). WGC publishes
     * year Y in ~Q1 of Y+1, so from April Y the latest *known* full year is Y-1; in Jan–Mar Y only
     * Y-2 is out yet. Look-ahead-free: a point in time never consumes a figure published later.
     */
    fun cbEffectiveYear(year: Int, month: Int): Int = if (month >= 4) year - 1 else year - 2

    fun cbTonnesEffective(year: Int, month: Int): Double {
        val effYear = cbEffectiveYear(year, month)
        // exact year, else the latest known year <= effYear, else the earliest known year
        return cbByYear.lastOrNull { it.first <= effYear }?.second ?: cbByYear.first().second
    }

    // ── Live quarterly feed (preferred over the bundled annual series when it covers the date) ──

    // A quarter publishes ~6 weeks after it ends (WGC Gold Demand Trends): Q1(Mar)→May, Q2(Jun)→Aug,
    // Q3(Sep)→Nov, Q4(Dec)→Feb next year. Look-ahead-free: a date never sees a quarter published later.
    private fun cbQuarterPublishedAsOf(q: CbQuarter, year: Int, month: Int): Boolean {
        var pubYear = q.year
        var pubMonth = q.quarter * 3 + 2
        if (pubMonth > 12) { pubMonth -= 12; pubYear += 1 }
        return pubYear < year || (pubYear == year && pubMonth <= month)
    }

    // Trailing-12-month net purchases from the live feed (sum of the last 4 *published* quarters as
    // of the date), or null if fewer than 4 are available → caller uses the bundled annual series.
    fun cbTonnesFromQuarterly(quarterly: List<CbQuarter>, year: Int, month: Int): Double? {
        if (quarterly.isEmpty()) return null
        val published = quarterly.filter { cbQuarterPublishedAsOf(it, year, month) }
            .sortedWith(compareBy({ it.year }, { it.quarter }))
        if (published.size < 4) return null
        return published.takeLast(4).sumOf { it.tonnes }
    }

    private fun cbTonnes(year: Int, month: Int, quarterly: List<CbQuarter>): Double =
        cbTonnesFromQuarterly(quarterly, year, month) ?: cbTonnesEffective(year, month)

    // Fixed domain-anchored map: net purchases (tonnes/yr) -> 0..100 score. Anchored to absolute
    // tonnage bands (net-selling → record-buying), so it is look-ahead-free and stable across
    // regimes — unlike sample min-max scaling, which either leaks the future peak (full-sample) or
    // inflates the calm era (expanding-window).
    private val CB_ANCHORS = listOf(
        -100.0 to 10f, 0.0 to 28f, 300.0 to 45f, 500.0 to 55f,
        800.0 to 72f, 1100.0 to 90f, 1300.0 to 95f,
    )

    fun cbScoreFromTonnes(tonnes: Double): Float = piecewise(tonnes, CB_ANCHORS)

    private fun centralBankScoreAt(dateStr: String, quarterly: List<CbQuarter>): Float =
        cbScoreFromTonnes(cbTonnes(dateStr.substring(0, 4).toInt(), dateStr.substring(5, 7).toInt(), quarterly))

    // Latest published quarter ("2025-Q1") when the live feed drives the score, else the bundled
    // annual label ("2025" actual / "2026 est.") — surfaced so users see how current the CB input is.
    private fun cbAsOfLabel(year: Int, month: Int, quarterly: List<CbQuarter>): String {
        val latestQ = quarterly.filter { cbQuarterPublishedAsOf(it, year, month) }
            .takeIf { it.size >= 4 }
            ?.maxWithOrNull(compareBy({ it.year }, { it.quarter }))
        if (latestQ != null) return "${latestQ.year}-Q${latestQ.quarter}"
        val effYear = cbEffectiveYear(year, month).coerceAtLeast(cbByYear.first().first)
        return if (effYear >= CB_ESTIMATE_FROM_YEAR) "$effYear est." else "$effYear"
    }

    private fun scoreCentralBank(quarterly: List<CbQuarter>): GoldComponentScore {
        val now = Clock.System.now().toLocalDateTime(NY)
        val y = now.year; val m = now.monthNumber
        val tonnes = cbTonnes(y, m, quarterly)
        val score  = cbScoreFromTonnes(tonnes)
        return GoldComponentScore(
            name = "Central Bank Demand", score = score, label = toLabel(score),
            detail = "WGC net buying ≈ ${tonnes.roundToInt()} t/yr · as of ${cbAsOfLabel(y, m, quarterly)}",
        )
    }

    // ── Forward Signal v2 (3-6M outlook) — real-rate regime + trend + Fed cycle ──
    //
    // Rebuilt 2026-07 after a full backtest against real 2005-2026 history (see research/README.md
    // for methodology, data and every number). The v1 delta-based signal (RY Δ 0.40 / USD Δ 0.30 /
    // INF Δ 0.20 / ROC60 0.10) measured Spearman IC ≈ -0.05 vs forward 63-trading-day gold returns
    // (monthly obs, 2005-2026) — its BEARISH months were followed by BETTER returns (+3.2%) than
    // its BULLISH months (+2.7%). All three of its macro deltas were individually ~zero or
    // wrong-signed as predictors.
    //
    // What did survive validation, era by era (2005-12 / 13-18 / 19-21 / 22-26 all positive):
    //   * Real-yield LEVEL, oriented HIGH = bullish (IC +0.42 train AND test at 63d, +0.70 at
    //     126d test): high real yields = restrictive peak → market prices future easing, and
    //     gold positioning is washed out. Mapped 0.5 fixed anchors + 0.5 rolling 5y percentile
    //     (the pure fixed map mislabels QE eras like 2019-21; the blend keeps ranks stable).
    //   * 12M price trend (ROC252): weak alone (mildly negative within pre-2022 eras) but adds
    //     robustness as a 0.25 sleeve.
    //   * Fed cycle (DGS2 63-obs change, falling = easing = bullish): flat pre-2019, IC -0.24
    //     (right sign) since. Small 0.20 sleeve.
    // Composite v2: IC +0.29 train [CI +0.04,+0.47], +0.38 test [CI +0.20,+0.57], +0.33 full
    // [CI +0.17,+0.49]; positive in all four eras at both horizons. Kept OUT, with measured
    // reasons: USD Δ (test IC +0.11 wrong-signed vs its bullish orientation), inflation Δ
    // (IC ≈ -0.14 BOTH halves — rising breakevens preceded weaker gold), ROC60 (~0.00), and
    // CB demand (sign flips: -0.31 train / +0.33 test — structural level, not a timing signal;
    // it stays in the spot index).
    private const val W_FWD_REAL_RATE = 0.55f
    private const val W_FWD_TREND    = 0.25f
    private const val W_FWD_FED      = 0.20f

    private fun computeForwardSignal(inputs: Inputs): Triple<Float, String, List<GoldComponentScore>> {
        val c1 = scoreRealRateRegime(inputs.realYield)
        val c2 = scoreTrend12M(inputs.gldCandles.map { it.close })
        val c3 = scoreFedCycle(inputs.dgs2)
        val components = listOf(c1, c2, c3)
        val weights    = listOf(W_FWD_REAL_RATE, W_FWD_TREND, W_FWD_FED)
        var wSum = 0f; var wTotal = 0f
        for ((comp, w) in components.zip(weights)) {
            if (comp.available) { wSum += comp.score * w; wTotal += w }
        }
        val score = if (wTotal > 0f) wSum / wTotal else 50f
        return Triple(score, toLabel(score), components)
    }

    // DFII10 level -> 3-6M gold score, HIGH yields = bullish (rate-cut runway + washed-out
    // positioning). Anchors span the 2003-2026 DFII10 range (-1.2%..3.2%).
    private val RY_REGIME_ANCHORS = listOf(
        -1.0 to 12f, -0.5 to 22f, 0.0 to 32f, 0.5 to 42f, 1.0 to 52f,
        1.5 to 62f, 2.0 to 74f, 2.5 to 86f, 3.0 to 92f,
    )

    // 12M price ROC (%) -> score. Deliberately flat-ish: trend is the diversifying sleeve.
    private val TREND_ANCHORS = listOf(
        -25.0 to 15f, -10.0 to 30f, 0.0 to 45f, 10.0 to 58f,
        20.0 to 70f, 35.0 to 82f, 50.0 to 90f,
    )

    // DGS2 3M change (pct pts) -> score, FALLING 2y yields (easing) = bullish.
    private val FED_ANCHORS = listOf(
        -2.0 to 90f, -1.0 to 75f, -0.25 to 60f, 0.25 to 50f, 1.0 to 35f, 2.0 to 15f,
    )

    /** Real-rate regime: 0.5 × fixed level anchors + 0.5 × rolling 5y percentile of DFII10.
     *  The percentile half needs >= 504 obs (2y) to be meaningful; below that, fixed-only. */
    private fun scoreRealRateRegime(obs: List<FredObs>): GoldComponentScore {
        if (obs.size < 5) return GoldComponentScore(
            name = "Real-Rate Regime", score = 50f, label = "N/A",
            detail = "FRED API key required → Settings", available = false, keyRequired = true,
        )
        val vals = obs.map { it.value }
        val current = vals.last()
        val fixed = piecewise(current, RY_REGIME_ANCHORS)
        val window = vals.takeLast(minOf(vals.size, 1260))
        val score = if (window.size >= 504) {
            0.5f * fixed + 0.5f * directPct(current, window) * 100f
        } else fixed
        val pctNote = if (window.size >= 504)
            " · ${(directPct(current, window) * 100).roundToInt()}th pct (5y)" else ""
        val stance = if (current >= 1.5) "restrictive → cut runway (Bullish)"
                     else if (current <= 0.0) "easy money already in (Bearish)" else "mid-cycle"
        return GoldComponentScore(
            name = "Real-Rate Regime", score = score, label = toLabel(score),
            detail = "${formatDecimals(current, 2)}%$pctNote  $stance",
        )
    }

    private fun scoreTrend12M(closes: List<Double>): GoldComponentScore {
        if (closes.size < 253) return GoldComponentScore(
            name = "12M Trend", score = 50f, label = "NEUTRAL",
            detail = "Needs 12 months of price history", available = false,
        )
        val roc = (closes.last() / closes[closes.size - 253] - 1) * 100
        val score = piecewise(roc, TREND_ANCHORS)
        val dir = if (roc > 2.0) "↑ Uptrend" else if (roc < -2.0) "↓ Downtrend" else "→ Flat"
        return GoldComponentScore(
            name = "12M Trend", score = score, label = toLabel(score),
            detail = "ROC(252): ${if (roc >= 0) "+" else ""}${formatDecimals(roc, 1)}%  $dir",
        )
    }

    private fun scoreFedCycle(obs: List<FredObs>): GoldComponentScore {
        if (obs.size < 60) return GoldComponentScore(
            name = "Fed Cycle (2Y)", score = 50f, label = "N/A",
            detail = "FRED API key required → Settings", available = false, keyRequired = true,
        )
        val vals = obs.map { it.value }
        val delta = vals.last() - vals[maxOf(0, vals.size - 63)]  // 3M change; fall = easing = bullish
        val score = piecewise(delta, FED_ANCHORS)
        val dir = if (delta < -0.10) "↓ Easing (Bullish)" else if (delta > 0.10) "↑ Tightening (Bearish)" else "→ On hold"
        return GoldComponentScore(
            name = "Fed Cycle (2Y)", score = score, label = toLabel(score),
            detail = "${if (delta >= 0) "+" else ""}${formatDecimals(delta, 2)}% (3M)  $dir",
        )
    }

    // ── Historical chart computation (1-year) ────────────────────────────────

    private fun computeHistorical(inputs: Inputs, days: Int): List<DailyIndexPoint> {
        val candles = inputs.gldCandles
        if (candles.size < 60) return emptyList()

        val ryMap  = buildFredSeries(inputs.realYield)
        val infMap = buildFredSeries(inputs.inflation)
        val dxyMap = buildCandleSeries(inputs.dxyCandles)

        val result   = mutableListOf<DailyIndexPoint>()
        val startIdx = maxOf(50, candles.size - days)

        for (i in startIdx until candles.size) {
            val dateStr  = epochMsToDate(candles[i].datetimeMs)
            val subClose = candles.subList(maxOf(0, i - 251), i + 1).map { it.close }

            // Historical chart uses the full 5-component basis — same as the live index. CB comes
            // from the WGC net-purchase series (fixed-anchor), so live and history are comparable.
            data class W(val score: Float, val weight: Float)
            val scored = mutableListOf<W>()

            val ryWindow = ryMap.window(dateStr, 252)
            if (ryWindow.size >= 5) scored.add(W(invertedPct(ryWindow.last(), ryWindow) * 100f, W_REAL_YIELD))

            val dxyWindow = dxyMap.window(dateStr, 504)
            if (dxyWindow.size >= 5) scored.add(W(usdScore(dxyWindow.last(), dxyWindow), W_USD))

            scored.add(W(centralBankScoreAt(dateStr, inputs.cbQuarterly), W_CENTRAL))

            val infWindow = infMap.window(dateStr, 252)
            if (infWindow.size >= 5) scored.add(W(directPct(infWindow.last(), infWindow) * 100f, W_INFLATION))

            if (subClose.size >= 20) scored.add(W(scoreTechnical(subClose).score, W_TECHNICAL))

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

        val ryMap  = buildFredSeries(inputs.realYield)
        val infMap = buildFredSeries(inputs.inflation)
        val dxyMap = buildCandleSeries(inputs.dxyCandles)

        val result   = mutableListOf<HistoricalRow>()
        val startIdx = 50

        for (i in startIdx until candles.size) {
            val dateStr  = epochMsToDate(candles[i].datetimeMs)
            val subClose = candles.subList(maxOf(0, i - 251), i + 1).map { it.close }

            val ryWindow  = ryMap.window(dateStr, 252)
            val ryScore   = if (ryWindow.size >= 5) invertedPct(ryWindow.last(), ryWindow) * 100f else null

            val dxyWindow = dxyMap.window(dateStr, 504)
            val dxyScore  = if (dxyWindow.size >= 5) usdScore(dxyWindow.last(), dxyWindow) else null

            val infWindow = infMap.window(dateStr, 252)
            val infScore  = if (infWindow.size >= 5) directPct(infWindow.last(), infWindow) * 100f else null

            val cbScore   = centralBankScoreAt(dateStr, inputs.cbQuarterly)
            val techScore = if (subClose.size >= 20) scoreTechnical(subClose).score else null

            // Weighted composite — same 5-component profile as the live index.
            data class W(val score: Float, val weight: Float)
            val scored = listOfNotNull(
                ryScore?.let   { W(it, W_REAL_YIELD) },
                dxyScore?.let  { W(it, W_USD) },
                W(cbScore, W_CENTRAL),
                infScore?.let  { W(it, W_INFLATION) },
                techScore?.let { W(it, W_TECHNICAL) },
            )
            if (scored.isEmpty()) continue
            val wTotal    = scored.sumOf { it.weight.toDouble() }.toFloat()
            val composite = scored.sumOf { (it.score * it.weight).toDouble() }.toFloat() / wTotal

            result.add(HistoricalRow(candles[i].datetimeMs, composite, ryScore, dxyScore, cbScore, infScore, techScore))
        }
        return result
    }

    fun toCsv(rows: List<HistoricalRow>): String {
        if (rows.isEmpty()) return ""
        val firstDate = epochMsToDate(rows.first().dateMs)
        val lastDate  = epochMsToDate(rows.last().dateMs)
        val sb = StringBuilder()
        sb.appendLine("# Gold Index History | GLD · DXY · FRED DFII10 · FRED T10YIE · WGC CB net purchases | $firstDate to $lastDate")
        sb.appendLine("# Composite Gold Index (0-100) with its 5 component scores. CB Demand = WGC net central-bank purchases (fixed-anchor).")
        sb.appendLine("Date,Gold Index,Real Yield Score,USD Score,Central Bank Score,Inflation Score,Technical Score")
        for (r in rows) {
            sb.append(epochMsToDate(r.dateMs)).append(',')
            sb.append(formatDecimals(r.composite.toDouble(), 1)).append(',')
            sb.append(r.realYield?.let { formatDecimals(it.toDouble(), 1) } ?: "").append(',')
            sb.append(r.usd?.let { formatDecimals(it.toDouble(), 1) } ?: "").append(',')
            sb.append(r.centralBank?.let { formatDecimals(it.toDouble(), 1) } ?: "").append(',')
            sb.append(r.inflation?.let { formatDecimals(it.toDouble(), 1) } ?: "").append(',')
            sb.appendLine(r.technical?.let { formatDecimals(it.toDouble(), 1) } ?: "")
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

    // Structural DXY level -> gold score (inverse: a strong dollar is bearish for gold). Anchored to
    // the multi-year DXY range (~84-120) so the read reflects where the dollar sits historically,
    // not merely within a short rolling window. Look-ahead-free and stable across regimes.
    private val DXY_ANCHORS = listOf(
        84.0 to 92f, 89.0 to 82f, 93.0 to 70f, 97.0 to 58f, 100.0 to 50f,
        103.0 to 42f, 107.0 to 32f, 113.0 to 18f, 120.0 to 8f,
    )

    private fun dxyLevelScore(dxy: Double): Float = piecewise(dxy, DXY_ANCHORS)

    /** Linear interpolation over (x, score) anchors, clamped to the end scores outside the range. */
    private fun piecewise(x: Double, anchors: List<Pair<Double, Float>>): Float {
        if (x <= anchors.first().first) return anchors.first().second
        if (x >= anchors.last().first) return anchors.last().second
        for (i in 0 until anchors.size - 1) {
            val (x0, y0) = anchors[i]
            val (x1, y1) = anchors[i + 1]
            if (x >= x0 && x <= x1) return y0 + (y1 - y0) * ((x - x0) / (x1 - x0)).toFloat()
        }
        return 50f
    }

    // USD component score: structural DXY level (65%) blended with a rolling percentile (35%).
    private fun usdScore(current: Double, window: List<Double>): Float =
        0.65f * dxyLevelScore(current) + 0.35f * invertedPct(current, window) * 100f

    // Flags a stale feed: appends an age note when the latest candle is more than 5 days old
    // (covers weekends/holidays without false alarms).
    private fun freshnessNote(latestMs: Long): String {
        val ageDays = (nowMs() - latestMs) / 86_400_000L
        return if (ageDays > 5) "  ⚠ ${ageDays}d delayed" else ""
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

    // The spot composite is a NOWCAST, so its headline uses conditions vocabulary, not
    // direction. Backtest 2005-2026 (research/README.md): composite >= 70 was followed by a
    // MEAN -0.6% next-3M return (42% up) — printing "BULLISH" there promised the opposite of
    // what the data delivered. Direction lives exclusively in the Forward Signal.
    fun toConditionsLabel(score: Float) = when {
        score >= 70f -> "HOT"
        score >= 45f -> "MIXED"
        else -> "WEAK"
    }

    private fun buildFredSeries(obs: List<FredObs>): SortedDateSeries {
        val m = mutableMapOf<String, Double>()
        for (o in obs) m[o.dateStr] = o.value   // dedupe by date, last wins (matches the old TreeMap)
        return SortedDateSeries.from(m)
    }

    private fun buildCandleSeries(candles: List<Candle>): SortedDateSeries {
        val m = mutableMapOf<String, Double>()
        for (c in candles) m[epochMsToDate(c.datetimeMs)] = c.close
        return SortedDateSeries.from(m)
    }

    // Date-keyed series with TreeMap.headMap(key, true) semantics in common code: sort once, then each
    // window() is a binary search. Keys are "yyyy-MM-dd" so lexicographic order == chronological.
    private class SortedDateSeries private constructor(
        private val dates: List<String>,
        private val values: List<Double>,
    ) {
        /** Last [n] values whose date <= [key] (== headMap(key, true).values.toList().takeLast(n)). */
        fun window(key: String, n: Int): List<Double> {
            var lo = 0; var hi = dates.size           // find the count of dates <= key
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (dates[mid] <= key) lo = mid + 1 else hi = mid }
            return values.subList(maxOf(0, lo - n), lo)
        }
        companion object {
            fun from(byDate: Map<String, Double>): SortedDateSeries {
                val sorted = byDate.entries.sortedBy { it.key }
                return SortedDateSeries(sorted.map { it.key }, sorted.map { it.value })
            }
        }
    }

    private fun emptyReport() = GoldIndexReport(50f, "MIXED", emptyList(), emptyList(), nowMs())
}
