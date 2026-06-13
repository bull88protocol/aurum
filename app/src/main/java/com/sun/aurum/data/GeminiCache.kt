package com.sun.aurum.data

import android.content.Context
import com.sun.aurum.model.GeminiResult
import com.sun.aurum.model.NewsItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Per-symbol cache for Gemini market brief results. Valid for 8 hours (one trading day). */
object GeminiCache {

    private const val CACHE_FILE = "gemini_brief_cache.json"
    private const val MAX_AGE_MS = 8 * 60 * 60 * 1000L

    fun save(context: Context, symbol: String, result: GeminiResult) {
        try {
            val all = loadRaw(context)
            all.put(symbol, JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("data", result.toJson())
            })
            File(context.filesDir, CACHE_FILE).writeText(all.toString())
        } catch (_: Exception) {}
    }

    fun clear(context: Context) {
        try { File(context.filesDir, CACHE_FILE).delete() } catch (_: Exception) {}
    }

    fun load(context: Context, symbol: String): GeminiResult? {
        return try {
            val entry = loadRaw(context).optJSONObject(symbol) ?: return null
            val age   = System.currentTimeMillis() - entry.getLong("ts")
            if (age > MAX_AGE_MS) return null
            entry.getJSONObject("data").toGeminiResult()
        } catch (_: Exception) { null }
    }

    private fun loadRaw(context: Context): JSONObject {
        val file = File(context.filesDir, CACHE_FILE)
        return if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        else JSONObject()
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private fun GeminiResult.toJson() = JSONObject().apply {
        put("sig", signal); put("score", score)
        put("desc", description)
        put("yr", yesterdayRecap); put("to", todayOutlook)
        put("lsl", lastSessionLabel); put("nsl", nextSessionLabel)
        put("kf", JSONArray().also { a -> keyFactors.forEach { a.put(it) } })
        put("news", JSONArray().also { a ->
            news.forEach { n ->
                a.put(JSONObject().apply {
                    put("h", n.headline); put("s", n.summary)
                    put("src", n.source); put("url", n.url); put("dt", n.date)
                })
            }
        })
        if (goldCentralBankScore != null) put("gcbs", goldCentralBankScore)
    }

    private fun JSONObject.toGeminiResult() = GeminiResult(
        signal                = optString("sig", "NEUTRAL"),
        score                 = optInt("score", 50),
        description           = optString("desc", ""),
        yesterdayRecap        = optString("yr", ""),
        todayOutlook          = optString("to", ""),
        lastSessionLabel      = optString("lsl", ""),
        nextSessionLabel      = optString("nsl", ""),
        keyFactors            = optJSONArray("kf")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        news                  = optJSONArray("news")?.let { a ->
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { n ->
                    NewsItem(n.optString("h"), n.optString("s"), n.optString("src"), n.optString("url"), n.optString("dt"))
                }
            }
        } ?: emptyList(),
        goldCentralBankScore  = if (has("gcbs")) optInt("gcbs", 50) else null,
    )
}
