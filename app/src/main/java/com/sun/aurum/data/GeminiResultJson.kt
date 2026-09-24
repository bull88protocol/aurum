package com.sun.aurum.data

import com.sun.aurum.model.GeminiResult
import com.sun.aurum.model.NewsItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-the-wire shape of a gold brief, shared by [GeminiCache] (the app's own 8-hour disk cache)
 * and [com.sun.aurum.network.BriefFeedClient] (the hosted feed).
 *
 * The hosted feed publishes briefs in exactly this shape — `.github/brief-feed/build_brief.py`
 * writes these field names — so a feed brief is deserialized by the same code that reads the disk
 * cache. That is the point: the feed's generator and [com.sun.aurum.network.GeminiClient] can word
 * their prompts differently without the app ever mis-parsing a brief, because neither one's raw
 * model output reaches this layer. Keep the short field names in step with build_brief.py.
 */
object GeminiResultJson {

    fun encode(result: GeminiResult): JSONObject = JSONObject().apply {
        put("sig", result.signal); put("score", result.score)
        put("desc", result.description)
        put("yr", result.yesterdayRecap); put("to", result.todayOutlook)
        put("lsl", result.lastSessionLabel); put("nsl", result.nextSessionLabel)
        put("kf", JSONArray().also { a -> result.keyFactors.forEach { a.put(it) } })
        put("news", JSONArray().also { a ->
            result.news.forEach { n ->
                a.put(JSONObject().apply {
                    put("h", n.headline); put("s", n.summary)
                    put("src", n.source); put("url", n.url); put("dt", n.date)
                })
            }
        })
        if (result.goldCentralBankScore != null) put("gcbs", result.goldCentralBankScore)
    }

    fun decode(json: JSONObject): GeminiResult = GeminiResult(
        signal               = json.optString("sig", "NEUTRAL"),
        score                = json.optInt("score", 50),
        description          = json.optString("desc", ""),
        yesterdayRecap       = json.optString("yr", ""),
        todayOutlook         = json.optString("to", ""),
        lastSessionLabel     = json.optString("lsl", ""),
        nextSessionLabel     = json.optString("nsl", ""),
        keyFactors           = json.optJSONArray("kf")?.let { a ->
            (0 until a.length()).map { a.getString(it) }
        } ?: emptyList(),
        news                 = json.optJSONArray("news")?.let { a ->
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { n ->
                    NewsItem(n.optString("h"), n.optString("s"), n.optString("src"), n.optString("url"), n.optString("dt"))
                }
            }
        } ?: emptyList(),
        goldCentralBankScore = if (json.has("gcbs")) json.optInt("gcbs", 50) else null,
    )

    /** True when a brief has enough substance to be worth showing instead of the empty state. */
    fun isUsable(result: GeminiResult): Boolean =
        result.description.isNotBlank() || result.yesterdayRecap.isNotBlank() || result.todayOutlook.isNotBlank()
}
