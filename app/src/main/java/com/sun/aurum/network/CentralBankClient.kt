package com.sun.aurum.network

import android.content.Context
import com.sun.aurum.data.CentralBankCache
import com.sun.aurum.model.CbQuarter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches the hosted WGC central-bank net-purchase series (quarterly, tonnes).
 *
 * This is a **read-only download of a public data file** — no user data is sent, so the app's
 * "no backend / we collect nothing" promise is unchanged (same category as the Yahoo/FRED calls).
 * To refresh the numbers you edit the FILE (see release-2.0/cb-data/), not the app: every install
 * picks up the change on its next weekly refresh, with no app update.
 *
 * The engine falls back to its bundled annual series whenever this returns empty (offline, fetch
 * failure, or a period the feed doesn't cover yet), so the feature degrades gracefully.
 *
 * Feed JSON shape:
 *   { "source": "...", "unit": "tonnes", "updated": "YYYY-MM-DD",
 *     "quarterly": { "2024-Q4": 333.0, "2025-Q1": 244.0, ... } }
 */
class CentralBankClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Raw JSON body, or null on any failure. */
    fun fetchRaw(): String? = try {
        client.newCall(Request.Builder().url(FEED_URL).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) { null }

    companion object {
        // Hosted public data file. The numbers live here, not in the app — edit the file to refresh.
        const val FEED_URL =
            "https://raw.githubusercontent.com/bull88protocol/aurum/master/data/cb_quarterly.json"

        /** Weekly-cached load with offline fallback to the last-good copy, then parse. */
        fun loadCached(context: Context): List<CbQuarter> {
            val raw = if (CentralBankCache.isFresh(context)) {
                CentralBankCache.load(context)
            } else {
                CentralBankClient().fetchRaw()?.also { CentralBankCache.save(context, it) }
                    ?: CentralBankCache.load(context)   // fetch failed → reuse last good
            }
            return parse(raw)
        }

        /** Parses the feed JSON into sorted quarterly rows. Tolerant: returns empty on any problem. */
        fun parse(json: String?): List<CbQuarter> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val quarterly = JSONObject(json).optJSONObject("quarterly") ?: return emptyList()
                val keyRe = Regex("""(\d{4})-Q([1-4])""")
                val out = mutableListOf<CbQuarter>()
                val keys = quarterly.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val m = keyRe.matchEntire(k.trim()) ?: continue
                    val t = quarterly.optDouble(k, Double.NaN)
                    if (!t.isNaN()) out.add(CbQuarter(m.groupValues[1].toInt(), m.groupValues[2].toInt(), t))
                }
                out.sortedWith(compareBy({ it.year }, { it.quarter }))
            } catch (e: Exception) { emptyList() }
        }
    }
}
