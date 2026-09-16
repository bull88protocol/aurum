package com.sun.aurum.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.sun.aurum.MainViewModel

/**
 * Gold, split into four section tabs: Gold Index · AI Brief · News · 20 Days. The last is the 20-day
 * drivers read (what real yields and the dollar did to gold over the last 20 trading days). It
 * replaced the Dollar (DXY) tab.
 */
class QuotePagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount() = TAB_TITLES.size
    override fun createFragment(position: Int): Fragment = when (position) {
        0    -> QuoteFragment.newInstance(MainViewModel.SYMBOLS[0])   // GLD — Gold Index card
        1    -> AiBriefFragment()
        2    -> NewsFragment()
        else -> DriversFragment()
    }

    companion object {
        val TAB_TITLES = listOf("Gold", "AI Brief", "News", "20 Days")
    }
}
