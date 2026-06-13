package com.sun.aurum.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.sun.aurum.MainViewModel
import com.sun.aurum.R
import com.sun.aurum.databinding.FragmentNewsBinding
import com.sun.aurum.model.NewsItem
import com.sun.aurum.model.SymbolState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Standalone News tab for the hero symbol (gold). */
class NewsFragment : Fragment() {

    private var _binding: FragmentNewsBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()
    private val symbol = MainViewModel.SYMBOLS.first()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNewsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.swipeRefresh.setOnRefreshListener { vm.refresh() }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.isRefreshing.collectLatest { binding.swipeRefresh.isRefreshing = it }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.states.collectLatest { states -> states[symbol]?.let { render(it) } }
        }
    }

    private fun render(state: SymbolState) {
        binding.llNews.removeAllViews()
        if (state.news.isEmpty()) {
            binding.newsScroll.visibility = View.GONE
            binding.newsEmptyState.visibility = View.VISIBLE
            if (vm.hasGeminiKey) {
                binding.tvNewsEmptyMsg.text =
                    "No news loaded yet. Pull down to refresh and fetch today's gold headlines."
                binding.btnNewsAction.text = "Refresh"
                binding.btnNewsAction.setOnClickListener { vm.refresh() }
            } else {
                binding.tvNewsEmptyMsg.text =
                    "Add a free Gemini key to see the latest gold-market headlines, pulled fresh each day with links to the source."
                binding.btnNewsAction.text = "Add Gemini Key"
                binding.btnNewsAction.setOnClickListener {
                    startActivity(Intent(requireContext(), SettingsActivity::class.java))
                }
            }
            return
        }
        binding.newsEmptyState.visibility = View.GONE
        binding.newsScroll.visibility = View.VISIBLE
        for (item in state.news) addNewsItem(item)
    }

    private fun addNewsItem(item: NewsItem) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.news_bg))
            if (item.url.startsWith("http")) {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) }
                }
                background = with(android.util.TypedValue()) {
                    ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
                    ContextCompat.getDrawable(ctx, resourceId)
                }
            }
        }
        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt())
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.news_divider))
        }
        val tvHeadline = TextView(ctx).apply {
            text = item.headline
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.news_headline))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val tvSummary = TextView(ctx).apply {
            text = item.summary
            textSize = 11f
            setTextColor(ContextCompat.getColor(ctx, R.color.news_summary))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (3 * dp).toInt()
            }
        }
        val sourceDate = buildString {
            append(item.source)
            if (item.date.isNotBlank()) append("  ·  ${item.date}")
            if (item.url.startsWith("http")) append("  ↗")
        }
        val tvSource = TextView(ctx).apply {
            text = sourceDate
            textSize = 10f
            setTextColor(ContextCompat.getColor(ctx, R.color.news_source))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * dp).toInt()
            }
        }
        card.addView(tvHeadline); card.addView(tvSummary); card.addView(tvSource)
        binding.llNews.addView(divider)
        binding.llNews.addView(card)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
