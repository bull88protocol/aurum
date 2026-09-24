package com.sun.aurum.network

import com.sun.aurum.data.GeminiResultJson
import com.sun.aurum.model.GeminiResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Reads the hosted AI gold brief: the signal, score, session recap, outlook, key factors and the
 * five news items the AI Brief and News tabs show. The repo's GitHub Action
 * (.github/workflows/brief-feed.yml) regenerates it about hourly with the maintainer's Gemini key,
 * which lives in a repository secret and never ships in the app.
 *
 * This is the app's DEFAULT source for the brief, which is the opposite of how the FRED feed works,
 * and deliberately so. FRED's feed is a fallback because a fetch with the user's own key is fresher
 * than the last GitHub run. Here the user's own key is the *slow* path — a grounded Gemini call
 * takes 15-60 seconds — so the app shows this brief immediately and, when the user has a key,
 * replaces it with their own fresh brief when that call returns. Users with no key get a real
 * brief instead of the empty state, which is the other half of the point.
 *
 * Like the FRED and CB feeds, this is an anonymous download of a public file and sends nothing
 * about the user. A brief older than [MAX_STALE_HOURS] is dropped rather than served: if the Action
 * stops, the tab must not go on showing a recap of a session three days gone.
 *
 * Feed JSON shape (schema 1):
 *   { "schema": 1, "generated_utc": "2026-09-23T22:54:58Z", "model": "gemini-2.5-flash",
 *     "symbol": "GLD", "quote_at_generation": { "price": 398.47, ... },
 *     "brief": { "sig": "BULLISH", "score": 72, "desc": "...", ... } }
 *
 * `brief` uses [GeminiResultJson]'s field names, so it is decoded by the same code that reads the
 * on-disk cache — the feed publishes a parsed brief, never a raw model response.
 */
class BriefFeedClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // Bounds the ENTIRE call. connect/read only bound individual socket
        // operations, so a server that trickles bytes resets them forever and
        // the refresh spins with no upper bound. This is that upper bound.
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * A brief from the feed, with the timestamp it was generated at and the gold price it was
     * written against — both shown on the tab, because a feed brief's numbers are as of then,
     * not as of the quote at the top of the screen.
     */
    data class Feed(val result: GeminiResult, val generatedUtc: String, val anchorPrice: Double?)

    /** The published brief, or null if the feed can't be fetched, read, or is too old. */
    fun fetch(now: Instant = Instant.now()): Feed? = try {
        client.newCall(Request.Builder().url(FEED_URL).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string()?.let { parse(it, now) } else null
        }
    } catch (e: Exception) { null }

    companion object {
        const val FEED_URL =
            "https://raw.githubusercontent.com/bull88protocol/aurum/brief-data/brief_daily.json"
        const val MAX_STALE_HOURS = 12L
        private const val SCHEMA = 1

        /** Parses the feed, or null if it is unreadable, the wrong schema, empty or stale. */
        fun parse(json: String, now: Instant): Feed? = try {
            val root = JSONObject(json)
            val generated = root.optString("generated_utc")
            if (root.optInt("schema") != SCHEMA || !isFresh(generated, now)) null
            else {
                val result = GeminiResultJson.decode(root.getJSONObject("brief"))
                if (!GeminiResultJson.isUsable(result)) null
                else Feed(
                    result       = result,
                    generatedUtc = generated,
                    anchorPrice  = root.optJSONObject("quote_at_generation")
                        ?.optDouble("price")?.takeIf { it.isFinite() && it > 0 },
                )
            }
        } catch (e: Exception) { null }

        /** True when [generatedUtc] parses and is within [MAX_STALE_HOURS] of [now]. */
        fun isFresh(generatedUtc: String?, now: Instant): Boolean {
            val at = try { Instant.parse(generatedUtc) } catch (e: Exception) { return false }
            // A clock skewed far into the future would otherwise pass as "fresh" forever.
            if (at.isAfter(now.plus(Duration.ofHours(2)))) return false
            return at.isAfter(now.minus(Duration.ofHours(MAX_STALE_HOURS)))
        }
    }
}
