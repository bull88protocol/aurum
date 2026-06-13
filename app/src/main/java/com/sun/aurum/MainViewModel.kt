package com.sun.aurum

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sun.aurum.data.DataRepository
import com.sun.aurum.data.GoogleAuthManager
import com.sun.aurum.data.SecurePrefs
import com.sun.aurum.domain.gold.GoldIndexEngine
import com.sun.aurum.model.SymbolState
import com.sun.aurum.network.FredClient
import com.sun.aurum.network.YahooFinanceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        // Aurum is gold-first: the hero tab is GLD (the Gold Index). The Search
        // tab lets pro traders pull related instruments (DXY, GDX, miners, etc.).
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
            } catch (e: Exception) {
                _states.update { map -> map.mapValues { (_, v) -> if (v.loading) v.copy(loading = false, error = e.message) else v } }
            }
        }
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

        if (gldLong.size < 60) return@withContext null

        val inputs = GoldIndexEngine.Inputs(
            gldCandles   = gldLong,
            dxyCandles   = dxyCandles,
            realYield    = realYield,
            inflation    = inflation,
        )
        val rows = GoldIndexEngine.computeHistoricalFull(inputs)
        if (rows.isEmpty()) null else GoldIndexEngine.toCsv(rows)
    }
}
