package com.sun.aurum.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.sun.aurum.MainViewModel
import com.sun.aurum.R
import com.sun.aurum.databinding.FragmentDriversBinding
import com.sun.aurum.domain.gold.GoldDriversEngine
import com.sun.aurum.model.DriverLeg
import com.sun.aurum.model.DriversReport
import com.sun.aurum.model.SymbolState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The 20 Days tab: what real yields and the dollar did to gold over the last 20 trading days, and
 * how much of gold's own move that accounts for. Reads GLD's state; the report is built in the
 * same fetch (GoldDriversEngine). Replaced the Dollar (DXY) tab.
 */
class DriversFragment : Fragment() {

    private var _binding: FragmentDriversBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()
    private val symbol = MainViewModel.SYMBOLS.first()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDriversBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.swipeRefresh.setOnRefreshListener { vm.refresh() }
        binding.btnRetry.setOnClickListener { vm.refresh() }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.isRefreshing.collectLatest { binding.swipeRefresh.isRefreshing = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.states.collectLatest { states -> states[symbol]?.let { render(it) } }
        }
    }

    private fun render(state: SymbolState) {
        val report = state.driversReport
        // Full-screen spinner only on the first load, as on the Gold tab.
        if (state.loading && report == null) {
            binding.progressBar.visibility = View.VISIBLE
            binding.scrollContent.visibility = View.GONE
            return
        }
        binding.progressBar.visibility = View.GONE
        binding.scrollContent.visibility = View.VISIBLE

        if (state.error != null) {
            binding.tvError.visibility = View.VISIBLE
            binding.tvError.text = state.error
            binding.btnRetry.visibility = View.VISIBLE
        } else {
            binding.tvError.visibility = View.GONE
            binding.btnRetry.visibility = View.GONE
        }

        if (report == null) {
            binding.cardDrivers.visibility = View.GONE
            binding.tvDriversEmpty.visibility = if (state.error == null) View.VISIBLE else View.GONE
            return
        }
        binding.tvDriversEmpty.visibility = View.GONE
        binding.cardDrivers.visibility = View.VISIBLE
        renderReport(report)
    }

    private fun renderReport(report: DriversReport) {
        val color = colorFor(report.score, report.available)
        binding.tvDriversScore.text = if (report.available) signedScore(report.score) else "--"
        binding.tvDriversLabel.text = report.label
        binding.tvDriversLabel.setTextColor(color)
        binding.driversBar.progress = (report.score + 100f).roundToInt().coerceIn(0, 200)
        binding.driversBar.progressTintList = ColorStateList.valueOf(color)

        val banner = report.legs.filter { !it.available }.joinToString("\n") { leg ->
            if (leg.keyRequired) "Add a free FRED key in Settings to include ${shortName(leg)}."
            else "Couldn't load ${shortName(leg)} (pull to refresh)."
        }
        binding.tvDriversMissing.visibility = if (banner.isEmpty()) View.GONE else View.VISIBLE
        binding.tvDriversMissing.text = banner

        binding.driversChart.points = report.history

        binding.llDriverLegs.removeAllViews()
        for (leg in report.legs) addLegRow(leg)

        val gold = report.goldChangePct
        binding.llBreakdownSection.visibility = if (gold == null) View.GONE else View.VISIBLE
        binding.llBreakdown.removeAllViews()
        if (gold != null) {
            addBreakdownRow("Gold (GLD), last 20 trading days", gold, bold = true)
            for (leg in report.legs) {
                addBreakdownRow("from ${shortName(leg)}", if (leg.available) leg.goldImpactPct else null)
            }
            addBreakdownRow("everything else", report.otherPct)
        }
    }

    private fun addLegRow(leg: DriverLeg) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }

        val labelRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        labelRow.addView(TextView(ctx).apply {
            text = leg.name
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.pillar_label))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        labelRow.addView(TextView(ctx).apply {
            text = if (leg.available) signedScore(leg.score) else "N/A"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        })
        labelRow.addView(TextView(ctx).apply {
            text = if (leg.available) GoldDriversEngine.toLabel(leg.score) else ""
            textSize = 10f
            setTextColor(ContextCompat.getColor(ctx, R.color.pillar_sublabel))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (8 * dp).toInt() }
        })
        row.addView(labelRow)

        if (leg.available) {
            row.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 200
                progress = (leg.score + 100f).roundToInt().coerceIn(0, 200)
                progressTintList = ColorStateList.valueOf(colorFor(leg.score, true))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt())
                    .apply { topMargin = (2 * dp).toInt() }
            })
        }

        row.addView(TextView(ctx).apply {
            text = when {
                leg.available   -> legDetail(leg)
                leg.keyRequired -> "Needs a FRED key (Settings)"
                else            -> "Not enough data yet"
            }
            textSize = 11f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (2 * dp).toInt() }
        })
        binding.llDriverLegs.addView(row)
    }

    private fun addBreakdownRow(label: String, pct: Double?, bold: Boolean = false) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(if (bold) 0 else (12 * dp).toInt(), (3 * dp).toInt(), 0, (3 * dp).toInt())
        }
        row.addView(TextView(ctx).apply {
            text = label
            textSize = if (bold) 13f else 12f
            setTextColor(ContextCompat.getColor(ctx, if (bold) R.color.text_primary else R.color.text_secondary))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(ctx).apply {
            text = pct?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "n/a"
            textSize = if (bold) 13f else 12f
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setTextColor(when {
                pct == null || abs(pct) < 0.05 -> ContextCompat.getColor(ctx, R.color.text_secondary)
                pct > 0 -> ContextCompat.getColor(ctx, R.color.up_green)
                else    -> ContextCompat.getColor(ctx, R.color.down_red)
            })
        })
        binding.llBreakdown.addView(row)
    }

    /** "2.62%  ↑ +18 bp in 20 days · as of Sep 15" / "97.84  ↓ -0.82% in 20 days · as of Sep 15" */
    private fun legDetail(leg: DriverLeg): String {
        val realYield = leg.name == GoldDriversEngine.REAL_YIELD_NAME
        val flat = if (realYield) abs(leg.change) < 0.005 else abs(leg.change) < 0.05
        val arrow = when { flat -> "→"; leg.change > 0 -> "↑"; else -> "↓" }
        val level = if (realYield) String.format(Locale.US, "%.2f%%", leg.level)
                    else String.format(Locale.US, "%.2f", leg.level)
        val move = if (realYield) String.format(Locale.US, "%+d bp", (leg.change * 100).roundToInt())
                   else String.format(Locale.US, "%+.2f%%", leg.change)
        return "$level  $arrow $move in 20 days${asOfNote(leg.asOf)}"
    }

    private fun asOfNote(date: String): String = try {
        " · as of " + LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
    } catch (e: Exception) { "" }

    private fun shortName(leg: DriverLeg) =
        if (leg.name == GoldDriversEngine.REAL_YIELD_NAME) "real yields" else "the dollar"

    private fun signedScore(score: Float): String {
        val v = score.roundToInt()
        return if (v == 0) "0" else String.format(Locale.US, "%+d", v)
    }

    private fun colorFor(score: Float, available: Boolean): Int = when {
        !available    -> ContextCompat.getColor(requireContext(), R.color.text_secondary)
        score >= 30f  -> Color.parseColor("#26A69A")
        score <= -30f -> Color.parseColor("#EF5350")
        else          -> Color.parseColor("#FFA726")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
