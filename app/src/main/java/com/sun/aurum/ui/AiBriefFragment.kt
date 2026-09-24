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
            // Since v2.9.0 a key is no longer what stands between the user and a brief — the
            // shared feed gives everyone one. So the empty state means the fetch hasn't landed
            // or couldn't reach the feed, and the offer of a key is about freshness, not access.
            binding.tvAiEmptyMsg.text = when {
                state.briefLoading  -> "Loading today's gold brief…"
                vm.hasGeminiKey     -> "No AI brief loaded yet. Pull down to refresh and fetch today's analysis."
                else                -> "No AI brief loaded yet — the shared brief couldn't be reached. Pull down to try again, or add your own free Gemini key in Settings for a brief written against the live price each time you refresh."
            }
            binding.btnAiAction.text = if (vm.hasGeminiKey) "Refresh" else "Add Gemini Key"
            binding.btnAiAction.setOnClickListener {
                if (vm.hasGeminiKey) vm.refresh()
                else startActivity(Intent(requireContext(), SettingsActivity::class.java))
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

        renderProvenance(state)

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


    /**
     * Says where the brief came from. A feed brief was written up to an hour ago against the price
     * at that moment, so its numbers can disagree with the quote on the Gold tab — which is exactly
     * the inconsistency v2.6.0 set out to remove, and the honest fix is to date it rather than
     * pretend it is live. A brief from the user's own key is current by construction, so it says
     * nothing unless a fresher one is on its way.
     */
    private fun renderProvenance(state: SymbolState) {
        val text = when {
            state.briefLoading && state.briefFromFeed -> "Shared brief — refreshing with your Gemini key…"
            state.briefLoading                        -> "Refreshing…"
            state.briefFromFeed                       -> "Shared brief" + formatGeneratedAt(state.briefGeneratedUtc)
            else                                      -> null
        }
        binding.tvAiProvenance.text = text ?: ""
        binding.tvAiProvenance.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** " · written 6:54 PM, 23 Sep" in the device's own time zone, or "" if the stamp is unusable. */
    private fun formatGeneratedAt(generatedUtc: String?): String = try {
        val at = java.time.Instant.parse(generatedUtc)
        " · written " + java.time.format.DateTimeFormatter.ofPattern("h:mm a, d MMM")
            .withZone(java.time.ZoneId.systemDefault())
            .format(at)
    } catch (e: Exception) { "" }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
