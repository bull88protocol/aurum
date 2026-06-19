package com.sun.aurum.network

import com.sun.aurum.model.QuoteData
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reads live GOOGLEFINANCE() data from a Google Sheet owned by the signed-in user.
 *
 * On first call the app creates a sheet titled "Aurum Market Data" and
 * writes GOOGLEFINANCE formulas for GLD and VIX (INDEXCBOE:VIX).
 * On subsequent calls it just reads the already-populated values.
 *
 * Sheet layout (Quotes tab, rows 1-3):
 *   Row 1 : headers
 *   Row 2 : GLD   | price | change | (unused) | high | low | open | volume | prevClose
 *   Row 3 : VIX   | price
 *
 * VIX is kept for the HMAI engine (a v2.0 second instrument); the Gold Index itself
 * doesn't consume it.
 */
class GoogleSheetsClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class SheetsResult(
        val sheetId: String,
        val quotes: Map<String, QuoteData>,
        val vix: Double?,
    )

    // GOOGLEFINANCE exchange-qualified tickers
    private val GF_TICKER = mapOf(
        "GLD" to "NYSE:GLD",
    )
    private val SYMBOLS = listOf("GLD")

    /**
     * Fetches live quotes using the provided OAuth [token].
     * Passes [savedSheetId] to skip re-creation; returns the (possibly new) sheet ID.
     */
    fun fetchLiveQuotes(token: String, savedSheetId: String?): SheetsResult {
        // Try existing sheet first
        if (!savedSheetId.isNullOrBlank()) {
            val data = tryRead(token, savedSheetId)
            if (data != null) return SheetsResult(savedSheetId, data.first, data.second)
        }
        // Sheet missing or deleted — create a fresh one
        val newId = createSheet(token)
        writeFormulas(token, newId)
        Thread.sleep(3500)   // give Google a moment to evaluate GOOGLEFINANCE()
        val data = tryRead(token, newId)
        return SheetsResult(newId, data?.first ?: emptyMap(), data?.second)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun tryRead(token: String, sheetId: String): Pair<Map<String, QuoteData>, Double?>? {
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$sheetId" +
                "/values/Quotes!A2:I3?valueRenderOption=UNFORMATTED_VALUE"
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404 || resp.code == 403) return null
                if (!resp.isSuccessful) return null
                parseRows(JSONObject(resp.body!!.string()))
            }
        } catch (e: Exception) { null }
    }

    private fun parseRows(json: JSONObject): Pair<Map<String, QuoteData>, Double?> {
        val rows = json.optJSONArray("values") ?: return Pair(emptyMap(), null)
        val quotes = mutableMapOf<String, QuoteData>()
        var vix: Double? = null

        for (i in 0 until rows.length()) {
            val row = rows.getJSONArray(i)
            val sym = row.optString(0, "")

            if (sym == "VIX") {
                val v = row.optDouble(1, -1.0)
                if (v > 0) vix = v
                continue
            }

            val price     = row.optDouble(1, -1.0); if (price <= 0) continue
            val change    = row.optDouble(2, 0.0)
            val prevClose = row.optDouble(8, -1.0).let { if (it > 0) it else price - change }
            val changePct = if (prevClose > 0) change / prevClose * 100.0 else 0.0
            val high      = row.optDouble(4, price).let { if (it > 0) it else price }
            val low       = row.optDouble(5, price).let { if (it > 0) it else price }
            val open      = row.optDouble(6, price).let { if (it > 0) it else price }
            val volume    = row.optLong(7)

            if (sym in SYMBOLS) {
                quotes[sym] = QuoteData(
                    symbol        = sym,
                    price         = price,
                    change        = change,
                    changePct     = changePct,
                    high          = high,
                    low           = low,
                    open          = open,
                    previousClose = prevClose,
                    volume        = volume,
                )
            }
        }
        return Pair(quotes, vix)
    }

    private fun createSheet(token: String): String {
        val body = JSONObject()
            .put("properties", JSONObject().put("title", "Aurum Market Data"))
            .put("sheets", JSONArray().put(
                JSONObject().put("properties", JSONObject().put("title", "Quotes"))
            ))
        val req = Request.Builder()
            .url("https://sheets.googleapis.com/v4/spreadsheets")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { resp ->
            return JSONObject(resp.body!!.string()).getString("spreadsheetId")
        }
    }

    private fun writeFormulas(token: String, sheetId: String) {
        val values = JSONArray()

        // Header row
        values.put(JSONArray().apply {
            put("Symbol"); put("Price"); put("Change"); put("")
            put("High");   put("Low");   put("Open");  put("Volume"); put("PrevClose")
        })

        // One row per symbol
        for (sym in SYMBOLS) {
            val t = GF_TICKER[sym] ?: sym
            values.put(JSONArray().apply {
                put(sym)
                put("=IFERROR(GOOGLEFINANCE(\"$t\",\"price\"),-1)")
                put("=IFERROR(GOOGLEFINANCE(\"$t\",\"change\"),0)")
                put("")   // changePct computed in app from change/prevClose
                put("=IFERROR(GOOGLEFINANCE(\"$t\",\"high\"),-1)")
                put("=IFERROR(GOOGLEFINANCE(\"$t\",\"low\"),-1)")
                put("=IFERROR(GOOGLEFINANCE(\"$t\",\"open\"),-1)")
                put("=IFERROR(GOOGLEFINANCE(\"$t\",\"volume\"),0)")
                put("=IFERROR(GOOGLEFINANCE(\"$t\",\"closeyest\"),-1)")
            })
        }

        // VIX row
        values.put(JSONArray().apply {
            put("VIX")
            put("=IFERROR(GOOGLEFINANCE(\"INDEXCBOE:VIX\",\"price\"),-1)")
        })

        val body = JSONObject()
            .put("values", values)
            .put("range", "Quotes!A1:I3")
            .put("majorDimension", "ROWS")

        val req = Request.Builder()
            .url("https://sheets.googleapis.com/v4/spreadsheets/$sheetId/values/Quotes!A1:I3?valueInputOption=USER_ENTERED")
            .put(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { /* fire and forget */ }
    }
}
