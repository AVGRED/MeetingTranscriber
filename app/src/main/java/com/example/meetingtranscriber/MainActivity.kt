package com.example.meetingtranscriber

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.meetingtranscriber.databinding.ActivityMainBinding
import com.example.meetingtranscriber.ui.history.HistoryFragment
import com.example.meetingtranscriber.ui.meeting.MeetingFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 默认显示会议页面
        if (savedInstanceState == null) {
            showFragment(MeetingFragment(), "meeting")
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_meeting -> {
                    showFragment(MeetingFragment(), "meeting")
                    true
                }
                R.id.nav_history -> {
                    showFragment(HistoryFragment(), "history")
                    true
                }
                else -> false
            }
        }
    }

    private fun showFragment(fragment: Fragment, tag: String) {
        val existing = supportFragmentManager.findFragmentByTag(tag)
        supportFragmentManager.beginTransaction().apply {
            // 隐藏所有已有 fragment
            supportFragmentManager.fragments.forEach {
                if (it.isVisible) hide(it)
            }
            // 显示或添加
            if (existing != null) {
                show(existing)
            } else {
                add(R.id.fragment_container, fragment, tag)
            }
        }.commit()
    }
}
