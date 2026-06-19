package com.sun.aurum.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.sun.aurum.MainViewModel
import com.sun.aurum.R
import com.sun.aurum.databinding.FragmentQuoteBinding
import com.sun.aurum.model.GoldComponentScore
import com.sun.aurum.model.PillarResult
import com.sun.aurum.model.SymbolState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.NumberFormat
import java.util.*

class QuoteFragment : Fragment() {

    private var _binding: FragmentQuoteBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()
    private lateinit var symbol: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        symbol = requireArguments().getString(ARG_SYMBOL)!!
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQuoteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.swipeRefresh.setOnRefreshListener { vm.refresh() }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.isRefreshing.collectLatest { binding.swipeRefresh.isRefreshing = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.states.collectLatest { states ->
                states[symbol]?.let { render(it) }
            }
        }
    }

    private fun render(state: SymbolState) {
        // Full-screen spinner only on the first load (no data yet); later refreshes
        // keep content visible and use the pull-to-refresh spinner instead.
        if (state.loading && state.quote == null && state.goldIndexReport == null) {
            binding.progressBar.visibility = View.VISIBLE
            binding.scrollContent.visibility = View.GONE
            return
        }
        binding.progressBar.visibility = View.GONE
        binding.scrollContent.visibility = View.VISIBLE

        // Error
        if (state.error != null) {
            binding.tvError.visibility = View.VISIBLE
            binding.tvError.text = state.error
        } else {
            binding.tvError.visibility = View.GONE
        }

        // Quote header
        val q = state.quote
        if (q != null) {
            val nf = NumberFormat.getNumberInstance(Locale.US)
            // Show "Gold" for GLD tab, otherwise use symbol
            binding.tvSymbol.text = MainViewModel.displayName(q.symbol)
            binding.tvPrice.text = "$${String.format("%.2f", q.price)}"
            val changeStr = "${if (q.change >= 0) "+" else ""}${String.format("%.2f", q.change)} " +
                    "(${if (q.changePct >= 0) "+" else ""}${String.format("%.2f", q.changePct)}%)"
            binding.tvChange.text = changeStr
            val upColor   = ContextCompat.getColor(requireContext(), R.color.up_green)
            val downColor = ContextCompat.getColor(requireContext(), R.color.down_red)
            binding.tvChange.setTextColor(if (q.change >= 0) upColor else downColor)

            // Extended-hours badge + reference close
            when (q.marketState) {
                "PRE" -> {
                    binding.tvMarketState.text = "PRE MARKET"
                    binding.tvMarketState.setTextColor(Color.parseColor("#FFA726"))
                    binding.tvMarketState.visibility = View.VISIBLE
                    binding.tvRegularClose.text = "vs prev close $${String.format("%.2f", q.previousClose)}"
                    binding.tvRegularClose.visibility = View.VISIBLE
                }
                "POST", "POSTPOST" -> {
                    binding.tvMarketState.text = "AFTER HOURS"
                    binding.tvMarketState.setTextColor(Color.parseColor("#7E57C2"))
                    binding.tvMarketState.visibility = View.VISIBLE
                    binding.tvRegularClose.text = "vs regular close $${String.format("%.2f", q.regularMarketPrice)}"
                    binding.tvRegularClose.visibility = View.VISIBLE
                }
                else -> {
                    binding.tvMarketState.visibility = View.GONE
                    binding.tvRegularClose.visibility = View.GONE
                }
            }

            binding.tvHighLow.text = "H: $${String.format("%.2f", q.high)}  L: $${String.format("%.2f", q.low)}"
            binding.tvVolume.text = "Vol: ${nf.format(q.volume)}"
        }

        // Intraday chart
        val intraday = state.intradayPoints
        binding.priceChart.points = intraday
        binding.priceChart.isUp = (q?.change ?: 0.0) >= 0
        if (state.lastUpdated > 0) {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
            cal.timeInMillis = state.lastUpdated
            val src = if (state.usingGoogleData) "GF" else "YF"
            binding.tvLastUpdated.text = "$src · ${String.format("%d:%02d ET", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))}"
        }

        // Gold Index (GLD only) vs HMAI (all others)
        if (symbol == "GLD") {
            binding.cardHmai.visibility = View.GONE
            renderGoldIndex(state)
        } else {
            binding.cardGoldIndex.visibility = View.GONE
            renderHmai(state)
        }

        // Market Brief and News now live in their own tabs (AiBriefFragment / NewsFragment).
    }

    private fun renderGoldIndex(state: SymbolState) {
        val report = state.goldIndexReport
        if (report == null) {
            binding.cardGoldIndex.visibility = View.GONE
            return
        }
        binding.cardGoldIndex.visibility = View.VISIBLE

        // Status banner — keep "needs a key" distinct from "couldn't load" so a transient data
        // failure (e.g. a dropped DXY fetch) no longer masquerades as a missing-key/config problem.
        val unavailable = report.components.filter { !it.available }
        fun names(list: List<GoldComponentScore>) = list.joinToString(" · ") { it.name.substringBefore(" (") }
        val needKey = names(unavailable.filter { it.keyRequired })
        val noData  = names(unavailable.filterNot { it.keyRequired })
        val banner = buildList {
            if (needKey.isNotEmpty()) add("Add a FRED key for: $needKey")
            if (noData.isNotEmpty())  add("Couldn't load (pull to refresh): $noData")
        }.joinToString("\n")
        if (banner.isNotEmpty()) {
            binding.tvGoldMissing.visibility = View.VISIBLE
            binding.tvGoldMissing.text = banner
        } else {
            binding.tvGoldMissing.visibility = View.GONE
        }

        // Composite score
        val scoreColor = when (report.compositeLabel) {
            "BULLISH" -> Color.parseColor("#26A69A")
            "BEARISH" -> Color.parseColor("#EF5350")
            else      -> Color.parseColor("#FFA726")
        }
        binding.tvGoldIndexScore.text = "${String.format("%.0f", report.compositeScore)} / 100"
        binding.tvGoldIndexLabel.text = report.compositeLabel
        binding.tvGoldIndexLabel.setTextColor(scoreColor)

        // Historical chart
        binding.goldIndexChart.points = report.historicalScores

        // Component rows
        binding.llGoldComponents.removeAllViews()
        for (comp in report.components) {
            addGoldComponentRow(binding.llGoldComponents, comp)
        }

        // Forward Signal (3-6M outlook)
        val fwdColor = when (report.forwardLabel) {
            "BULLISH" -> Color.parseColor("#26A69A")
            "BEARISH" -> Color.parseColor("#EF5350")
            else      -> Color.parseColor("#FFA726")
        }
        binding.tvForwardScore.text = "${String.format("%.0f", report.forwardScore)} / 100"
        binding.tvForwardLabel.text = report.forwardLabel
        binding.tvForwardLabel.setTextColor(fwdColor)
        binding.forwardBar.progress = report.forwardScore.toInt()
        binding.forwardBar.progressTintList = android.content.res.ColorStateList.valueOf(fwdColor)
        binding.llForwardComponents.removeAllViews()
        for (comp in report.forwardComponents) {
            addGoldComponentRow(binding.llForwardComponents, comp)
        }

        // Download CSV button
        binding.btnDownloadGoldCsv.setOnClickListener {
            downloadGoldIndexCsv()
        }
    }

    private fun renderHmai(state: SymbolState) {
        val hmai = state.hmaiReport
        if (hmai != null) {
            binding.cardHmai.visibility = View.VISIBLE
            val scoreColor = when (hmai.compositeLabel) {
                "RISK ON"  -> Color.parseColor("#26A69A")
                "CAUTION"  -> Color.parseColor("#FFA726")
                else       -> Color.parseColor("#EF5350")
            }
            binding.tvComposite.text = "${String.format("%.0f", hmai.composite)} / 100"
            binding.tvCompositeLabel.text = hmai.compositeLabel
            binding.tvCompositeLabel.setTextColor(scoreColor)
            binding.compositeBar.progress = hmai.composite.toInt()
            binding.compositeBar.progressTintList = android.content.res.ColorStateList.valueOf(scoreColor)

            // Circuit breaker
            if (hmai.circuitBreaker.triggers.isNotEmpty()) {
                binding.tvCbWarning.visibility = View.VISIBLE
                binding.tvCbWarning.text = "⚠ ${hmai.circuitBreaker.description}: ${hmai.circuitBreaker.triggers.joinToString(", ")}"
            } else {
                binding.tvCbWarning.visibility = View.GONE
            }

            // Pillar breakdown
            binding.llPillars.removeAllViews()
            for (p in hmai.pillars) {
                addPillarRow(p)
            }
        } else {
            binding.cardHmai.visibility = View.GONE
        }
    }

    private fun addGoldComponentRow(container: LinearLayout, comp: GoldComponentScore) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }

        val labelRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tvName = TextView(ctx).apply {
            text = comp.name
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.pillar_label))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvScore = TextView(ctx).apply {
            text = if (comp.available) "${String.format("%.0f", comp.score)}/100" else "N/A"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        }
        val tvLabel = TextView(ctx).apply {
            text = comp.label
            textSize = 10f
            setTextColor(ContextCompat.getColor(ctx, R.color.pillar_sublabel))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = (8 * dp).toInt()
            layoutParams = lp
        }
        labelRow.addView(tvName); labelRow.addView(tvScore); labelRow.addView(tvLabel)

        val tvDetail = TextView(ctx).apply {
            text = comp.detail
            textSize = 11f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (2 * dp).toInt()
            layoutParams = lp
        }

        if (comp.available) {
            val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = comp.score.toInt()
                val barColor = when {
                    comp.score >= 70f -> Color.parseColor("#26A69A")
                    comp.score >= 45f -> Color.parseColor("#FFA726")
                    else              -> Color.parseColor("#EF5350")
                }
                progressTintList = android.content.res.ColorStateList.valueOf(barColor)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (6 * dp).toInt()).apply {
                    topMargin = (2 * dp).toInt()
                }
            }
            row.addView(labelRow); row.addView(bar); row.addView(tvDetail)
        } else {
            row.addView(labelRow); row.addView(tvDetail)
        }

        container.addView(row)
    }

    private fun downloadGoldIndexCsv() {
        viewLifecycleOwner.lifecycleScope.launch {
            val csv = vm.generateGoldIndexHistoryCsv()
            if (csv == null) {
                Toast.makeText(requireContext(), "Not enough data to generate CSV", Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                val ctx = requireContext()
                val file = File(ctx.getExternalFilesDir(null), "gold_index_history.csv")
                file.writeText(csv)
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share Gold Index CSV"))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addPillarRow(p: PillarResult) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
        }

        // Label row
        val labelRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tvName = TextView(ctx).apply {
            text = "P${p.pillar}: ${p.name}"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.pillar_label))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvScore = TextView(ctx).apply {
            text = "${String.format("%.0f", p.score)}/${p.maxScore.toInt()}"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
        }
        val tvLabel = TextView(ctx).apply {
            text = p.label
            textSize = 10f
            setTextColor(ContextCompat.getColor(ctx, R.color.pillar_sublabel))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        labelRow.addView(tvName); labelRow.addView(tvScore); labelRow.addView(tvLabel)

        // Progress bar
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = p.maxScore.toInt()
            progress = p.score.toInt()
            val pct = if (p.maxScore > 0) p.score / p.maxScore else 0.0
            val barColor = when {
                pct >= 0.7 -> Color.parseColor("#26A69A")
                pct >= 0.4 -> Color.parseColor("#FFA726")
                else       -> Color.parseColor("#EF5350")
            }
            progressTintList = android.content.res.ColorStateList.valueOf(barColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (6 * resources.displayMetrics.density).toInt()).apply {
                topMargin = (2 * resources.displayMetrics.density).toInt()
            }
        }
        row.addView(labelRow); row.addView(bar)
        binding.llPillars.addView(row)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SYMBOL = "symbol"
        fun newInstance(symbol: String) = QuoteFragment().apply {
            arguments = Bundle().apply { putString(ARG_SYMBOL, symbol) }
        }
    }
}
