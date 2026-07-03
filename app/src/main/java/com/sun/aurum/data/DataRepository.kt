package com.sun.aurum.data

import android.content.Context
import com.sun.aurum.domain.gold.GoldIndexEngine
import com.sun.aurum.domain.hmai.HmaiEngine
import com.sun.aurum.model.Candle
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
     */
    suspend fun fetchAll(
        symbols: List<String>,
        accessToken: String?,
        sheetId: String?,
        geminiKey: String,
        fredKey: String = "",
        forceGemini: Boolean = false,
        onState: (SymbolState) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        var updatedSheetId: String? = sheetId
        // The displayed quote always comes from Yahoo (near-real-time + pre/after-hours). When the
        // user is signed in we still maintain their own "sync" Sheet, but no longer read its
        // delayed GOOGLEFINANCE values back for display (GOOGLEFINANCE lags ~20m and has no
        // extended hours, so it was making the quote worse, not better).
        if (accessToken != null) {
            val result = sheets.fetchLiveQuotes(accessToken, sheetId)
            if (result.sheetId != sheetId) updatedSheetId = result.sheetId
        }
        val vix: Double? = yahoo.fetchVix()

        // DX-Y.NYB daily candles feed both the Dollar tab's HMAI (its own series) and GLD's Gold
        // Index (the USD component) — fetch once here and share across both symbols so a batch
        // refresh hits Yahoo for the dollar history a single time.
        val sharedDxyCandles: List<Candle>? =
            if ("DX-Y.NYB" in symbols || "GLD" in symbols) yahoo.fetchDailyCandles("DX-Y.NYB") else null

        for (symbol in symbols) {
            try {
                onState(buildSymbolState(symbol, geminiKey, fredKey, vix, forceGemini, sharedDxyCandles))
            } catch (e: Exception) {
                onState(SymbolState(symbol = symbol, loading = false, error = e.message ?: "Error"))
            }
        }
        updatedSheetId
    }

    /** Fetches a single symbol using Yahoo Finance + Gemini brief (cache or fresh). */
    suspend fun fetchSymbol(symbol: String, geminiKey: String, fredKey: String = ""): SymbolState =
        withContext(Dispatchers.IO) {
            buildSymbolState(symbol, geminiKey, fredKey, vix = yahoo.fetchVix(), forceGemini = false)
        }

    /**
     * Fetches one symbol from Yahoo (quote + intraday + daily candles), runs the appropriate engine
     * (the Gold Index for GLD, HMAI for the other instruments), and assembles its [SymbolState].
     * Shared by [fetchAll] (batch refresh, reusing one [vix] fetch across symbols) and [fetchSymbol]
     * (single-tab refresh). [vix] feeds HMAI's market-stress read; [forceGemini] bypasses the Gemini
     * cache so a new day gets a fresh briefing. [sharedDxyCandles], when supplied by a batch refresh,
     * is reused as the Dollar tab's own series and as GLD's Gold-Index USD component so DX-Y.NYB is
     * only fetched once per batch.
     */
    private suspend fun buildSymbolState(
        symbol: String,
        geminiKey: String,
        fredKey: String,
        vix: Double?,
        forceGemini: Boolean,
        sharedDxyCandles: List<Candle>? = null,
    ): SymbolState {
        val (yahooQuote, intraday) = yahoo.fetchIntraday(symbol)
        val candles = if (symbol == "DX-Y.NYB" && sharedDxyCandles != null) sharedDxyCandles
                      else yahoo.fetchDailyCandles(symbol)

        // Gemini brief/news is gold-only (the AI Brief & News tabs are about gold). The second
        // instrument (DXY) runs HMAI without it — no wasted AI call, no ticker like "DX-Y.NYB"
        // sent to the model.
        val geminiResult = if (symbol == "GLD" && geminiKey.isNotBlank()) {
            val cached = if (!forceGemini) GeminiCache.load(context, symbol) else null
            cached ?: gemini.fetchAnalysisAndNews(symbol, geminiKey)?.also { fresh ->
                GeminiCache.save(context, symbol, fresh)
            }
        } else null

        val hmai = if (symbol != "GLD" && candles.size >= 50)
            HmaiEngine.compute(symbol, candles, vix, geminiResult)
        else null

        val goldIndexReport = if (symbol == "GLD") {
            val dxyCandles = sharedDxyCandles ?: yahoo.fetchDailyCandles("DX-Y.NYB")
            fun yearsAgo(n: Int): String {
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.YEAR, -n)
                return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
            }
            val threeYearsAgo = yearsAgo(3)
            // DFII10 needs >= 5y so the forward signal's rolling 5y percentile has a full window
            // (~250 obs/yr; the default fetch limit of 1000 would silently cap it at ~4y).
            val realYield = if (fredKey.isNotBlank()) fred.fetchSeries("DFII10", fredKey, startDate = yearsAgo(6), limit = 2000) else emptyList()
            val inflation = if (fredKey.isNotBlank()) fred.fetchSeries("T10YIE", fredKey, startDate = threeYearsAgo) else emptyList()
            val dgs2 = if (fredKey.isNotBlank()) fred.fetchSeries("DGS2", fredKey, startDate = threeYearsAgo) else emptyList()
            val cbQuarterly = CentralBankClient.loadCached(context)
            val inputs = GoldIndexEngine.Inputs(
                gldCandles        = candles,
                dxyCandles        = dxyCandles,
                realYield         = realYield,
                inflation         = inflation,
                cbQuarterly       = cbQuarterly,
                dgs2              = dgs2,
            )
            GoldIndexEngine.compute(inputs)
        } else null

        return SymbolState(
            symbol               = symbol,
            loading              = false,
            error                = if (yahooQuote == null && candles.isEmpty()) "Failed to fetch data" else null,
            quote                = yahooQuote,
            intradayPoints       = intraday,
            hmaiReport           = hmai,
            news                 = geminiResult?.news ?: emptyList(),
            lastUpdated          = System.currentTimeMillis(),
            usingGoogleData      = false,   // quote is always Yahoo now; sign-in is sync-only
            goldIndexReport      = goldIndexReport,
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
