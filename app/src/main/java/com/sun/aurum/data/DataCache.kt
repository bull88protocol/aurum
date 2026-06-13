package com.sun.aurum.data

import android.content.Context
import com.sun.aurum.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object DataCache {

    private const val CACHE_FILE = "symbol_cache.json"
    private const val MAX_AGE_MS = 8 * 60 * 60 * 1000L // 8 hours

    fun save(context: Context, states: Map<String, SymbolState>) {
        try {
            val arr = JSONArray()
            for ((_, s) in states) arr.put(s.toJson())
            File(context.filesDir, CACHE_FILE).writeText(arr.toString())
        } catch (_: Exception) {}
    }

    fun clear(context: Context) {
        try { File(context.filesDir, CACHE_FILE).delete() } catch (_: Exception) {}
    }

    fun load(context: Context): Map<String, SymbolState>? {
        return try {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.exists()) return null
            val arr = JSONArray(file.readText())
            val map = mutableMapOf<String, SymbolState>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i).toSymbolState()
                map[s.symbol] = s
            }
            val freshest = map.values.maxOfOrNull { it.lastUpdated } ?: 0L
            if (System.currentTimeMillis() - freshest > MAX_AGE_MS) null else map
        } catch (_: Exception) { null }
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private fun SymbolState.toJson() = JSONObject().apply {
        put("sym", symbol); put("ts", lastUpdated); put("gd", usingGoogleData)
        if (error != null) put("err", error)
        if (quote != null) put("q", quote.toJson())
        put("ip", JSONArray().also { a -> intradayPoints.forEach { a.put(it.toJson()) } })
        if (hmaiReport != null) put("hmai", hmaiReport.toJson())
        put("news", JSONArray().also { a -> news.forEach { a.put(it.toJson()) } })
        if (goldIndexReport != null) put("gi", goldIndexReport.toJson())
        if (geminiSignal != null) put("gsig", geminiSignal)
        if (geminiScore != null) put("gsc2", geminiScore)
        if (geminiDescription != null) put("gdesc", geminiDescription)
        if (geminiYesterdayRecap != null) put("gyr2", geminiYesterdayRecap)
        if (geminiTodayOutlook != null) put("gto2", geminiTodayOutlook)
        if (lastSessionLabel != null) put("lsl2", lastSessionLabel)
        if (nextSessionLabel != null) put("nsl2", nextSessionLabel)
        put("gkf2", JSONArray().also { a -> geminiKeyFactors.forEach { a.put(it) } })
    }

    private fun JSONObject.toSymbolState() = SymbolState(
        symbol               = getString("sym"),
        lastUpdated          = getLong("ts"),
        usingGoogleData      = optBoolean("gd", false),
        error                = if (has("err")) getString("err") else null,
        quote                = if (has("q")) getJSONObject("q").toQuoteData() else null,
        intradayPoints       = getJSONArray("ip").let { a -> (0 until a.length()).map { a.getJSONObject(it).toIntradayPoint() } },
        hmaiReport           = if (has("hmai")) getJSONObject("hmai").toHmaiReport() else null,
        news                 = getJSONArray("news").let { a -> (0 until a.length()).map { a.getJSONObject(it).toNewsItem() } },
        goldIndexReport      = if (has("gi")) getJSONObject("gi").toGoldIndexReport() else null,
        geminiSignal         = if (has("gsig")) getString("gsig") else null,
        geminiScore          = if (has("gsc2")) getInt("gsc2") else null,
        geminiDescription    = if (has("gdesc")) getString("gdesc") else null,
        geminiYesterdayRecap = if (has("gyr2")) getString("gyr2") else null,
        geminiTodayOutlook   = if (has("gto2")) getString("gto2") else null,
        lastSessionLabel     = if (has("lsl2")) getString("lsl2") else null,
        nextSessionLabel     = if (has("nsl2")) getString("nsl2") else null,
        geminiKeyFactors     = if (has("gkf2")) getJSONArray("gkf2").let { a -> (0 until a.length()).map { a.getString(it) } } else emptyList(),
    )

    private fun QuoteData.toJson() = JSONObject().apply {
        put("s", symbol); put("p", price); put("c", change); put("cp", changePct)
        put("h", high); put("l", low); put("o", open); put("pc", previousClose); put("v", volume)
        put("ms", marketState); put("rmp", regularMarketPrice)
    }

    private fun JSONObject.toQuoteData() = QuoteData(
        symbol             = getString("s"),  price              = getDouble("p"),
        change             = getDouble("c"),  changePct          = getDouble("cp"),
        high               = getDouble("h"),  low                = getDouble("l"),
        open               = getDouble("o"),  previousClose      = getDouble("pc"),
        volume             = getLong("v"),
        marketState        = optString("ms", "REGULAR"),
        regularMarketPrice = optDouble("rmp", 0.0),
    )

    private fun IntradayPoint.toJson() = JSONObject().apply { put("t", timestampMs); put("p", price); put("v", volume) }
    private fun JSONObject.toIntradayPoint() = IntradayPoint(getLong("t"), getDouble("p"), getLong("v"))

    private fun NewsItem.toJson() = JSONObject().apply {
        put("h", headline); put("s", summary); put("src", source)
        put("url", url); put("dt", date)
    }
    private fun JSONObject.toNewsItem() = NewsItem(
        getString("h"), getString("s"), getString("src"),
        optString("url", ""), optString("dt", ""),
    )

    private fun HmaiReport.toJson() = JSONObject().apply {
        put("sym", symbol); put("comp", composite); put("cl", compositeLabel)
        put("rc", rawComposite)
        if (vixValue != null) put("vix", vixValue)
        if (geminiSignal != null) put("gs", geminiSignal)
        if (geminiScore != null) put("gsc", geminiScore)
        if (geminiDescription != null) put("gd", geminiDescription)
        if (geminiYesterdayRecap != null) put("gyr", geminiYesterdayRecap)
        if (geminiTodayOutlook != null) put("gto", geminiTodayOutlook)
        if (lastSessionLabel != null) put("lsl", lastSessionLabel)
        if (nextSessionLabel != null) put("nsl", nextSessionLabel)
        put("gkf", JSONArray().also { a -> geminiKeyFactors.forEach { a.put(it) } })
        put("cb", JSONObject().apply {
            put("act", circuitBreaker.action.name); put("desc", circuitBreaker.description)
            put("tr", JSONArray().also { a -> circuitBreaker.triggers.forEach { a.put(it) } })
        })
        put("pil", JSONArray().also { a -> pillars.forEach { a.put(it.toJson()) } })
    }

    private fun JSONObject.toHmaiReport(): HmaiReport {
        val cbObj = getJSONObject("cb")
        val cb = CircuitBreakerResult(
            action      = CbAction.valueOf(cbObj.getString("act")),
            description = cbObj.getString("desc"),
            triggers    = cbObj.getJSONArray("tr").let { a -> (0 until a.length()).map { a.getString(it) } },
        )
        return HmaiReport(
            symbol               = getString("sym"),
            composite            = getDouble("comp"),
            compositeLabel       = getString("cl"),
            rawComposite         = getDouble("rc"),
            vixValue             = if (has("vix")) getDouble("vix") else null,
            geminiSignal         = if (has("gs")) getString("gs") else null,
            geminiScore          = if (has("gsc")) getInt("gsc") else null,
            geminiDescription    = if (has("gd")) getString("gd") else null,
            geminiYesterdayRecap = if (has("gyr")) getString("gyr") else null,
            geminiTodayOutlook   = if (has("gto")) getString("gto") else null,
            lastSessionLabel     = if (has("lsl")) getString("lsl") else null,
            nextSessionLabel     = if (has("nsl")) getString("nsl") else null,
            geminiKeyFactors     = getJSONArray("gkf").let { a -> (0 until a.length()).map { a.getString(it) } },
            circuitBreaker       = cb,
            pillars              = getJSONArray("pil").let { a -> (0 until a.length()).map { a.getJSONObject(it).toPillarResult() } },
        )
    }

    private fun PillarResult.toJson() = JSONObject().apply {
        put("p", pillar); put("n", name); put("s", score); put("ms", maxScore)
        put("l", label); put("det", details)
        put("comp", JSONObject().also { o -> components.forEach { (k, v) -> o.put(k, v) } })
    }

    private fun JSONObject.toPillarResult(): PillarResult {
        val compObj = getJSONObject("comp")
        val comp    = compObj.keys().asSequence().associateWith { compObj.getDouble(it) }
        return PillarResult(getInt("p"), getString("n"), getDouble("s"), getDouble("ms"), getString("l"), comp, getString("det"))
    }

    // ── Gold Index serialization ──────────────────────────────────────────────

    private fun GoldIndexReport.toJson() = JSONObject().apply {
        put("cs", compositeScore); put("cl", compositeLabel); put("ts", timestamp)
        put("comp", JSONArray().also { a -> components.forEach { a.put(it.toJson()) } })
        put("hist", JSONArray().also { a -> historicalScores.forEach { a.put(it.toJson()) } })
        put("fs", forwardScore); put("fl", forwardLabel)
        put("fcomp", JSONArray().also { a -> forwardComponents.forEach { a.put(it.toJson()) } })
    }

    private fun JSONObject.toGoldIndexReport() = GoldIndexReport(
        compositeScore     = getDouble("cs").toFloat(),
        compositeLabel     = getString("cl"),
        timestamp          = getLong("ts"),
        components         = getJSONArray("comp").let { a -> (0 until a.length()).map { a.getJSONObject(it).toGoldComponentScore() } },
        historicalScores   = getJSONArray("hist").let { a -> (0 until a.length()).map { a.getJSONObject(it).toDailyIndexPoint() } },
        forwardScore       = optDouble("fs", 50.0).toFloat(),
        forwardLabel       = optString("fl", "NEUTRAL"),
        forwardComponents  = if (has("fcomp")) getJSONArray("fcomp").let { a -> (0 until a.length()).map { a.getJSONObject(it).toGoldComponentScore() } } else emptyList(),
    )

    private fun GoldComponentScore.toJson() = JSONObject().apply {
        put("n", name); put("s", score); put("l", label); put("d", detail); put("av", available)
    }

    private fun JSONObject.toGoldComponentScore() = GoldComponentScore(
        name      = getString("n"),
        score     = getDouble("s").toFloat(),
        label     = getString("l"),
        detail    = getString("d"),
        available = optBoolean("av", true),
    )

    private fun DailyIndexPoint.toJson() = JSONObject().apply { put("t", dateMs); put("s", score) }
    private fun JSONObject.toDailyIndexPoint() = DailyIndexPoint(getLong("t"), getDouble("s").toFloat())
}
