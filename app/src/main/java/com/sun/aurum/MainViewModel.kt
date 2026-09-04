package com.sun.aurum

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sun.aurum.data.DataRepository
import com.sun.aurum.data.GoogleAuthManager
import com.sun.aurum.data.SecurePrefs
import com.sun.aurum.domain.gold.GoldIndexEngine
import com.sun.aurum.model.SymbolState
import com.sun.aurum.network.CentralBankClient
import com.sun.aurum.network.FredClient
import com.sun.aurum.network.YahooFinanceClient
import com.sun.aurum.report.GoldReportPdf
import java.io.File
import kotlinx.coroutines.Dispatchers
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
         * Hard ceiling on a whole refresh. The per-request callTimeouts bound each HTTP call, but a
         * refresh is ~6 Yahoo calls plus FRED plus Gemini in series, so their worst cases still
         * compound into many minutes of spinner. This turns "loads forever" into "an error and a
         * retry button". Generous on purpose — a healthy refresh takes a few seconds and Gemini
         * alone is allowed 150s, so only a genuinely stuck refresh ever reaches it.
         *
         * NB: this frees the UI, not the socket. fetchAll does blocking OkHttp work, which
         * coroutine cancellation cannot interrupt — the orphaned request keeps running until its
         * own callTimeout fires. That is why both fixes are needed.
         */
        const val REFRESH_TIMEOUT_MS = 180_000L
        const val REFRESH_TIMEOUT_MSG =
            "Couldn't reach the market data providers. Check your connection and try again."

        // Gold is the hero (GLD → the Gold Index). DX-Y.NYB (the US Dollar Index) is surfaced as a
        // second instrument through the HMAI engine — the dollar is gold's key inverse driver.
        val SYMBOLS = listOf("GLD", "DX-Y.NYB")

        fun displayName(symbol: String): String = when (symbol) {
            "GLD"      -> "Gold"
            "DX-Y.NYB" -> "Dollar (DXY)"
            else       -> symbol
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
        // Load cached data instantly (e.g. from 9 AM background fetch)
        repo.loadCache()?.let { _states.value = it }
    }

    /** Refresh a single symbol — used when the user taps refresh on a specific tab. */
    fun refreshSymbol(symbol: String) {
        if (_states.value[symbol]?.loading == true) return
        _states.update { it + (symbol to (_states.value[symbol] ?: SymbolState(symbol)).copy(loading = true, error = null)) }
        viewModelScope.launch {
            try {
                val state = repo.fetchSymbol(symbol, prefs.geminiApiKey, prefs.fredApiKey)
                _states.update { it + (symbol to state) }
                repo.saveCache(_states.value)
            } catch (e: Exception) {
                _states.update { it + (symbol to it[symbol]!!.copy(loading = false, error = e.message ?: "Error")) }
            }
        }
    }

    /** Wipes both on-disk caches then fetches everything fresh including Gemini. */
    fun clearCacheAndRefresh() {
        repo.clearCache()
        refresh(forceGemini = true)
    }

    /** Refresh all symbols at once. [forceGemini] bypasses the 8-hour Gemini cache (used on auth). */
    fun refresh(forceGemini: Boolean = false) {
        val anyLoading = _states.value.values.any { it.loading }
        if (anyLoading) return
        _states.update { map -> map.mapValues { (_, v) -> v.copy(loading = true, error = null) } }
        viewModelScope.launch {
            try {
                withTimeout(REFRESH_TIMEOUT_MS) {
                    val accessToken    = googleAuth.getAccessToken()
                    val updatedSheetId = repo.fetchAll(
                        symbols      = SYMBOLS,
                        accessToken  = accessToken,
                        sheetId      = prefs.googleSheetId.ifBlank { null },
                        geminiKey    = prefs.geminiApiKey,
                        fredKey      = prefs.fredApiKey,
                        forceGemini  = forceGemini,
                    ) { state -> _states.update { it + (state.symbol to state) } }
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
