package com.sun.aurum.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FredClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Obs(val dateStr: String, val value: Double)

    /** Fetches FRED series observations; returns empty list on failure or missing API key. */
    fun fetchSeries(
        seriesId: String,
        apiKey: String,
        startDate: String? = null,
        limit: Int = 1000,
    ): List<Obs> {
        if (apiKey.isBlank()) return emptyList()
        var url = "https://api.stlouisfed.org/fred/series/observations" +
                "?series_id=$seriesId&api_key=$apiKey&file_type=json&sort_order=asc&limit=$limit"
        if (startDate != null) url += "&observation_start=$startDate"
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                parseObs(JSONObject(body))
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Quick connectivity + auth test — returns true if the key is valid. */
    fun testApiKey(apiKey: String): Boolean = fetchSeries("DFII10", apiKey, limit = 3).isNotEmpty()

    private fun parseObs(json: JSONObject): List<Obs> {
        return try {
            val arr = json.getJSONArray("observations")
            val list = mutableListOf<Obs>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val v = obj.getString("value")
                if (v == "." || v.isBlank()) continue
                val d = v.toDoubleOrNull() ?: continue
                list.add(Obs(obj.getString("date"), d))
            }
            list
        } catch (e: Exception) { emptyList() }
    }
}
