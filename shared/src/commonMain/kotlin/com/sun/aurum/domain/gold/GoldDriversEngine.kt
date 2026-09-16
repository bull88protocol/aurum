package com.sun.aurum.domain.gold

import com.sun.aurum.model.Candle
import com.sun.aurum.model.DailyIndexPoint
import com.sun.aurum.model.DriverLeg
import com.sun.aurum.model.DriversReport
import com.sun.aurum.model.FredObs
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.sqrt

/**
 * 20-Day Drivers: what real yields and the dollar did to gold over the last 20 trading days.
 *
 * A NOWCAST, and presented as one. Backtested 2007-2026 on the app's own inputs (GLD, DXY, FRED
 * DFII10) in research/DRIVERS_20D_2026-09-16.md: Spearman +0.51 with gold's return over the SAME 20
 * days, positive in every era, but +0.06 with the NEXT 20 days and -0.01 with the next 3 months.
 * It explains the month just gone and does not forecast. The outlook stays the Forward Signal,
 * which reads the same DFII10 as a LEVEL (high = bullish over 3-6M), so the two can colour the same
 * yield in opposite directions. Each is right on its own horizon.
 *
 * Each leg is the 20-observation change on the series' own spine, divided by the sample std of its
 * last 252 such changes (at least 126), and mapped to -100 * clamp(z / 2, -1, 1): rising yields or
 * a rising dollar read as a headwind. The headline is the mean of the available legs (50/50).
 * Reweighting 60/40, windows of 10-63 days, the Fed's broad dollar instead of DXY and a tanh squash
 * all measured the same, so none of those choices is load-bearing.
 *
 * The breakdown splits gold's own 20-day move into a real-yield part, a dollar part and everything
 * else, with fixed betas from a 2007-2026 regression of GLD's 20-day return on the two changes.
 * That regression explains 29% of a typical month, and its betas drift by era (real yield -3.6 to
 * -17.7, dollar -0.56 to -0.98), so the split is a rough guide, not an accounting identity.
 */
object GoldDriversEngine {

    const val WINDOW = 20
    private const val VOL_WINDOW = 252
    private const val MIN_VOL_OBS = 126
    private const val HISTORY_DAYS = 252

    /** Gold % per +1.00 percentage point of DFII10 over 20 observations (GLD, 2007-2026 OLS). */
    const val BETA_REAL_YIELD = -6.6
    /** Gold % per +1% in DXY over 20 observations (GLD, 2007-2026 OLS). */
    const val BETA_DOLLAR = -0.76

    const val REAL_YIELD_NAME = "Real yields (10Y TIPS)"
    const val DOLLAR_NAME = "Dollar (DXY)"

    data class Inputs(
        val gldCandles: List<Candle>,
        val dxyCandles: List<Candle>,
        val realYield: List<FredObs>,   // FRED DFII10
    )

    /** One replayed day: the chart's source, and what the research dump writes. */
    data class Row(val dateMs: Long, val realYieldScore: Float?, val dollarScore: Float?, val score: Float?)

    private val NY = TimeZone.of("America/New_York")
    private fun epochMsToDate(ms: Long): String =
        Instant.fromEpochMilliseconds(ms).toLocalDateTime(NY).date.toString()

    fun compute(inputs: Inputs): DriversReport {
        val ry  = Leg.of(inputs.realYield.map { it.dateStr to it.value }, pct = false)
        val usd = Leg.of(inputs.dxyCandles.map { epochMsToDate(it.datetimeMs) to it.close }, pct = true)
        val gld = inputs.gldCandles

        // Read both legs as of gold's latest session, so the headline, the breakdown and the last
        // point on the chart all describe the same day.
        val asOf = gld.lastOrNull()?.let { epochMsToDate(it.datetimeMs) }
        val ryLeg = legAt(ry, ry.indexAtOrBefore(asOf), REAL_YIELD_NAME, BETA_REAL_YIELD,
            keyRequired = inputs.realYield.isEmpty())
        val usdLeg = legAt(usd, usd.indexAtOrBefore(asOf), DOLLAR_NAME, BETA_DOLLAR, keyRequired = false)
        val legs = listOf(ryLeg, usdLeg)

        val available = legs.filter { it.available }
        val score = if (available.isEmpty()) 0f else available.map { it.score }.average().toFloat()

        val goldChange = if (gld.size > WINDOW) {
            val pct = (gld.last().close / gld[gld.size - 1 - WINDOW].close - 1) * 100
            pct.takeIf { it.isFinite() }
        } else null
        val other = goldChange?.let { g -> g - available.sumOf { it.goldImpactPct } }

        val history = replay(gld, ry, usd, HISTORY_DAYS)
            .mapNotNull { r -> r.score?.let { DailyIndexPoint(r.dateMs, it) } }

        return DriversReport(
            score         = score,
            label         = if (available.isEmpty()) "N/A" else toLabel(score),
            legs          = legs,
            goldChangePct = goldChange,
            otherPct      = other,
            history       = history,
        )
    }

