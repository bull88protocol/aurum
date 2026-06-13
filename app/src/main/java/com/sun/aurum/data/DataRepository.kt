package com.sun.aurum.data

import android.content.Context
import com.sun.aurum.domain.gold.GoldIndexEngine
import com.sun.aurum.domain.hmai.HmaiEngine
import com.sun.aurum.model.QuoteData
import com.sun.aurum.model.SymbolState
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
        val googleQuotes: Map<String, QuoteData>
        var vix: Double?

        if (accessToken != null) {
            val result = sheets.fetchLiveQuotes(accessToken, sheetId)
            if (result.sheetId != sheetId) updatedSheetId = result.sheetId
            googleQuotes = result.quotes
            vix          = result.vix
        } else {
            googleQuotes = emptyMap()
            vix          = yahoo.fetchVix()
        }

        for (symbol in symbols) {
            try {
                val (yahooQuote, intraday) = yahoo.fetchIntraday(symbol)
                val quote   = googleQuotes[symbol] ?: yahooQuote
                val candles = yahoo.fetchDailyCandles(symbol)

                // Use cached Gemini result if fresh; otherwise fetch and cache
                val geminiResult = if (geminiKey.isNotBlank()) {
                    val cached = if (!forceGemini) GeminiCache.load(context, symbol) else null
                    cached ?: gemini.fetchAnalysisAndNews(symbol, geminiKey)?.also { fresh ->
                        GeminiCache.save(context, symbol, fresh)
                    }
                } else null

                val hmai = if (symbol != "GLD" && candles.size >= 50)
                    HmaiEngine.compute(symbol, candles, vix, geminiResult)
                else null

                val goldIndexReport = if (symbol == "GLD") {
                    val dxyCandles = yahoo.fetchDailyCandles("DX-Y.NYB")
                    val threeYearsAgo = run {
                        val cal = java.util.Calendar.getInstance()
                        cal.add(java.util.Calendar.YEAR, -3)
                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
                    }
                    val realYield = if (fredKey.isNotBlank()) fred.fetchSeries("DFII10", fredKey, startDate = threeYearsAgo) else emptyList()
                    val inflation = if (fredKey.isNotBlank()) fred.fetchSeries("T10YIE", fredKey, startDate = threeYearsAgo) else emptyList()
                    val inputs = GoldIndexEngine.Inputs(
                        gldCandles        = candles,
                        dxyCandles        = dxyCandles,
                        realYield         = realYield,
                        inflation         = inflation,
                        centralBankScore  = geminiResult?.goldCentralBankScore,
                    )
                    GoldIndexEngine.compute(inputs)
                } else null

                onState(SymbolState(
                    symbol               = symbol,
                    loading              = false,
                    error                = if (quote == null && candles.isEmpty()) "Failed to fetch data" else null,
                    quote                = quote,
                    intradayPoints       = intraday,
                    hmaiReport           = hmai,
                    news                 = geminiResult?.news ?: emptyList(),
                    lastUpdated          = System.currentTimeMillis(),
                    usingGoogleData      = accessToken != null && googleQuotes.containsKey(symbol),
                    goldIndexReport      = goldIndexReport,
                    geminiSignal         = geminiResult?.signal,
                    geminiScore          = geminiResult?.score,
                    geminiDescription    = geminiResult?.description,
                    geminiKeyFactors     = geminiResult?.keyFactors ?: emptyList(),
                    geminiYesterdayRecap = geminiResult?.yesterdayRecap,
                    geminiTodayOutlook   = geminiResult?.todayOutlook,
                    lastSessionLabel     = geminiResult?.lastSessionLabel,
                    nextSessionLabel     = geminiResult?.nextSessionLabel,
                ))
            } catch (e: Exception) {
                onState(SymbolState(symbol = symbol, loading = false, error = e.message ?: "Error"))
            }
        }
        updatedSheetId
    }

    /** Fetches a single symbol using Yahoo Finance + Gemini brief (cache or fresh). */
    suspend fun fetchSymbol(symbol: String, geminiKey: String, fredKey: String = ""): SymbolState = withContext(Dispatchers.IO) {
        val (yahooQuote, intraday) = yahoo.fetchIntraday(symbol)
        val candles      = yahoo.fetchDailyCandles(symbol)
        val vix          = yahoo.fetchVix()
        val geminiResult = if (geminiKey.isNotBlank()) {
            val cached = GeminiCache.load(context, symbol)
            cached ?: gemini.fetchAnalysisAndNews(symbol, geminiKey)?.also { fresh ->
                GeminiCache.save(context, symbol, fresh)
            }
        } else null
        val hmai = if (symbol != "GLD" && candles.size >= 50)
            HmaiEngine.compute(symbol, candles, vix, geminiResult)
        else null

        val goldIndexReport = if (symbol == "GLD") {
            val dxyCandles = yahoo.fetchDailyCandles("DX-Y.NYB")
            val threeYearsAgo = run {
                val cal = java.util.Calendar.getInstance()
                cal.add(java.util.Calendar.YEAR, -3)
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
            }
            val realYield = if (fredKey.isNotBlank()) fred.fetchSeries("DFII10", fredKey, startDate = threeYearsAgo) else emptyList()
            val inflation = if (fredKey.isNotBlank()) fred.fetchSeries("T10YIE", fredKey, startDate = threeYearsAgo) else emptyList()
            val inputs = GoldIndexEngine.Inputs(
                gldCandles        = candles,
                dxyCandles        = dxyCandles,
                realYield         = realYield,
                inflation         = inflation,
                centralBankScore  = geminiResult?.goldCentralBankScore,
            )
            GoldIndexEngine.compute(inputs)
        } else null

        SymbolState(
            symbol               = symbol,
            loading              = false,
            error                = if (yahooQuote == null && candles.isEmpty()) "Failed to fetch data" else null,
            quote                = yahooQuote,
            intradayPoints       = intraday,
            hmaiReport           = hmai,
            news                 = geminiResult?.news ?: emptyList(),
            lastUpdated          = System.currentTimeMillis(),
            usingGoogleData      = false,
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
    }
}
