package com.sun.aurum.research

import com.sun.aurum.domain.gold.GoldIndexEngine
import com.sun.aurum.model.Candle
import com.sun.aurum.model.CbQuarter
import com.sun.aurum.model.FredObs
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Research harness, not a product test: replays the full downloaded real history (see
 * research/scripts/parse_inputs.py) through the engine and dumps its spot-index and
 * forward-signal series to research/cache/engine/ for the backtest. The dump is the
 * ground truth the Python replica is validated against — the numbers come from the
 * engine itself, so there is no port drift.
 *
 * Skips (JUnit assume) when the research data set is absent, so normal runs of the
 * suite are unaffected.
 */
class EngineHistoryDumpTest {

    private val ny = ZoneId.of("America/New_York")
    private fun msToDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ny).toLocalDate().toString()

    private fun findResearchDir(): File? =
        listOf("../research", "research").map { File(it) }
            .firstOrNull { File(it, "cache/inputs/gld.csv").exists() }

    private fun readCandles(f: File): List<Candle> =
        f.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val p = line.split(',')
            Candle(
                open = p[2].toDouble(), high = p[3].toDouble(), low = p[4].toDouble(),
                close = p[5].toDouble(), volume = p[6].toDouble().toLong(),
                datetimeMs = p[1].toLong() * 1000L,
            )
        }

    private fun readFred(f: File): List<FredObs> =
        f.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val p = line.split(',')
            FredObs(p[0], p[1].toDouble())
        }

    private fun readCbQuarterly(f: File): List<CbQuarter> {
        val re = Regex(""""(\d{4})-Q([1-4])"\s*:\s*(-?[0-9.]+)""")
        return re.findAll(f.readText())
            .map { CbQuarter(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toDouble()) }
            .sortedWith(compareBy({ it.year }, { it.quarter }))
            .toList()
    }

    @Test
    fun dumpEngineHistory() {
        val research = findResearchDir()
        assumeTrue("research data set not present - skipping dump", research != null)
        val inputsDir = File(research!!, "cache/inputs")
        val outDir = File(research, "cache/engine").apply { mkdirs() }

        val gld = readCandles(File(inputsDir, "gld.csv"))
        val dxy = readCandles(File(inputsDir, "dxy.csv"))
        val ry = readFred(File(inputsDir, "dfii10.csv"))
        val inf = readFred(File(inputsDir, "t10yie.csv"))
        val dgs2 = readFred(File(inputsDir, "dgs2.csv"))
        val cbq = readCbQuarterly(File(research, "../data/cb_quarterly.json"))
        check(cbq.size >= 40) { "cb_quarterly.json parse looks wrong: ${cbq.size} quarters" }

        // A) Spot index, daily, via the engine's own computeHistoricalFull (point-in-time CB).
        val rows = GoldIndexEngine.computeHistoricalFull(
            GoldIndexEngine.Inputs(gldCandles = gld, dxyCandles = dxy, realYield = ry, inflation = inf, cbQuarterly = cbq)
        )
        File(outDir, "engine_spot_daily.csv").bufferedWriter().use { w ->
            w.appendLine("date,composite,ry,usd,cb,inf,tech")
            for (r in rows) {
                w.append(msToDate(r.dateMs)).append(',').append(r.composite.toString()).append(',')
                w.append(r.realYield?.toString() ?: "").append(',')
                w.append(r.usd?.toString() ?: "").append(',')
                w.append(r.centralBank?.toString() ?: "").append(',')
                w.append(r.inflation?.toString() ?: "").append(',')
                w.appendLine(r.technical?.toString() ?: "")
            }
        }

        // B) Forward signal at month-ends via compute() on inputs truncated to each date.
        //    Only forwardScore/forwardComponents are read from these calls: the composite from
        //    compute() scores CB at Clock.now, which is wrong for a historical date (dump A is
        //    the point-in-time spot series).
        val gldDates = gld.map { msToDate(it.datetimeMs) }
        val dxyDates = dxy.map { msToDate(it.datetimeMs) }
        val monthEndIdx = LinkedHashMap<String, Int>() // "yyyy-MM" -> last candle index
        gldDates.forEachIndexed { i, d -> monthEndIdx[d.substring(0, 7)] = i }

        File(outDir, "engine_forward_monthly.csv").bufferedWriter().use { w ->
            w.appendLine("date,forward,fRealRate,fTrend,fFed,fRealRateAvail,fTrendAvail,fFedAvail")
            for ((_, idx) in monthEndIdx) {
                if (idx < 65) continue
                val dateStr = gldDates[idx]
                val gldT = gld.subList(0, idx + 1)
                val dxyCut = dxyDates.count { it <= dateStr }
                val dxyT = dxy.subList(0, dxyCut)
                val ryT = ry.takeWhile { it.dateStr <= dateStr }
                val infT = inf.takeWhile { it.dateStr <= dateStr }
                val dgs2T = dgs2.takeWhile { it.dateStr <= dateStr }
                val rep = GoldIndexEngine.compute(
                    GoldIndexEngine.Inputs(gldCandles = gldT, dxyCandles = dxyT, realYield = ryT,
                        inflation = infT, cbQuarterly = cbq, dgs2 = dgs2T)
                )
                val fc = rep.forwardComponents
                fun s(n: Int) = fc.getOrNull(n)?.score?.toString() ?: ""
                fun a(n: Int) = fc.getOrNull(n)?.available?.toString() ?: "false"
                w.append(dateStr).append(',').append(rep.forwardScore.toString()).append(',')
                w.append(s(0)).append(',').append(s(1)).append(',').append(s(2)).append(',')
                w.appendLine("${a(0)},${a(1)},${a(2)}")
            }
        }
        println("EngineHistoryDump: ${rows.size} spot rows, ${monthEndIdx.size} forward month-ends -> $outDir")
    }
}
