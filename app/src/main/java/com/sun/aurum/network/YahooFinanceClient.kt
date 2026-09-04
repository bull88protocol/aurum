package com.sun.aurum.network

import com.sun.aurum.model.Candle
import com.sun.aurum.model.IntradayPoint
import com.sun.aurum.model.QuoteData
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class YahooFinanceClient {

    private companion object {
        // 2000-01-01 UTC — safely before GLD (2004) and DX-Y.NYB (2003) daily history begins.
        const val EARLY_HISTORY_EPOCH = 946684800L
        // 2 attempts x 2 hosts = 4 requests, each bounded by callTimeout above. It was 3
        // attempts: 6 requests x ~50s = ~5 minutes for ONE series, and fetchAll runs six
        // of these back to back, so a Yahoo outage could spin the UI for ~30 minutes. The
        // query2 mirror already covers the flaky-host case a third attempt was added for.
        const val MAX_ATTEMPTS = 2
        const val BACKOFF_MS = 300L
        // Half a cent — tolerates rounding noise when checking an open against the day's range.
        const val OHLC_EPS = 0.005
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Bounds the ENTIRE call. connect/read only bound individual socket
        // operations, so a server that trickles bytes resets them forever and
        // the refresh spins with no upper bound. This is that upper bound.
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .build()
            chain.proceed(req)
        }
        .build()

    /** Fetch ~2 years of daily OHLCV candles for HMAI computation */
    fun fetchDailyCandles(symbol: String): List<Candle> {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol" +
                "?interval=1d&range=2y"
        val json = get(url) ?: return emptyList()
        return parseCandles(json)
    }

    /**
     * Fetch the full daily OHLCV history as TRUE daily bars.
     *
     * NB: `range=max` is a trap — Yahoo silently downsamples it (GLD → monthly, DX-Y.NYB →
     * quarterly), which makes the CSV's technical/USD columns meaningless (a "200-day" SMA over
     * monthly bars is really 200 months). Requesting an explicit period1..period2 window with
     * interval=1d returns genuine daily bars for the whole history in one response
     * (~5.4k bars for GLD back to 2004, ~7.2k for DX-Y.NYB back to 2003).
     */
    fun fetchMaxDailyCandles(symbol: String): List<Candle> {
        val period2 = System.currentTimeMillis() / 1000L
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol" +
                "?period1=$EARLY_HISTORY_EPOCH&period2=$period2&interval=1d"
        val json = get(url) ?: return emptyList()
        return parseCandles(json)
    }

    /** Fetch today's 5-minute intraday bars */
    fun fetchIntraday(symbol: String): Pair<QuoteData?, List<IntradayPoint>> {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol" +
                "?interval=5m&range=1d"
        val json = get(url) ?: return Pair(null, emptyList())
        val quote = parseQuote(json, symbol)
        val intraday = parseIntraday(json)
        return Pair(quote, intraday)
    }

    /** Fetch latest VIX value */
    fun fetchVix(): Double? {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/%5EVIX?interval=1d&range=5d"
        val json = get(url) ?: return null
        return try {
            val meta = json.getJSONObject("chart")
                .getJSONArray("result")
                .getJSONObject(0)
                .getJSONObject("meta")
            meta.optDouble("regularMarketPrice").takeIf { !it.isNaN() }
        } catch (e: Exception) { null }
    }

    /**
     * GETs a Yahoo chart URL with resilience: each attempt tries the query1 host and then its
     * query2 mirror, with bounded linear backoff between attempts. This is what stops a single
     * flaky request from silently dropping a core series (e.g. DXY → the USD component) — the
     * failure we saw blank out USD on-device even though Yahoo was otherwise healthy.
     */
    private fun get(url: String): JSONObject? {
        val hosts = if (url.contains("query1.finance.yahoo.com"))
            listOf(url, url.replace("query1.finance.yahoo.com", "query2.finance.yahoo.com"))
        else listOf(url)

        repeat(MAX_ATTEMPTS) { attempt ->
            for (u in hosts) {
                try {
                    val req = Request.Builder().url(u).build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (!body.isNullOrEmpty()) return JSONObject(body)
                        }
                    }
                } catch (e: Exception) { /* fall through to the mirror / next attempt */ }
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                try { Thread.sleep(BACKOFF_MS * (attempt + 1)) } catch (_: InterruptedException) {}
            }
        }
        return null
    }

    private fun parseCandles(json: JSONObject): List<Candle> {
        return try {
            val result = json.getJSONObject("chart")
                .getJSONArray("result")
                .getJSONObject(0)
            val timestamps = result.getJSONArray("timestamp")
            val indicators = result.getJSONObject("indicators")
            val quote = indicators.getJSONArray("quote").getJSONObject(0)
            val opens   = quote.getJSONArray("open")
            val highs   = quote.getJSONArray("high")
            val lows    = quote.getJSONArray("low")
            val closes  = quote.getJSONArray("close")
            val volumes = quote.getJSONArray("volume")

            val candles = mutableListOf<Candle>()
            for (i in 0 until timestamps.length()) {
                val o = opens.optDouble(i)
                val h = highs.optDouble(i)
                val l = lows.optDouble(i)
                val c = closes.optDouble(i)
                val v = volumes.optLong(i)
                val t = timestamps.getLong(i) * 1000L
                if (!o.isNaN() && !h.isNaN() && !l.isNaN() && !c.isNaN() && c > 0) {
                    candles.add(Candle(o, h, l, c, v, t))
                }
            }
            candles
        } catch (e: Exception) { emptyList() }
    }

    private fun parseQuote(json: JSONObject, symbol: String): QuoteData? {
        return try {
            val meta = json.getJSONObject("chart")
                .getJSONArray("result")
                .getJSONObject(0)
                .getJSONObject("meta")

            val regularPrice = meta.optDouble("regularMarketPrice")
            val prevClose    = meta.optDouble("chartPreviousClose")
            if (regularPrice.isNaN() || prevClose.isNaN()) return null

            val marketState = meta.optString("marketState", "REGULAR")

            // Pick the live price and compute change vs the appropriate baseline
            val (displayPrice, change, changePct) = when {
                marketState == "PRE" -> {
                    val pre = meta.optDouble("preMarketPrice")
                    if (!pre.isNaN() && pre > 0) {
                        val ch = pre - prevClose
                        Triple(pre, ch, if (prevClose != 0.0) ch / prevClose * 100.0 else 0.0)
                    } else {
                        val ch = regularPrice - prevClose
                        Triple(regularPrice, ch, if (prevClose != 0.0) ch / prevClose * 100.0 else 0.0)
                    }
                }
                marketState == "POST" || marketState == "POSTPOST" -> {
                    val post = meta.optDouble("postMarketPrice")
                    if (!post.isNaN() && post > 0) {
                        val baseline = if (regularPrice > 0) regularPrice else prevClose
                        val ch = post - baseline
                        Triple(post, ch, if (baseline != 0.0) ch / baseline * 100.0 else 0.0)
                    } else {
                        val ch = regularPrice - prevClose
                        Triple(regularPrice, ch, if (prevClose != 0.0) ch / prevClose * 100.0 else 0.0)
                    }
                }
                else -> {
                    val ch = regularPrice - prevClose
                    Triple(regularPrice, ch, if (prevClose != 0.0) ch / prevClose * 100.0 else 0.0)
                }
            }

            val high = meta.optDouble("regularMarketDayHigh", regularPrice)
            val low  = meta.optDouble("regularMarketDayLow", regularPrice)

            QuoteData(
                symbol             = symbol,
                price              = displayPrice,
                change             = change,
                changePct          = changePct,
                high               = high,
                low                = low,
                open               = resolveOpen(json, meta, high, low),
                previousClose      = prevClose,
                volume             = meta.optLong("regularMarketVolume"),
                marketState        = marketState,
                regularMarketPrice = regularPrice,
            )
        } catch (e: Exception) { null }
    }

    /**
     * The regular-session open.
     *
     * NB: the v8 chart `meta` block carries NO `regularMarketOpen` — that field belongs to the v7
     * quote API — so the old `meta.optDouble("regularMarketOpen", prevClose)` missed every single
     * time and silently relabelled the *previous close* as today's open. It shipped values that
     * contradicted the same response's high/low (e.g. GLD 2026-09-01: open printed 408.42 against
     * a day high of 401.25). The real open is the first non-null bar in `indicators.quote[0].open`,
     * which this same intraday response already carries.
     *
     * Both candidates are checked against the session's own range, and null is returned when
     * neither holds — before the first bar prints (pre-market) there is genuinely no open yet, and
     * showing "—" is honest where showing the previous close is not.
     */
    private fun resolveOpen(json: JSONObject, meta: JSONObject, high: Double, low: Double): Double? {
        fun inRange(o: Double) = o >= low - OHLC_EPS && o <= high + OHLC_EPS

        val metaOpen = meta.optDouble("regularMarketOpen")
        if (!metaOpen.isNaN() && metaOpen > 0 && inRange(metaOpen)) return metaOpen

        val barOpen = firstBarOpen(json)
        if (barOpen != null && inRange(barOpen)) return barOpen

        return null
    }

    /** First non-null open in the intraday bar series, i.e. the opening print of the session. */
    private fun firstBarOpen(json: JSONObject): Double? = try {
        val opens = json.getJSONObject("chart")
            .getJSONArray("result")
            .getJSONObject(0)
            .getJSONObject("indicators")
            .getJSONArray("quote")
            .getJSONObject(0)
            .getJSONArray("open")
        var found: Double? = null
        for (i in 0 until opens.length()) {
            val o = opens.optDouble(i)
            if (!o.isNaN() && o > 0) { found = o; break }
        }
        found
    } catch (e: Exception) { null }

    private fun parseIntraday(json: JSONObject): List<IntradayPoint> {
        return try {
            val result = json.getJSONObject("chart")
                .getJSONArray("result")
                .getJSONObject(0)
            val timestamps = result.getJSONArray("timestamp")
            val quote = result.getJSONObject("indicators")
                .getJSONArray("quote")
                .getJSONObject(0)
            val closes  = quote.getJSONArray("close")
            val volumes = quote.getJSONArray("volume")

            val points = mutableListOf<IntradayPoint>()
            for (i in 0 until timestamps.length()) {
                val c = closes.optDouble(i)
                val v = volumes.optLong(i)
                val t = timestamps.getLong(i) * 1000L
                if (!c.isNaN() && c > 0) {
                    points.add(IntradayPoint(t, c, v))
                }
            }
            points
        } catch (e: Exception) { emptyList() }
    }
}
