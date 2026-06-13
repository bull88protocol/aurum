package com.sun.aurum.network

import com.sun.aurum.model.GeminiResult
import com.sun.aurum.model.NewsItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Lightweight key validation — lists models (no token cost).
     * Returns true if the key authenticates successfully.
     */
    fun testApiKey(apiKey: String): Boolean {
        if (apiKey.isBlank()) return false
        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    fun fetchAnalysisAndNews(symbol: String, apiKey: String): GeminiResult? {
        if (apiKey.isBlank()) return null

        val (lastSession, nextSession) = getTradingSessionDates()
        val prompt = buildPrompt(symbol, lastSession.longLabel, nextSession.longLabel)
        val bodyJson = buildRequestBody(prompt)

        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini-2.5-flash:generateContent?key=$apiKey"

        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Gemini error (${resp.code}): ${body.take(200)}")
                parseResponse(JSONObject(body), lastSession.shortLabel, nextSession.shortLabel)
            }
        } catch (e: Exception) { null }
    }

    /** Computes the last closed trading session and the next upcoming trading session in ET. */
    private fun getTradingSessionDates(): Pair<SessionDate, SessionDate> {
        val nyZone = TimeZone.getTimeZone("America/New_York")
        val now = Calendar.getInstance(nyZone)
        val dow = now.get(Calendar.DAY_OF_WEEK)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val isWeekday = dow in Calendar.MONDAY..Calendar.FRIDAY
        // Market closes at 4 PM ET; treat pre-market (< 9:30) as "today not yet open" — same logic
        val marketClosedForToday = !isWeekday || hour >= 16

        val lastCal = now.clone() as Calendar
        if (marketClosedForToday) {
            // If weekend, back up to most recent Friday
            if (!isWeekday) {
                while (lastCal.get(Calendar.DAY_OF_WEEK) !in Calendar.MONDAY..Calendar.FRIDAY)
                    lastCal.add(Calendar.DAY_OF_YEAR, -1)
            }
            // If weekday after 4 PM: lastCal = today (already correct)
        } else {
            // Before 4 PM on a weekday: last session = previous trading day
            lastCal.add(Calendar.DAY_OF_YEAR, -1)
            while (lastCal.get(Calendar.DAY_OF_WEEK) !in Calendar.MONDAY..Calendar.FRIDAY)
                lastCal.add(Calendar.DAY_OF_YEAR, -1)
        }

        val nextCal = lastCal.clone() as Calendar
        nextCal.add(Calendar.DAY_OF_YEAR, 1)
        while (nextCal.get(Calendar.DAY_OF_WEEK) !in Calendar.MONDAY..Calendar.FRIDAY)
            nextCal.add(Calendar.DAY_OF_YEAR, 1)

        val shortFmt = SimpleDateFormat("MMMM d", Locale.US).apply { timeZone = nyZone }
        val longFmt  = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).apply { timeZone = nyZone }
        return Pair(
            SessionDate(shortFmt.format(lastCal.time), longFmt.format(lastCal.time)),
            SessionDate(shortFmt.format(nextCal.time), longFmt.format(nextCal.time)),
        )
    }

    private data class SessionDate(val shortLabel: String, val longLabel: String)

    private fun buildPrompt(symbol: String, lastSession: String, nextSession: String) = """
        You are a financial analyst. Search for real-time information about $symbol stock.
        Last closed trading session: $lastSession.
        Next trading session: $nextSession.

        Provide a daily market brief covering exactly these two sessions. Return ONLY a valid JSON object — no markdown, no code fences:
        {
          "signal": "BULLISH" or "NEUTRAL" or "BEARISH",
          "score": integer 0-100 (100=extremely bullish, 50=neutral, 0=extremely bearish),
          "description": "2-3 sentence overall market sentiment summary for $symbol",
          "key_factors": ["brief factor 1", "brief factor 2", "brief factor 3"],
          "yesterday_recap": "3-4 sentences about $symbol on $lastSession — actual closing price and % change for that session, the key reason(s) it moved, any company-specific news. Also briefly mention what the broader market (S&P 500, Nasdaq) did and why.",
          "today_outlook": "3-4 sentences about what could affect $symbol on $nextSession — upcoming catalysts (earnings, analyst events, product launches), relevant macro data releases (Fed, CPI, jobs), sector trends, key technical support/resistance levels to watch.",
          "news": [
            {"headline": "...", "summary": "1-2 sentence summary", "source": "publisher name", "url": "https://actual-article-url.com/...", "date": "YYYY-MM-DD"},
            {"headline": "...", "summary": "...", "source": "...", "url": "https://...", "date": "YYYY-MM-DD"},
            {"headline": "...", "summary": "...", "source": "...", "url": "https://...", "date": "YYYY-MM-DD"},
            {"headline": "...", "summary": "...", "source": "...", "url": "https://...", "date": "YYYY-MM-DD"},
            {"headline": "...", "summary": "...", "source": "...", "url": "https://...", "date": "YYYY-MM-DD"}
          ]${if (symbol == "GLD") """,
          "central_bank_score": 0""" else ""}
        }
        Rules: Include the 5 most impactful news items from the past 7 days. Provide real article URLs. Be specific — name actual prices, percentages, events.${if (symbol == "GLD") "\n        For central_bank_score: integer 0-100 (100=peak central bank gold buying, 50=neutral, 0=net selling; based on latest WGC data and central bank news)." else ""}
    """.trimIndent()

    private fun buildRequestBody(prompt: String): JSONObject {
        val part    = JSONObject().put("text", prompt)
        val content = JSONObject().put("parts", JSONArray().put(part))
        return JSONObject()
            .put("contents", JSONArray().put(content))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject().put("responseMimeType", "text/plain"))
    }

    private fun parseResponse(root: JSONObject, lastSessionLabel: String, nextSessionLabel: String): GeminiResult? {
        val text = root
            .optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)
            ?.optString("text") ?: return null

        val json = extractJsonObject(text) ?: return null

        val signal = json.optString("signal", "NEUTRAL").uppercase().let {
            when { it.contains("BULL") -> "BULLISH"; it.contains("BEAR") -> "BEARISH"; else -> "NEUTRAL" }
        }

        val newsArr = json.optJSONArray("news")
        val sevenDaysAgo = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.time
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        val news = buildList {
            if (newsArr != null) for (i in 0 until newsArr.length()) {
                val n = newsArr.optJSONObject(i) ?: continue
                val dateStr = n.optString("date", "")
                // Filter out items older than 7 days
                val itemDate = runCatching { dateFmt.parse(dateStr) }.getOrNull()
                if (itemDate != null && itemDate.before(sevenDaysAgo)) continue
                add(NewsItem(
                    headline = n.optString("headline", ""),
                    summary  = n.optString("summary", ""),
                    source   = n.optString("source", ""),
                    url      = n.optString("url", ""),
                    date     = dateStr,
                ))
                if (size >= 5) break
            }
        }

        return GeminiResult(
            signal            = signal,
            score             = json.optInt("score", 50).coerceIn(0, 100),
            description       = json.optString("description", ""),
            keyFactors        = json.optJSONArray("key_factors")?.let { a ->
                (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
            } ?: emptyList(),
            news              = news,
            yesterdayRecap    = json.optString("yesterday_recap", ""),
            todayOutlook      = json.optString("today_outlook", ""),
            lastSessionLabel  = lastSessionLabel,
            nextSessionLabel  = nextSessionLabel,
            goldCentralBankScore = if (json.has("central_bank_score")) json.optInt("central_bank_score", 50).coerceIn(0, 100) else null,
        )
    }

    private fun extractJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end   = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }
}
