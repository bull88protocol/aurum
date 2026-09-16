package com.sun.aurum.data

import android.content.Context
import com.sun.aurum.domain.gold.GoldDriversEngine
import com.sun.aurum.domain.gold.GoldIndexEngine
import com.sun.aurum.model.DriversReport
import com.sun.aurum.model.FredObs
import com.sun.aurum.model.GoldIndexReport
import com.sun.aurum.model.QuoteData
import com.sun.aurum.model.SymbolState
import com.sun.aurum.network.CentralBankClient
import com.sun.aurum.network.FredClient
import com.sun.aurum.network.GeminiClient
import com.sun.aurum.network.GoogleSheetsClient
import com.sun.aurum.network.YahooFinanceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DataRepository(private val context: Context) {

    private val sheets = GoogleSheetsClient()
    private val yahoo  = YahooFinanceClient()
    private val gemini = GeminiClient()
    private val fred   = FredClient()

    /**
     * Fetches all data for [symbols], calling [onState] for each symbol as it completes.
     * Returns the (possibly updated) Google Sheet ID, or null if not using Google.
     * [forceGemini] bypasses the 8-hour Gemini cache (used by the 9 AM worker for a new day).
     * [fredFeed] is the hosted FRED feed (FredFeedClient). Only the daily report worker passes it;
     * any series it lacks, and every series when it is null, comes from FRED with [fredKey].
     */
    suspend fun fetchAll(
        symbols: List<String>,
        accessToken: String?,
        sheetId: String?,
        geminiKey: String,
        fredKey: String = "",
        fredFeed: Map<String, List<FredObs>>? = null,
        forceGemini: Boolean = false,
        onState: (SymbolState) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        var updatedSheetId: String? = sheetId
        // The displayed quote always comes from Yahoo (near-real-time + pre/after-hours). When the
        // user is signed in we still maintain their own "sync" Sheet, but no longer read its
        // delayed GOOGLEFINANCE values back for display (GOOGLEFINANCE lags ~20m and has no
        // extended hours, so it was making the quote worse, not better).
        // Everything before the per-symbol loop used to run unguarded, so a failure here took
        // down the whole refresh before a single symbol had been attempted — and this Sheets
        // call only runs when signed in, which is why the hang looked login-specific. Sync is
        // optional; it must never block the quotes.
        if (accessToken != null) {
            try {
                val result = sheets.fetchLiveQuotes(accessToken, sheetId)
                if (result.sheetId != sheetId) updatedSheetId = result.sheetId
            } catch (e: Exception) { /* sync is best-effort — keep the saved id and carry on */ }
        }

        for (symbol in symbols) {
            try {
                onState(buildSymbolState(symbol, geminiKey, fredKey, fredFeed, forceGemini))
            } catch (e: Exception) {
                onState(SymbolState(symbol = symbol, loading = false, error = e.message ?: "Error"))
            }
        }
        updatedSheetId
    }

    /** Fetches a single symbol using Yahoo Finance + Gemini brief (cache or fresh). */
    suspend fun fetchSymbol(symbol: String, geminiKey: String, fredKey: String = ""): SymbolState =
        withContext(Dispatchers.IO) {
            buildSymbolState(symbol, geminiKey, fredKey, fredFeed = null, forceGemini = false)
        }

    /**
     * Fetches one symbol from Yahoo (quote + intraday + daily candles), runs the gold engines when it
     * is GLD (the Gold Index and the 20-day drivers), and assembles its [SymbolState]. Shared by
     * [fetchAll] (batch refresh) and [fetchSymbol] (single-tab refresh). [forceGemini] bypasses the
     * Gemini cache so a new day gets a fresh briefing. [fredFeed] is the hosted FRED feed, passed only
     * by the report worker.
     */
    private suspend fun buildSymbolState(
        symbol: String,
        geminiKey: String,
        fredKey: String,
        fredFeed: Map<String, List<FredObs>>?,
        forceGemini: Boolean,
    ): SymbolState {
        val (yahooQuote, intraday) = yahoo.fetchIntraday(symbol)
        val candles = yahoo.fetchDailyCandles(symbol)

        // Gemini brief/news is gold-only (the AI Brief & News tabs are about gold).
        val geminiResult = if (symbol == "GLD" && geminiKey.isNotBlank()) {
            val cached = if (!forceGemini) GeminiCache.load(context, symbol) else null
            cached ?: gemini.fetchAnalysisAndNews(symbol, geminiKey, yahooQuote)?.also { fresh ->
                GeminiCache.save(context, symbol, fresh)
            }
        } else null

        var goldIndexReport: GoldIndexReport? = null
        var driversReport: DriversReport? = null
        if (symbol == "GLD") {
            // DXY feeds the Gold Index's USD component and the drivers' dollar leg. A failure here
            // only blanks those two reads, never the whole gold state.
            val dxyCandles = try { yahoo.fetchDailyCandles("DX-Y.NYB") } catch (e: Exception) { emptyList() }
            fun yearsAgo(n: Int): String {
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.YEAR, -n)
                return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
            }
            // The hosted feed (report worker only) wins when it has the series; otherwise FRED with
            // the user's own key. The feed carries the same windows fetched here.
            fun fredSeries(id: String, years: Int, limit: Int = 1000): List<FredObs> =
                fredFeed?.get(id)?.takeIf { it.isNotEmpty() }
                    ?: if (fredKey.isNotBlank()) fred.fetchSeries(id, fredKey, startDate = yearsAgo(years), limit = limit)
                       else emptyList()
            // DFII10 needs >= 5y so the forward signal's rolling 5y percentile has a full window
            // (~250 obs/yr; the default fetch limit of 1000 would silently cap it at ~4y).
            val realYield = fredSeries("DFII10", years = 6, limit = 2000)
            val inflation = fredSeries("T10YIE", years = 3)
            val dgs2 = fredSeries("DGS2", years = 3)
            val cbQuarterly = CentralBankClient.loadCached(context)
            val inputs = GoldIndexEngine.Inputs(
                gldCandles        = candles,
                dxyCandles        = dxyCandles,
                realYield         = realYield,
                inflation         = inflation,
                cbQuarterly       = cbQuarterly,
                dgs2              = dgs2,
            )
            goldIndexReport = GoldIndexEngine.compute(inputs)
            driversReport = GoldDriversEngine.compute(GoldDriversEngine.Inputs(candles, dxyCandles, realYield))
        }

        return SymbolState(
            symbol               = symbol,
            loading              = false,
            error                = if (yahooQuote == null && candles.isEmpty()) "Failed to fetch data" else null,
            quote                = yahooQuote,
            intradayPoints       = intraday,
            news                 = geminiResult?.news ?: emptyList(),
            lastUpdated          = System.currentTimeMillis(),
            usingGoogleData      = false,   // quote is always Yahoo now; sign-in is sync-only
            goldIndexReport      = goldIndexReport,
            driversReport        = driversReport,
            geminiSignal         = geminiResult?.signal,
            geminiScore          = geminiResult?.score,
            geminiDescription    = geminiResult?.description,
            geminiKeyFactors     = geminiResult?.keyFactors ?: emptyList(),
            geminiYesterdayRecap = geminiResult?.yesterdayRecap,
            geminiTodayOutlook   = geminiResult?.todayOutlook,
            lastSessionLabel     = geminiResult?.lastSessionLabel,
            nextSessionLabel     = geminiResult?.nextSessionLabel,
        )
    }

    fun loadCache(): Map<String, SymbolState>? = DataCache.load(context)

    fun saveCache(states: Map<String, SymbolState>) = DataCache.save(context, states)

    fun clearCache() {
        DataCache.clear(context)
        GeminiCache.clear(context)
        // Also drop the weekly CB-feed cache so "Clear Cache" genuinely fetches everything fresh
        // (its dialog promises as much) — e.g. to pull a corrected WGC quarterly feed immediately
        // instead of after the 7-day TTL. Invalidate (not delete) keeps the last-good copy offline.
        CentralBankCache.invalidate(context)
    }
}