    /** The drivers read replayed over the last [days] gold sessions (all of them by default). */
    fun history(inputs: Inputs, days: Int = Int.MAX_VALUE): List<Row> = replay(
        inputs.gldCandles,
        Leg.of(inputs.realYield.map { it.dateStr to it.value }, pct = false),
        Leg.of(inputs.dxyCandles.map { epochMsToDate(it.datetimeMs) to it.close }, pct = true),
        days,
    )

    fun toLabel(score: Float): String = when {
        score >= 70f  -> "STRONG TAILWIND"
        score >= 30f  -> "TAILWIND"
        score > -30f  -> "MIXED"
        score > -70f  -> "HEADWIND"
        else          -> "STRONG HEADWIND"
    }

    private fun replay(gld: List<Candle>, ry: Leg, usd: Leg, days: Int): List<Row> {
        if (gld.isEmpty()) return emptyList()
        val start = maxOf(0, gld.size - days)
        return (start until gld.size).map { i ->
            val date = epochMsToDate(gld[i].datetimeMs)
            val rs = ry.scoreAt(ry.indexAtOrBefore(date))
            val us = usd.scoreAt(usd.indexAtOrBefore(date))
            val legs = listOfNotNull(rs, us)
            Row(gld[i].datetimeMs, rs, us, if (legs.isEmpty()) null else legs.average().toFloat())
        }
    }

    private fun legAt(leg: Leg, i: Int, name: String, beta: Double, keyRequired: Boolean): DriverLeg {
        val score = leg.scoreAt(i)
            ?: return DriverLeg(name = name, available = false, keyRequired = keyRequired)
        return DriverLeg(
            name          = name,
            available     = true,
            level         = leg.values[i],
            change        = leg.change[i],
            score         = score,
            goldImpactPct = beta * leg.change[i],
            asOf          = leg.dates[i],
        )
    }

    /** One series on its own observation spine, with its 20-obs change and leg score precomputed. */
    private class Leg private constructor(
        val dates: List<String>,
        val values: DoubleArray,
        val change: DoubleArray,
        val score: DoubleArray,   // NaN where there isn't enough history yet
    ) {
        val size get() = dates.size

        fun scoreAt(i: Int): Float? =
            if (i in 0 until size && score[i].isFinite()) score[i].toFloat() else null

        /** Index of the last observation dated on or before [date] (the latest one if null), or -1. */
        fun indexAtOrBefore(date: String?): Int {
            if (date == null) return size - 1
            var lo = 0; var hi = size   // count of dates <= date
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (dates[mid] <= date) lo = mid + 1 else hi = mid }
            return lo - 1
        }

        companion object {
            fun of(points: List<Pair<String, Double>>, pct: Boolean): Leg {
                // One value per date, last wins, in date order (same as the Gold Index's series).
                val byDate = LinkedHashMap<String, Double>()
                for ((d, v) in points) byDate[d] = v
                val sorted = byDate.entries.sortedBy { it.key }
                val dates = sorted.map { it.key }
                val values = DoubleArray(sorted.size) { sorted[it].value }
                val n = values.size

                val change = DoubleArray(n) { Double.NaN }
                for (i in WINDOW until n) {
                    change[i] = if (pct) (values[i] / values[i - WINDOW] - 1) * 100 else values[i] - values[i - WINDOW]
                }
                val score = DoubleArray(n) { Double.NaN }
                for (i in WINDOW until n) {
                    val lo = maxOf(WINDOW, i - VOL_WINDOW + 1)
                    val count = i - lo + 1
                    if (count < MIN_VOL_OBS || !change[i].isFinite()) continue
                    var mean = 0.0
                    for (j in lo..i) mean += change[j]
                    mean /= count
                    var sumSq = 0.0
                    for (j in lo..i) { val dev = change[j] - mean; sumSq += dev * dev }
                    val sd = sqrt(sumSq / (count - 1))
                    if (!sd.isFinite()) continue
                    score[i] = if (sd > 0) -100.0 * (change[i] / sd / 2).coerceIn(-1.0, 1.0) else 0.0
                }
                return Leg(dates, values, change, score)
            }
        }
    }
}
