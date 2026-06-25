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
    val open: Double,
    val previousClose: Double,
    val volume: Long,
    val marketState: String = "REGULAR",       // PRE | REGULAR | POST | POSTPOST | CLOSED
    val regularMarketPrice: Double = 0.0,      // last regular-session close (reference for pre/post)
)

// ── HMAI models ───────────────────────────────────────────────────────────────

data class PillarResult(
    val pillar: Int,
    val name: String,
    val score: Double,
    val maxScore: Double,
    val label: String,
    val components: Map<String, Double>,
    val details: String,
)

enum class CbAction { PASS_THROUGH, CAP_50, FORCE_20_30, FORCE_0_10 }

data class CircuitBreakerResult(
    val triggers: List<String>,
    val action: CbAction,
    val description: String,
)

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

data class HmaiReport(
    val symbol: String,
    val composite: Double,
    val compositeLabel: String,
    val pillars: List<PillarResult>,
    val circuitBreaker: CircuitBreakerResult,
    val rawComposite: Double,
    val vixValue: Double?,
    val geminiSignal: String?,
    val geminiScore: Int?,
    val geminiDescription: String?,
    val geminiKeyFactors: List<String>,
    val geminiYesterdayRecap: String? = null,
    val geminiTodayOutlook: String? = null,
    val lastSessionLabel: String? = null,  // e.g. "March 17"
    val nextSessionLabel: String? = null,  // e.g. "March 18"
)

// ── Per-symbol UI state ───────────────────────────────────────────────────────

data class SymbolState(
    val symbol: String,
    val loading: Boolean = false,
    val error: String? = null,
    val quote: QuoteData? = null,
    val intradayPoints: List<IntradayPoint> = emptyList(),
    val hmaiReport: HmaiReport? = null,
    val news: List<NewsItem> = emptyList(),
    val lastUpdated: Long = 0L,
    val usingGoogleData: Boolean = false,  // true = quote sourced from Google Finance via Sheets
    val goldIndexReport: GoldIndexReport? = null,
    val geminiSignal: String? = null,
    val geminiScore: Int? = null,
    val geminiDescription: String? = null,
    val geminiKeyFactors: List<String> = emptyList(),
    val geminiYesterdayRecap: String? = null,
    val geminiTodayOutlook: String? = null,
    val lastSessionLabel: String? = null,
    val nextSessionLabel: String? = null,
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
    val keyRequired: Boolean = false,  // unavailable specifically for lack of a FRED key (vs a data/network failure)
)

data class DailyIndexPoint(
    val dateMs: Long,
    val score: Float,           // 0-100 composite
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
