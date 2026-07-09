package com.example.meetingtranscriber

import android.app.AlertDialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.meetingtranscriber.data.db.AppDatabase
import com.example.meetingtranscriber.data.db.RecoveryStateEntity
import com.example.meetingtranscriber.databinding.ActivityMainBinding
import com.example.meetingtranscriber.ui.apiconfig.ApiConfigFragment
import com.example.meetingtranscriber.ui.detail.DetailFragment
import com.example.meetingtranscriber.ui.history.HistoryFragment
import com.example.meetingtranscriber.ui.home.HomeFragment
import com.example.meetingtranscriber.ui.meeting.MeetingFragment
import com.example.meetingtranscriber.ui.settings.SettingsFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showFragment(HomeFragment(), "home")
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showFragment(HomeFragment(), "home")
                    true
                }
                R.id.nav_api_config -> {
                    showFragment(ApiConfigFragment(), "api_config")
                    true
                }
                R.id.nav_meeting -> {
                    showFragment(MeetingFragment(), "meeting")
                    true
                }
                R.id.nav_history -> {
                    showFragment(HistoryFragment(), "history")
                    true
                }
                R.id.nav_settings -> {
                    showFragment(SettingsFragment(), "settings")
                    true
                }
                else -> false
            }
        }

        // 检查崩溃恢复
        MeetingApplication.pendingRecoveryState?.let { state ->
            showRecoveryDialog(state)
        }
    }

    /** 跳转到指定 tab */
    fun navigateToTab(tabId: Int) {
        binding.bottomNavigation.selectedItemId = tabId
    }

    /** 跳转到 Meeting tab 并启动指定模式 */
    fun navigateToMeeting(mode: String) {
        binding.bottomNavigation.selectedItemId = R.id.nav_meeting
        binding.root.post {
            val frag = supportFragmentManager.findFragmentByTag("meeting") as? MeetingFragment
            when (mode) {
                "realtime" -> frag?.startRealMeeting()
                "offline" -> frag?.startOfflineMeeting()
                "demo" -> frag?.startDemo()
            }
        }
    }

    /** 跳转到历史 tab 并打开会议详情 */
    fun navigateToMeetingDetail(meetingId: Long) {
        binding.bottomNavigation.selectedItemId = R.id.nav_history
        binding.root.post {
            val detail = DetailFragment.newInstance(meetingId)
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, detail, "detail")
                .addToBackStack(null)
                .commit()
        }
    }

    private fun showRecoveryDialog(state: RecoveryStateEntity) {
        AlertDialog.Builder(this)
            .setTitle("检测到未正常结束的会议")
            .setMessage("会议「${state.title}」在上次使用中异常中断，是否恢复？")
            .setPositiveButton("恢复") { _, _ ->
                MeetingApplication.pendingRecoveryState = null
                binding.bottomNavigation.selectedItemId = R.id.nav_meeting
                val meetingFrag = MeetingFragment()
                showFragment(meetingFrag, "meeting")
                binding.root.post {
                    val activeFrag = supportFragmentManager.findFragmentByTag("meeting") as? MeetingFragment
                    activeFrag?.recoverFromCrash(state)
                }
            }
            .setNegativeButton("放弃") { _, _ ->
                MeetingApplication.pendingRecoveryState = null
                lifecycleScope.launch {
                    AppDatabase.getInstance(this@MainActivity)
                        .recoveryStateDao().deleteAll()
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun showFragment(fragment: Fragment, tag: String) {
        val existing = supportFragmentManager.findFragmentByTag(tag)
        supportFragmentManager.beginTransaction().apply {
            supportFragmentManager.fragments.forEach {
                if (it.isVisible) hide(it)
            }
            if (existing != null) {
                show(existing)
            } else {
                add(R.id.fragment_container, fragment, tag)
            }
        }.commit()
    }
}
