package com.example.handycam

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class SettingsPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
    private val pages = listOf(MjpegFragment(), AvcFragment())

    override fun getItemCount(): Int = pages.size
    override fun createFragment(position: Int): Fragment = pages[position]

    fun getMjpegFragment(): MjpegFragment = pages[0] as MjpegFragment
    fun getAvcFragment(): AvcFragment = pages[1] as AvcFragment
}
