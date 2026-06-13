package com.sun.aurum.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.sun.aurum.MainViewModel

/**
 * Aurum is a single-instrument app (gold). The pager splits that one instrument into
 * three section tabs: the Gold Index, the AI Market Brief, and News.
 */
class QuotePagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount() = TAB_TITLES.size
    override fun createFragment(position: Int): Fragment = when (position) {
        0    -> QuoteFragment.newInstance(MainViewModel.SYMBOLS.first())
        1    -> AiBriefFragment()
        else -> NewsFragment()
    }

    companion object {
        val TAB_TITLES = listOf("Gold", "AI Brief", "News")
    }
}
