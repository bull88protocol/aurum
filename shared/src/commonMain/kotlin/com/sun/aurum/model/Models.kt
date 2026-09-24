package com.sun.aurum.model

// ── Raw market data ──────────────────────────────────────────────────────────

data class Candle(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val datetimeMs: Long,
)

data class IntradayPoint(
    val timestampMs: Long,
    val price: Double,
    val volume: Long,
)

data class QuoteData(
    val symbol: String,
    val price: Double,            // live price: pre-market, regular, or after-hours
    val change: Double,
    val changePct: Double,
    val high: Double,
    val low: Double,
    val open: Double?,           // regular-session open; null when it can't be determined
    val previousClose: Double,
    val volume: Long,
    val marketState: String = "REGULAR",       // PRE | REGULAR | POST | POSTPOST | CLOSED
    val regularMarketPrice: Double = 0.0,      // last regular-session close (reference for pre/post)
)

// ── Gemini ───────────────────────────────────────────────────────────────────

data class GeminiResult(
    val signal: String,           // BULLISH / NEUTRAL / BEARISH
    val score: Int,               // 0-100
    val description: String,      // overall sentiment summary
    val keyFactors: List<String>,
    val news: List<NewsItem>,     // top 3, < 7 days old, with URLs
    val yesterdayRecap: String = "",   // what moved the asset + market last session
    val todayOutlook: String = "",     // what could affect next session
    val lastSessionLabel: String = "", // e.g. "March 17" — the last closed trading session
    val nextSessionLabel: String = "", // e.g. "March 18" — the next/upcoming trading session
    val goldCentralBankScore: Int? = null,
)

data class NewsItem(
    val headline: String,
    val summary: String,
    val source: String,
    val url: String = "",
    val date: String = "",   // YYYY-MM-DD
)

// ── Per-symbol UI state ───────────────────────────────────────────────────────

data class SymbolState(
    val symbol: String,
    val loading: Boolean = false,
    val error: String? = null,
    val quote: QuoteData? = null,
    val intradayPoints: List<IntradayPoint> = emptyList(),
    val news: List<NewsItem> = emptyList(),
    val lastUpdated: Long = 0L,
    val usingGoogleData: Boolean = false,  // true = quote sourced from Google Finance via Sheets
    val goldIndexReport: GoldIndexReport? = null,
    val driversReport: DriversReport? = null,
    val geminiSignal: String? = null,
    val geminiScore: Int? = null,
    val geminiDescription: String? = null,
    val geminiKeyFactors: List<String> = emptyList(),
    val geminiYesterdayRecap: String? = null,
    val geminiTodayOutlook: String? = null,
    val lastSessionLabel: String? = null,
    val nextSessionLabel: String? = null,
    /** True while the AI brief is still being fetched, after the market data has already landed. */
    val briefLoading: Boolean = false,
    /** When a feed brief was generated (ISO-8601 UTC). Null for a brief from the user's own key. */
    val briefGeneratedUtc: String? = null,
    /** True when the brief on show came from the hosted feed rather than the user's own key. */
    val briefFromFeed: Boolean = false,
)

// The AI brief is fetched separately from the market data — a grounded Gemini call takes 15-60s and
// must never hold up the Gold Index, the chart or the 20 Days tab. That means two writers updating
// one SymbolState, so each needs to leave the other's fields alone. These two do that.

/** This state's own market data, carrying over the brief fields already on [previous]. */
fun SymbolState.carryingBriefFrom(previous: SymbolState?): SymbolState =
    if (previous == null) this else copy(
        news                 = previous.news,
        geminiSignal         = previous.geminiSignal,
        geminiScore          = previous.geminiScore,
        geminiDescription    = previous.geminiDescription,
        geminiKeyFactors     = previous.geminiKeyFactors,
        geminiYesterdayRecap = previous.geminiYesterdayRecap,
        geminiTodayOutlook   = previous.geminiTodayOutlook,
        lastSessionLabel     = previous.lastSessionLabel,
        nextSessionLabel     = previous.nextSessionLabel,
        briefLoading         = previous.briefLoading,
        briefGeneratedUtc    = previous.briefGeneratedUtc,
        briefFromFeed        = previous.briefFromFeed,
    )

