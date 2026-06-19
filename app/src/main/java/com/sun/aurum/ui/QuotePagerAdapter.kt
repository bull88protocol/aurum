package com.sun.aurum.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.sun.aurum.MainViewModel

/**
 * Gold is the hero, split into three section tabs (Gold Index · AI Brief · News). A fourth tab
 * surfaces a second instrument — the US Dollar Index (DXY) — through the HMAI engine.
 */
class QuotePagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount() = TAB_TITLES.size
    override fun createFragment(position: Int): Fragment = when (position) {
        0    -> QuoteFragment.newInstance(MainViewModel.SYMBOLS[0])   // GLD — Gold Index card
        1    -> AiBriefFragment()
        2    -> NewsFragment()
        else -> QuoteFragment.newInstance(MainViewModel.SYMBOLS[1])   // DX-Y.NYB — HMAI card
    }

    companion object {
        val TAB_TITLES = listOf("Gold", "AI Brief", "News", "Dollar")
    }
}
