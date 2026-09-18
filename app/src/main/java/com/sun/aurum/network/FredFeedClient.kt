package com.sun.aurum.network

import com.sun.aurum.model.FredObs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Reads the hosted FRED feed: DFII10, T10YIE and DGS2, which the repo's GitHub Action
 * (.github/workflows/fred-feed.yml) refetches every weekday evening with the maintainer's FRED key.
 * That key lives in a repository secret and never ships in the app.
 *
 * Only the 6 PM report worker reads the feed, so the daily PDF is complete for users without a FRED
 * key. It is a fallback: in the report a user's own key still comes first, because a fetch at report
 * time has FRED's latest print and the feed is only as fresh as the last GitHub run. The rest of the
 * app reads FRED with the user's own key alone. Like the CB feed, this is an anonymous download of
 * a public file and sends nothing about the user.
 *
 * The feed's windows mirror DataRepository's keyed fetch (DFII10 6y, T10YIE and DGS2 3y), so the
 * engines score the same from either source. A series whose latest observation is older than
 * [MAX_STALE_DAYS] is dropped rather than served: if the Action ever stops, the report must not go
 * on quietly scoring last month's yields. A dropped series counts as missing, as if the feed were down.
 *
 * Feed JSON shape (schema 1):
 *   { "schema": 1, "generated_utc": "...", "notice": "...",
 *     "series": { "DFII10": { "dates": ["2020-09-16", ...], "values": [-0.97, ...] }, ... } }
 */
class FredFeedClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        // Bounds the ENTIRE call. connect/read only bound individual socket
        // operations, so a server that trickles bytes resets them forever and
        // the refresh spins with no upper bound. This is that upper bound.
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The feed's fresh series by FRED id, or null if the feed can't be fetched or read. */
    fun fetch(today: LocalDate = LocalDate.now()): Map<String, List<FredObs>>? = try {
        client.newCall(Request.Builder().url(FEED_URL).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string()?.let { parse(it, today) } else null
        }
    } catch (e: Exception) { null }

    companion object {
        const val FEED_URL =
            "https://raw.githubusercontent.com/bull88protocol/aurum/fred-data/fred_daily.json"
        const val MAX_STALE_DAYS = 10L
        private const val SCHEMA = 1

        /** Parses the feed, keeping only series that pass [isUsable]. Null on an unreadable feed. */
        fun parse(json: String, today: LocalDate): Map<String, List<FredObs>>? = try {
            val root = JSONObject(json)
            if (root.optInt("schema") != SCHEMA) null else {
                val series = root.getJSONObject("series")
                val out = mutableMapOf<String, List<FredObs>>()
                for (id in series.keys()) {
                    val s = series.getJSONObject(id)
                    val dates = s.getJSONArray("dates")
                    val values = s.getJSONArray("values")
                    if (dates.length() != values.length()) continue
                    val obs = (0 until dates.length()).map { FredObs(dates.getString(it), values.getDouble(it)) }
                    if (isUsable(obs, today)) out[id] = obs
                }
                out
            }
        } catch (e: Exception) { null }

        /** Non-empty, finite, strictly ascending by date, and the latest observation is recent. */
        fun isUsable(obs: List<FredObs>, today: LocalDate): Boolean {
            if (obs.isEmpty() || obs.any { !it.value.isFinite() }) return false
            if (obs.zipWithNext().any { (a, b) -> a.dateStr >= b.dateStr }) return false
            val latest = try { LocalDate.parse(obs.last().dateStr) } catch (e: Exception) { return false }
            return !latest.isBefore(today.minusDays(MAX_STALE_DAYS))
        }
    }
}