/** This state with [brief] laid over its brief fields, leaving the market data alone. */
fun SymbolState.withBrief(
    brief: GeminiResult?,
    fromFeed: Boolean = false,
    generatedUtc: String? = null,
    loading: Boolean = false,
): SymbolState = copy(
    news                 = brief?.news ?: emptyList(),
    geminiSignal         = brief?.signal,
    geminiScore          = brief?.score,
    geminiDescription    = brief?.description,
    geminiKeyFactors     = brief?.keyFactors ?: emptyList(),
    geminiYesterdayRecap = brief?.yesterdayRecap,
    geminiTodayOutlook   = brief?.todayOutlook,
    lastSessionLabel     = brief?.lastSessionLabel,
    nextSessionLabel     = brief?.nextSessionLabel,
    briefLoading         = loading,
    briefGeneratedUtc    = generatedUtc.takeIf { brief != null },
    briefFromFeed        = fromFeed && brief != null,
)

// ── Gold Index ────────────────────────────────────────────────────────────

/** One quarter of WGC central-bank net gold purchases (tonnes), from the hosted feed. */
data class CbQuarter(
    val year: Int,
    val quarter: Int,   // 1..4
    val tonnes: Double,
)

/** A single FRED series observation (date "yyyy-MM-dd" + value). Shared input to the Gold Index. */
data class FredObs(val dateStr: String, val value: Double)

data class GoldComponentScore(
    val name: String,
    val score: Float,           // 0-100
    val label: String,          // BULLISH / NEUTRAL / BEARISH
    val detail: String,
    val available: Boolean = true,
    // Unavailable because no FRED observations arrived. Since v2.9.0 that means BOTH the
    // user's key (if any) and the hosted feed came back empty, so it is a data/network
    // failure far more often than a missing key — the UI copy reflects that.
    val keyRequired: Boolean = false,
)

data class DailyIndexPoint(
    val dateMs: Long,
    val score: Float,           // 0-100 Gold Index composite, or -100..+100 for the 20-day drivers
)

data class GoldIndexReport(
    val compositeScore: Float,
    val compositeLabel: String,
    val components: List<GoldComponentScore>,
    val historicalScores: List<DailyIndexPoint>,
    val timestamp: Long,
    // Forward Signal (3-6M outlook) — delta-based, macro-weighted
    val forwardScore: Float = 50f,
    val forwardLabel: String = "NEUTRAL",
    val forwardComponents: List<GoldComponentScore> = emptyList(),
)

// ── 20-Day Drivers ───────────────────────────────────────────────────────────

/**
 * One leg of the 20-day drivers read. [score] runs -100 (strong headwind for gold) to +100 (strong
 * tailwind); [goldImpactPct] is the part of gold's own 20-day move this leg accounts for.
 */
data class DriverLeg(
    val name: String,
    val available: Boolean,
    val keyRequired: Boolean = false,  // no FRED observations: neither the user's key nor the feed
    val level: Double = 0.0,           // latest value: DFII10 in %, or the DXY index
    val change: Double = 0.0,          // 20-observation change: percentage points, or % for the dollar
    val score: Float = 0f,
    val goldImpactPct: Double = 0.0,
    val asOf: String = "",             // yyyy-MM-dd of the observation used
)

/** What real yields and the dollar did to gold over the last 20 trading days. Explains; doesn't forecast. */
data class DriversReport(
    val score: Float,                  // -100..+100, mean of the available legs
    val label: String,                 // STRONG TAILWIND / TAILWIND / MIXED / HEADWIND / STRONG HEADWIND
    val legs: List<DriverLeg>,         // [real yields, dollar]
    val goldChangePct: Double?,        // GLD's own 20-day move, %
    val otherPct: Double?,             // the part of that move neither leg accounts for
    val history: List<DailyIndexPoint>,// last ~year of [score], for the chart
) {
    val available: Boolean get() = legs.any { it.available }
}
