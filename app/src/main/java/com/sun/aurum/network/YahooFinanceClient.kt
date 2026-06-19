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
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
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

    private fun get(url: String): JSONObject? {
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                JSONObject(body)
            }
        } catch (e: Exception) { null }
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

            QuoteData(
                symbol             = symbol,
                price              = displayPrice,
                change             = change,
                changePct          = changePct,
                high               = meta.optDouble("regularMarketDayHigh", regularPrice),
                low                = meta.optDouble("regularMarketDayLow", regularPrice),
                open               = meta.optDouble("regularMarketOpen", prevClose),
                previousClose      = prevClose,
                volume             = meta.optLong("regularMarketVolume"),
                marketState        = marketState,
                regularMarketPrice = regularPrice,
            )
        } catch (e: Exception) { null }
    }

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
