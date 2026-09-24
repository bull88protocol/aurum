package com.sun.aurum.data

import android.content.Context
import com.sun.aurum.domain.gold.GoldDriversEngine
import com.sun.aurum.domain.gold.GoldIndexEngine
import com.sun.aurum.model.DriversReport
import com.sun.aurum.model.FredObs
import com.sun.aurum.model.GeminiResult
import com.sun.aurum.model.GoldIndexReport
import com.sun.aurum.model.QuoteData
import com.sun.aurum.model.SymbolState
import com.sun.aurum.network.BriefFeedClient
import com.sun.aurum.network.CentralBankClient
import com.sun.aurum.network.FredClient
import com.sun.aurum.network.FredFeedClient
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
    private val briefFeed = BriefFeedClient()
    private val fredFeedClient = FredFeedClient()

    companion object {
        /**
         * How long a brief from the user's own key counts as current. Inside this window the
         * app neither fetches the feed nor spends another grounded call: the hosted feed is
         * regenerated about hourly, so a fresher own-key brief has nothing to gain from either.
         */
        const val OWN_KEY_FRESH_MS = 60 * 60 * 1000L
    }

    /**
     * Fetches the market data for [symbols], calling [onState] for each symbol as it completes.
     * Returns the (possibly updated) Google Sheet ID, or null if not using Google.
     *
     * The AI brief is NOT fetched here. It used to be, in the middle of this loop, which put a
     * 15-60s grounded Gemini call ahead of the Gold Index, the charts and the 20 Days tab — none
     * of which use it — and was the single biggest reason a refresh felt slow. Callers now load it
     * alongside this, through [loadBrief] and [refreshBriefWithOwnKey].
     *
     * [fredFeed] is a hosted FRED feed the caller already holds — only the report worker passes
     * one, because it fetches the feed for its own staleness reporting. Everyone else leaves it
     * null and [buildSymbolState] downloads one itself, but only if a keyed fetch comes back
     * empty. Either way a user's own key comes first.
     */
    suspend fun fetchAll(
        symbols: List<String>,
        accessToken: String?,
        sheetId: String?,
        fredKey: String = "",
        fredFeed: Map<String, List<FredObs>>? = null,
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
                onState(buildSymbolState(symbol, fredKey, fredFeed))
            } catch (e: Exception) {
                onState(SymbolState(symbol = symbol, loading = false, error = e.message ?: "Error"))
            }
        }
        updatedSheetId
    }

    /** Fetches a single symbol's market data from Yahoo + FRED. The brief is loaded separately. */
    suspend fun fetchSymbol(symbol: String, fredKey: String = ""): SymbolState =
        withContext(Dispatchers.IO) {
            buildSymbolState(symbol, fredKey, fredFeed = null)
        }

    /**
     * Fetches one symbol from Yahoo (quote + intraday + daily candles), runs the gold engines when it
     * is GLD (the Gold Index and the 20-day drivers), and assembles its [SymbolState]. Shared by
     * [fetchAll] (batch refresh) and [fetchSymbol] (single-tab refresh). [fredFeed] is a hosted
     * FRED feed the caller already holds; when null and a keyed fetch comes back empty, one is
     * downloaded here.
     *
     * The returned state's brief fields are all empty — see [fetchAll]. Callers merge a brief in
     * with SymbolState.carryingBriefFrom / withBrief.
     */
    private suspend fun buildSymbolState(
        symbol: String,
        fredKey: String,
        fredFeed: Map<String, List<FredObs>>?,
    ): SymbolState {
        val (yahooQuote, intraday) = yahoo.fetchIntraday(symbol)
        val candles = yahoo.fetchDailyCandles(symbol)

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
            // The user's own key first: a fetch now has FRED's latest print, while the hosted feed
            // is only as fresh as the last GitHub run, and GitHub delays or skips scheduled runs.
            // The feed covers users with no key and any fetch that fails, with the same windows
            // fetched here. fetchSeries returns empty for a blank key or a failure.
            //
            // The feed used to be read by the 6 PM report alone (the decision of 2026-09-16), which
            // left a keyless user's Gold Index blank everywhere except the PDF. It is downloaded
            // here too as of v2.9.0, so "no API key needed" is true of the app and not just the
            // report. This costs FRED nothing: the feed is a static file on GitHub, not an API
            // call, and a user whose own key works never downloads it at all.
            var feed: Map<String, List<FredObs>>? = fredFeed
            var feedTried = fredFeed != null
            fun fromFeed(id: String): List<FredObs> {
                if (!feedTried) { feed = fredFeedClient.fetch(); feedTried = true }
                return feed?.get(id).orEmpty()
            }
            fun fredSeries(id: String, years: Int, limit: Int = 1000): List<FredObs> =
                fred.fetchSeries(id, fredKey, startDate = yearsAgo(years), limit = limit)
                    .ifEmpty { fromFeed(id) }
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
            lastUpdated          = System.currentTimeMillis(),
            usingGoogleData      = false,   // quote is always Yahoo now; sign-in is sync-only
            goldIndexReport      = goldIndexReport,
            driversReport        = driversReport,
        )
    }

    // ── AI brief ──────────────────────────────────────────────────────────────
    // Two stages, because the two sources have very different costs. The hosted feed is a ~200ms
    // static download; a grounded call with the user's own key is 15-60 seconds. The app shows the
    // fast one immediately and swaps in the slow one when it arrives, so nobody waits on a spinner
    // to read a brief. This is the opposite order to the FRED feed, where the user's key is both
    // fast and fresher; here their key is the slow path.

    /** A brief and where it came from. */
    data class Brief(
        val result: GeminiResult,
        val fromFeed: Boolean,
        /** When a feed brief was generated (ISO-8601 UTC); null for a brief from the user's key. */
        val generatedUtc: String? = null,
        /**
         * True when this came from the user's own key recently enough that refetching it would
         * gain nothing. [refreshBriefWithOwnKey] can be skipped, saving a grounded call.
         */
        val ownKeyFresh: Boolean = false,
    )

    /**
     * The fast brief: the hosted feed, or the disk cache. Never calls Gemini, so it is safe to run
     * on every refresh. Null when there is nothing to show — no feed, nothing cached.
     *
     * A recent brief from the user's own key wins over the feed: it was written for this user's
     * refresh rather than up to an hour ago, and replacing it with an older feed brief would look
     * like the tab going backwards. [Brief.ownKeyFresh] marks that case so the caller can skip
     * the expensive refetch entirely.
     */
    suspend fun loadBrief(symbol: String): Brief? = withContext(Dispatchers.IO) {
        val cached = GeminiCache.loadEntry(context, symbol)
        if (cached != null && cached.origin == GeminiCache.Origin.OWN_KEY && cached.ageMs < OWN_KEY_FRESH_MS) {
            return@withContext Brief(cached.result, fromFeed = false, ownKeyFresh = true)
        }
        briefFeed.fetch()?.let { feed ->
            GeminiCache.save(context, symbol, feed.result, GeminiCache.Origin.FEED, feed.generatedUtc)
            return@withContext Brief(feed.result, fromFeed = true, generatedUtc = feed.generatedUtc)
        }
        // Offline, or the feed is down/stale: whatever is still cached beats an empty tab.
        cached?.let {
            Brief(it.result, fromFeed = it.origin == GeminiCache.Origin.FEED, generatedUtc = it.generatedUtc)
        }
    }

    /**
     * The slow brief: one grounded Gemini call with the user's own key, anchored to [quote] so its
     * numbers agree with what the app is showing. Null on a blank key or any failure — in which
     * case whatever [loadBrief] already put on screen simply stays there.
     */
    suspend fun refreshBriefWithOwnKey(symbol: String, geminiKey: String, quote: QuoteData?): Brief? =
        withContext(Dispatchers.IO) {
            if (geminiKey.isBlank()) return@withContext null
            gemini.fetchAnalysisAndNews(symbol, geminiKey, quote)?.let { fresh ->
                GeminiCache.save(context, symbol, fresh, GeminiCache.Origin.OWN_KEY)
                Brief(fresh, fromFeed = false)
            }
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
