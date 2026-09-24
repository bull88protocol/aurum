package com.sun.aurum

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sun.aurum.data.DataRepository
import com.sun.aurum.data.GoogleAuthManager
import com.sun.aurum.data.SecurePrefs
import com.sun.aurum.domain.gold.GoldIndexEngine
import com.sun.aurum.model.SymbolState
import com.sun.aurum.model.carryingBriefFrom
import com.sun.aurum.model.withBrief
import com.sun.aurum.network.CentralBankClient
import com.sun.aurum.network.FredClient
import com.sun.aurum.network.YahooFinanceClient
import com.sun.aurum.report.GoldReportPdf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /**
         * Hard ceiling on a whole market refresh. The per-request callTimeouts bound each HTTP
         * call, but a refresh is ~6 Yahoo calls plus FRED in series, so their worst cases still
         * compound into many minutes of spinner. This turns "loads forever" into "an error and a
         * retry button". Generous on purpose — a healthy refresh takes a few seconds, so only a
         * genuinely stuck refresh ever reaches it. Since v2.9.0 the 15-60s Gemini call is no
         * longer inside this: it runs on its own job with its own ceiling, [BRIEF_TIMEOUT_MS].
         *
         * NB: this frees the UI, not the socket. fetchAll does blocking OkHttp work, which
         * coroutine cancellation cannot interrupt — the orphaned request keeps running until its
         * own callTimeout fires. That is why both fixes are needed.
         */
        const val REFRESH_TIMEOUT_MS = 180_000L
        const val REFRESH_TIMEOUT_MSG =
            "Couldn't reach the market data providers. Check your connection and try again."

        /**
         * Hard ceiling on the AI brief job, which is the hosted feed (30s) and then, when the user
         * has a key, a grounded Gemini call (150s) that waits for the market fetch first. It never
         * holds up the spinner — it exists so a wedged brief job can't sit "loading" forever.
         */
        const val BRIEF_TIMEOUT_MS = 300_000L

        // Gold is the only instrument. All four tabs read GLD's state; the 20 Days tab's drivers
        // report is computed in the same fetch from GLD, DXY and FRED DFII10.
        val SYMBOLS = listOf("GLD")

        fun displayName(symbol: String): String = when (symbol) {
            "GLD" -> "Gold"
            else  -> symbol
        }
    }

    private val prefs      = SecurePrefs(app)
    private val googleAuth = GoogleAuthManager(app)
    private val repo       = DataRepository(app)

    private val _states = MutableStateFlow(SYMBOLS.associate { it to SymbolState(it) })
    val states: StateFlow<Map<String, SymbolState>> = _states.asStateFlow()

    // True when any symbol is loading
    val isRefreshing: StateFlow<Boolean> = _states
        .map { it.values.any { s -> s.loading } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isGoogleSignedIn: Boolean get() = googleAuth.isSignedIn()
    val googleEmail: String?      get() = googleAuth.getEmail()

    val hasGeminiKey: Boolean get() = prefs.geminiApiKey.isNotBlank()
    val hasFredKey: Boolean   get() = prefs.fredApiKey.isNotBlank()

    init {
        // Load cached data instantly (e.g. from the 6 PM background fetch). Keep only current
        // symbols: a cache written before the Dollar tab was retired still holds DX-Y.NYB, and a
        // leftover entry would be marked loading by refresh() and never cleared, an endless spinner.
        repo.loadCache()?.filterKeys { it in SYMBOLS }?.let { _states.value = _states.value + it }
    }

    /** Refresh a single symbol — used when the user taps refresh on a specific tab. */
    fun refreshSymbol(symbol: String) {
        if (_states.value[symbol]?.loading == true) return
        _states.update { it + (symbol to (_states.value[symbol] ?: SymbolState(symbol)).copy(loading = true, error = null)) }
        val market = viewModelScope.launch {
            try {
                val state = repo.fetchSymbol(symbol, prefs.fredApiKey)
                // carryingBriefFrom: the brief job owns those fields and may already have filled
                // them in, or be about to. A plain assignment here would wipe its work.
                _states.update { it + (symbol to state.carryingBriefFrom(it[symbol])) }
                repo.saveCache(_states.value)
            } catch (e: Exception) {
                _states.update { it + (symbol to it[symbol]!!.copy(loading = false, error = e.message ?: "Error")) }
            }
        }
        refreshBrief(symbol, market)
    }

    /** Wipes both on-disk caches then fetches everything fresh, brief included. */
    fun clearCacheAndRefresh() {
        repo.clearCache()
        refresh()
    }

    /**
     * Refresh all symbols at once: the market data and the AI brief on two independent jobs.
     *
     * They are split because the brief used to be fetched in the middle of the market loop, which
     * meant a 15-60s grounded Gemini call stood between the user and the Gold Index, the chart and
     * the 20 Days tab — none of which use it. Now the market data lands in its own time and the
     * brief fills itself in. Only the market job drives the pull-to-refresh spinner.
     */
    fun refresh() {
        val anyLoading = _states.value.values.any { it.loading }
        if (anyLoading) return
        _states.update { map -> map.mapValues { (_, v) -> v.copy(loading = true, error = null) } }
        val market = viewModelScope.launch {
            try {
                withTimeout(REFRESH_TIMEOUT_MS) {
                    val accessToken    = googleAuth.getAccessToken()
                    val updatedSheetId = repo.fetchAll(
                        symbols      = SYMBOLS,
                        accessToken  = accessToken,
                        sheetId      = prefs.googleSheetId.ifBlank { null },
                        fredKey      = prefs.fredApiKey,
                    ) { state -> _states.update { it + (state.symbol to state.carryingBriefFrom(it[state.symbol])) } }
                    if (updatedSheetId != null && updatedSheetId != prefs.googleSheetId) {
                        prefs.googleSheetId = updatedSheetId
                    }
                    repo.saveCache(_states.value)
                }
            } catch (e: TimeoutCancellationException) {
                _states.update { map -> map.mapValues { (_, v) ->
                    if (v.loading) v.copy(loading = false, error = REFRESH_TIMEOUT_MSG) else v
                } }
            } catch (e: Exception) {
                _states.update { map -> map.mapValues { (_, v) ->
                    if (v.loading) v.copy(loading = false, error = e.message ?: "Couldn't refresh") else v
                } }
            }
        }
        SYMBOLS.forEach { refreshBrief(it, market) }
    }

    private var briefJob: Job? = null

    /**
     * Loads the AI brief in two stages: the hosted feed (a ~200ms download, so the tab fills
     * almost at once) and then, only when the user has their own Gemini key, a grounded call with
     * that key that replaces it. Stage two waits for [market] so the brief it asks for is anchored
     * to the quote the app is actually showing — the consistency rule v2.6.0 established — and
     * costs nothing visible, because stage one is already on screen.
     *
     * Nothing here touches [SymbolState.loading], so none of it reaches the pull-to-refresh
     * spinner; the tabs show their own small indicator off [SymbolState.briefLoading].
     */
    private fun refreshBrief(symbol: String, market: Job?) {
        if (briefJob?.isActive == true) return
        briefJob = viewModelScope.launch {
            _states.update { it + (symbol to (it[symbol] ?: SymbolState(symbol)).copy(briefLoading = true)) }
            try {
                withTimeout(BRIEF_TIMEOUT_MS) {
                    val fast = repo.loadBrief(symbol)
                    // Whether a slow call is still coming decides if the tab keeps its indicator.
                    val ownKeyToCome = hasGeminiKey && fast?.ownKeyFresh != true
                    if (fast != null) applyBrief(symbol, fast, stillLoading = ownKeyToCome)
                    if (ownKeyToCome) {
                        market?.join()   // anchor the prompt to the freshly fetched quote
                        repo.refreshBriefWithOwnKey(symbol, prefs.geminiApiKey, _states.value[symbol]?.quote)
                            ?.let { applyBrief(symbol, it, stillLoading = false) }
                    }
                }
            } catch (e: Exception) {
                // A brief that won't load is not an error worth a banner — the market data is the
                // app. The tab keeps whatever it had, or shows its own empty state.
            } finally {
                _states.update { it + (symbol to (it[symbol] ?: SymbolState(symbol)).copy(briefLoading = false)) }
                repo.saveCache(_states.value)
            }
        }
    }

    private fun applyBrief(symbol: String, brief: DataRepository.Brief, stillLoading: Boolean) {
        _states.update { map ->
            val current = map[symbol] ?: SymbolState(symbol)
            map + (symbol to current.withBrief(
                brief        = brief.result,
                fromFeed     = brief.fromFeed,
                generatedUtc = brief.generatedUtc,
                loading      = stillLoading,
            ))
        }
    }

    /**
     * Builds the same PDF the 9 AM notification hands over, from whatever the app is currently
     * showing. Returns null when there is no gold data to report on yet.
     */
    suspend fun buildReportPdf(): File? = withContext(Dispatchers.IO) {
        GoldReportPdf.generate(getApplication(), states.value, hasFredKey, hasGeminiKey)
    }

    /**
     * Generates a CSV of the full available Gold Index history (max data from all sources).
     * Returns null if not enough data.
     */
    suspend fun generateGoldIndexHistoryCsv(): String? = withContext(Dispatchers.IO) {
        val fredKey = prefs.fredApiKey
        val yahoo   = YahooFinanceClient()
        val fred    = FredClient()

        val gldLong    = yahoo.fetchMaxDailyCandles("GLD")
        val dxyCandles = yahoo.fetchMaxDailyCandles("DX-Y.NYB")
        // limit must cover the full daily history (DFII10/T10YIE are daily, ~250/yr since 2003);
        // the default limit=1000 truncates to ~2003-2007 and freezes the rolling-window scores.
        val realYield  = if (fredKey.isNotBlank()) fred.fetchSeries("DFII10", fredKey, startDate = "2003-01-01", limit = 20000) else emptyList()
        val inflation  = if (fredKey.isNotBlank()) fred.fetchSeries("T10YIE", fredKey, startDate = "2003-01-01", limit = 20000) else emptyList()
        val cbQuarterly = CentralBankClient.loadCached(getApplication<Application>())

        if (gldLong.size < 60) return@withContext null

        val inputs = GoldIndexEngine.Inputs(
            gldCandles   = gldLong,
            dxyCandles   = dxyCandles,
            realYield    = realYield,
            inflation    = inflation,
            cbQuarterly  = cbQuarterly,
        )
        val rows = GoldIndexEngine.computeHistoricalFull(inputs)
        if (rows.isEmpty()) null else GoldIndexEngine.toCsv(rows)
    }
}
