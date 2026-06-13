package com.sun.aurum.domain.hmai

import com.sun.aurum.model.Candle
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

object TechnicalIndicators {

    fun sma(prices: List<Double>, period: Int): List<Double?> {
        if (prices.size < period) return List(prices.size) { null }
        return List(prices.size) { i ->
            if (i < period - 1) null
            else prices.subList(i - period + 1, i + 1).average()
        }
    }

    fun ema(prices: List<Double>, period: Int): List<Double> {
        if (prices.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1)
        val result = mutableListOf<Double>()
        var emaVal = prices[0]
        result.add(emaVal)
        for (i in 1 until prices.size) {
            emaVal = prices[i] * k + emaVal * (1 - k)
            result.add(emaVal)
        }
        return result
    }

    fun rsi(prices: List<Double>, period: Int = 14): List<Double?> {
        if (prices.size <= period) return List(prices.size) { null }
        val result = MutableList<Double?>(prices.size) { null }
        val gains = mutableListOf<Double>()
        val losses = mutableListOf<Double>()
        for (i in 1..period) {
            val diff = prices[i] - prices[i - 1]
            gains.add(if (diff > 0) diff else 0.0)
            losses.add(if (diff < 0) -diff else 0.0)
        }
        var avgGain = gains.average()
        var avgLoss = losses.average()
        result[period] = if (avgLoss == 0.0) 100.0 else 100 - (100 / (1 + avgGain / avgLoss))
        for (i in (period + 1) until prices.size) {
            val diff = prices[i] - prices[i - 1]
            val gain = if (diff > 0) diff else 0.0
            val loss = if (diff < 0) -diff else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            result[i] = if (avgLoss == 0.0) 100.0 else 100 - (100 / (1 + avgGain / avgLoss))
        }
        return result
    }

    data class MacdPoint(val line: Double, val signal: Double, val hist: Double)

    fun macd(prices: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): List<MacdPoint?> {
        if (prices.size < slow + signal) return List(prices.size) { null }
        val fastEma = ema(prices, fast)
        val slowEma = ema(prices, slow)
        val macdLine = fastEma.zip(slowEma) { f, s -> f - s }
        val signalLine = ema(macdLine, signal)
        return List(prices.size) { i ->
            MacdPoint(macdLine[i], signalLine[i], macdLine[i] - signalLine[i])
        }
    }

    fun atr(candles: List<Candle>, period: Int = 14): List<Double?> {
        if (candles.size <= period) return List(candles.size) { null }
        val result = MutableList<Double?>(candles.size) { null }
        fun trueRange(i: Int): Double {
            val h = candles[i].high; val l = candles[i].low; val pc = candles[i - 1].close
            return maxOf(h - l, abs(h - pc), abs(l - pc))
        }
        var atrVal = (1 until period + 1).map { trueRange(it) }.average()
        result[period] = atrVal
        for (i in (period + 1) until candles.size) {
            atrVal = (atrVal * (period - 1) + trueRange(i)) / period
            result[i] = atrVal
        }
        return result
    }

    fun atrPct(candles: List<Candle>, period: Int = 14): List<Double?> {
        val atrVals = atr(candles, period)
        return atrVals.mapIndexed { i, a -> if (a == null) null else a / candles[i].close * 100.0 }
    }

    fun atrPercentile(candles: List<Candle>, lookback: Int = 60): Double {
        if (candles.size < lookback + 14) return 50.0
        val window = candles.takeLast(lookback + 14)
        val atrSeries = atr(window).filterNotNull()
        if (atrSeries.size < 2) return 50.0
        val latest = atrSeries.last()
        return atrSeries.count { it <= latest }.toDouble() / atrSeries.size * 100.0
    }

    fun roc(prices: List<Double>, period: Int = 10): List<Double?> {
        return List(prices.size) { i ->
            if (i < period) null
            else (prices[i] - prices[i - period]) / prices[i - period] * 100.0
        }
    }

    fun obv(candles: List<Candle>): List<Long> {
        val result = mutableListOf<Long>()
        var obvVal = 0L
        result.add(obvVal)
        for (i in 1 until candles.size) {
            obvVal += when {
                candles[i].close > candles[i - 1].close -> candles[i].volume
                candles[i].close < candles[i - 1].close -> -candles[i].volume
                else -> 0L
            }
            result.add(obvVal)
        }
        return result
    }

    fun historicalVol(prices: List<Double>, period: Int = 20): List<Double?> {
        if (prices.size <= period) return List(prices.size) { null }
        val logRets = List(prices.size) { i -> if (i == 0) null else ln(prices[i] / prices[i - 1]) }
        return List(prices.size) { i ->
            if (i < period) null else {
                val window = (i - period + 1..i).mapNotNull { logRets[it] }
                if (window.size < period) null else {
                    val mean = window.average()
                    val variance = window.sumOf { (it - mean).pow(2) } / (window.size - 1)
                    sqrt(variance) * sqrt(252.0)
                }
            }
        }
    }

    fun hvPercentile(prices: List<Double>, period: Int = 20, lookback: Int = 252): Double {
        val hv = historicalVol(prices, period).filterNotNull()
        if (hv.size < 2) return 50.0
        val window = if (hv.size > lookback) hv.takeLast(lookback) else hv
        val latest = window.last()
        return window.count { it <= latest }.toDouble() / window.size * 100.0
    }

    fun slope(values: List<Double>, period: Int = 5): Double? {
        if (values.size < period) return null
        val window = values.takeLast(period)
        val n = period.toDouble()
        val xs = (0 until period).map { it.toDouble() }
        val sumX = xs.sum(); val sumY = window.sum()
        val sumXY = xs.zip(window).sumOf { (x, y) -> x * y }
        val sumX2 = xs.sumOf { it * it }
        val denom = n * sumX2 - sumX * sumX
        return if (denom == 0.0) 0.0 else (n * sumXY - sumX * sumY) / denom
    }

    fun hhhlScore(candles: List<Candle>, lookback: Int = 20): Int {
        if (candles.size < lookback + 1) return 2
        val window = candles.takeLast(lookback)
        val mid = lookback / 2
        val firstHalf = window.take(mid); val secondHalf = window.takeLast(mid)
        val hhScore = if (secondHalf.maxOf { it.high } > firstHalf.maxOf { it.high }) 2 else 0
        val hlScore = if (secondHalf.minOf { it.low } > firstHalf.minOf { it.low }) 2 else 0
        return hhScore + hlScore
    }

    fun range52WeekPosition(closes: List<Double>): Double {
        val window = if (closes.size > 252) closes.takeLast(252) else closes
        if (window.size < 2) return 50.0
        val hi = window.max(); val lo = window.min()
        return if (hi == lo) 50.0 else (closes.last() - lo) / (hi - lo) * 100.0
    }

    fun volSma(volumes: List<Long>, period: Int = 20): List<Double?> {
        return List(volumes.size) { i ->
            if (i < period - 1) null else volumes.subList(i - period + 1, i + 1).average()
        }
    }

    fun upDayRatio(candles: List<Candle>, period: Int = 10): Double {
        val window = if (candles.size > period) candles.takeLast(period) else candles
        if (window.isEmpty()) return 0.5
        return window.count { it.close > it.open }.toDouble() / window.size
    }

    fun lastGapPct(candles: List<Candle>): Double {
        if (candles.size < 2) return 0.0
        val prev = candles[candles.size - 2].close
        val cur = candles.last().open
        return if (prev == 0.0) 0.0 else (cur - prev) / prev * 100.0
    }
}
