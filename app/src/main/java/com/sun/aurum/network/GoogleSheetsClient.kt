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
        // Bounds the ENTIRE call. connect/read only bound individual socket
        // operations, so a server that trickles bytes resets them forever and
        // the refresh spins with no upper bound. This is that upper bound.
        .callTimeout(45, TimeUnit.SECONDS)
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
        // Try existing sheet first.
        //
        // NB: only a genuine "the sheet is gone" answer may fall through to creating a new one.
        // This used to recreate whenever the read returned null, and the read returned null on
        // ANY failure — a timeout, a 500, a dropped connection. Every transient blip therefore
        // minted a fresh "Aurum Market Data" spreadsheet in the user's Drive and orphaned the
        // previous one, so a flaky commute could leave a pile of duplicates behind.
        if (!savedSheetId.isNullOrBlank()) {
            when (val r = tryRead(token, savedSheetId)) {
                is ReadResult.Ok -> return SheetsResult(savedSheetId, r.quotes, r.vix)
                // Transient — keep the sheet id and report no quotes. Next refresh retries.
                ReadResult.Failed -> return SheetsResult(savedSheetId, emptyMap(), null)
                // Genuinely gone (404/403) — fall through and recreate.
                ReadResult.Missing -> Unit
            }
        }
        val newId = createSheet(token)
        writeFormulas(token, newId)
        Thread.sleep(3500)   // give Google a moment to evaluate GOOGLEFINANCE()
        val r = tryRead(token, newId)
        return if (r is ReadResult.Ok) SheetsResult(newId, r.quotes, r.vix)
               else SheetsResult(newId, emptyMap(), null)
    }

    /** Distinguishes "the sheet is gone" from "the read failed" — see fetchLiveQuotes. */
    private sealed interface ReadResult {
        data class Ok(val quotes: Map<String, QuoteData>, val vix: Double?) : ReadResult
        object Missing : ReadResult
        object Failed : ReadResult
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun tryRead(token: String, sheetId: String): ReadResult {
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$sheetId" +
                "/values/Quotes!A2:I3?valueRenderOption=UNFORMATTED_VALUE"
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(req).execute().use { resp ->
                // 404 = deleted, 403 = we can no longer reach it under drive.file. Both mean the
                // saved id is unusable and a new sheet is the right answer.
                if (resp.code == 404 || resp.code == 403) return ReadResult.Missing
                if (!resp.isSuccessful) return ReadResult.Failed
                val (quotes, vix) = parseRows(JSONObject(resp.body!!.string()))
                ReadResult.Ok(quotes, vix)
            }
        } catch (e: Exception) { ReadResult.Failed }
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
