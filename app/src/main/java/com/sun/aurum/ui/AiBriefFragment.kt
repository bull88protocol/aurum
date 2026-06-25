package com.sun.aurum.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.sun.aurum.MainViewModel
import com.sun.aurum.R
import com.sun.aurum.databinding.FragmentAiBriefBinding
import com.sun.aurum.model.SymbolState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Standalone AI Market Brief tab for the hero symbol (gold). */
class AiBriefFragment : Fragment() {

    private var _binding: FragmentAiBriefBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()
    private val symbol = MainViewModel.SYMBOLS.first()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAiBriefBinding.inflate(inflater, container, false)
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
        val hasBrief = !state.geminiDescription.isNullOrBlank()
                || !state.geminiYesterdayRecap.isNullOrBlank()
                || !state.geminiTodayOutlook.isNullOrBlank()

        if (!hasBrief) {
            binding.aiScroll.visibility = View.GONE
            binding.aiEmptyState.visibility = View.VISIBLE
            if (vm.hasGeminiKey) {
                binding.tvAiEmptyMsg.text =
                    "No AI brief loaded yet. Pull down to refresh and fetch today's analysis."
                binding.btnAiAction.text = "Refresh"
                binding.btnAiAction.setOnClickListener { vm.refresh() }
            } else {
                binding.tvAiEmptyMsg.text =
                    "Add a free Gemini key to unlock daily AI analysis — market sentiment, a last-session recap, the next-session outlook, and the key factors moving gold."
                binding.btnAiAction.text = "Add Gemini Key"
                binding.btnAiAction.setOnClickListener {
                    startActivity(Intent(requireContext(), SettingsActivity::class.java))
                }
            }
            return
        }
        binding.aiEmptyState.visibility = View.GONE
        binding.aiScroll.visibility = View.VISIBLE

        val signal = state.geminiSignal ?: "NEUTRAL"
        binding.tvAiSignal.text = signal
        binding.tvAiSignal.setTextColor(
            when (signal) {
                "BULLISH" -> ContextCompat.getColor(requireContext(), R.color.up_green)
                "BEARISH" -> ContextCompat.getColor(requireContext(), R.color.down_red)
                else      -> ContextCompat.getColor(requireContext(), R.color.warning)
            }
        )
        binding.tvAiScore.text = "Score: ${state.geminiScore ?: "--"}/100"

        binding.tvAiDescription.text = state.geminiDescription ?: ""
        binding.tvAiDescription.visibility = if (state.geminiDescription.isNullOrBlank()) View.GONE else View.VISIBLE

        binding.tvYesterdayLabel.text = state.lastSessionLabel?.takeIf { it.isNotBlank() }
            ?.let { "${it.uppercase()} SESSION" } ?: "LAST SESSION"
        binding.tvTodayLabel.text = state.nextSessionLabel?.takeIf { it.isNotBlank() }
            ?.let { "${it.uppercase()} OUTLOOK" } ?: "NEXT SESSION OUTLOOK"

        binding.tvYesterdayRecap.text = state.geminiYesterdayRecap ?: ""
        binding.tvYesterdayRecap.visibility = if (state.geminiYesterdayRecap.isNullOrBlank()) View.GONE else View.VISIBLE

        binding.tvTodayOutlook.text = state.geminiTodayOutlook ?: ""
        binding.tvTodayOutlook.visibility = if (state.geminiTodayOutlook.isNullOrBlank()) View.GONE else View.VISIBLE

        binding.tvAiFactors.text = state.geminiKeyFactors.joinToString("\n") { "• $it" }
        binding.tvAiFactors.visibility = if (state.geminiKeyFactors.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
