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

    // ── Tab Fragment 实例复用：避免每次切 Tab 创建新实例（减少 GC + ViewModel 重建） ──
    private val homeFragment by lazy { HomeFragment() }
    private val apiConfigFragment by lazy { ApiConfigFragment() }
    private val meetingFragment by lazy { MeetingFragment() }
    private val historyFragment by lazy { HistoryFragment() }
    private val settingsFragment by lazy { SettingsFragment() }

    /** Tab Fragment 的 tag 集合，用于区分 Tab 与非 Tab（detail/album 等 back stack 条目） */
    private val tabTags = setOf("home", "api_config", "meeting", "history", "settings")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showFragment(homeFragment, "home")
        } else {
            // Activity 重建后 FragmentManager 自动恢复了所有 Fragment。
            // 若 back stack 中有非 Tab 页面（detail/album），切 Tab 时
            // showFragment 会先清 back stack 再切换，避免 lifecycle 冲突 → 白屏。
            // 但刚重建时不需要立即做任何事，等待用户操作即可。
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showFragment(homeFragment, "home")
                    true
                }
                R.id.nav_api_config -> {
                    showFragment(apiConfigFragment, "api_config")
                    true
                }
                R.id.nav_meeting -> {
                    showFragment(meetingFragment, "meeting")
                    true
                }
                R.id.nav_history -> {
                    showFragment(historyFragment, "history")
                    true
                }
                R.id.nav_settings -> {
                    showFragment(settingsFragment, "settings")
                    true
                }
                else -> false
            }
        }

        // 检查崩溃恢复（检查在后台线程做，等完成信号再读结果；晚几百 ms 无感）
        lifecycleScope.launch {
            MeetingApplication.recoveryCheckDone.await()
            MeetingApplication.pendingRecoveryState?.let { state ->
                showRecoveryDialog(state)
            }
        }
    }

    /** 跳转到指定 tab */
    fun navigateToTab(tabId: Int) {
        binding.bottomNavigation.selectedItemId = tabId
    }

    /** 跳转到 Meeting tab 并启动指定模式 */
    fun navigateToMeeting(mode: String) {
        binding.bottomNavigation.selectedItemId = R.id.nav_meeting
        // 同步执行挂起的事务后直接取 Fragment：原来的 binding.root.post{} 存在时序
        // 竞态——post 先于事务执行时拿到 null，启动请求被静默丢弃（点了没反应）
        supportFragmentManager.executePendingTransactions()
        val frag = supportFragmentManager.findFragmentByTag("meeting") as? MeetingFragment
        if (frag == null) {
            android.util.Log.e("MainActivity", "navigateToMeeting: meeting fragment 不存在")
            return
        }
        when (mode) {
            "realtime" -> frag.startOnlineMeeting()
            "offline" -> frag.startOfflineMeeting()
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

    /** 打开 App 内相册页 */
    fun navigateToAlbum() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, com.example.meetingtranscriber.ui.album.AlbumFragment(), "album")
            .addToBackStack(null)
            .commit()
    }

    private fun showRecoveryDialog(state: RecoveryStateEntity) {
        AlertDialog.Builder(this)
            .setTitle("检测到未正常结束的会议")
            .setMessage("会议「${state.title}」在上次使用中异常中断，是否恢复？")
            .setPositiveButton("恢复") { _, _ ->
                MeetingApplication.pendingRecoveryState = null
                binding.bottomNavigation.selectedItemId = R.id.nav_meeting
                supportFragmentManager.executePendingTransactions()
                val activeFrag = supportFragmentManager.findFragmentByTag("meeting") as? MeetingFragment
                activeFrag?.recoverFromCrash(state)
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

    /**
     * Tab 切换核心方法。
     *
     * 设计要点：
     * 1. 非 Tab 页面（detail/album 等 back stack 条目）不直接操作——先清 back stack，
     *    避免 setMaxLifecycle 与 FragmentManager 的 back stack 生命周期管理冲突。
     * 2. 只操作已知 Tab Fragment 的 hide/show，不再遍历全部 Fragment。
     * 3. existing 的 view 可能已被 destroy（被 replace 移除后在恢复前的情况），
     *    此时走 add 分支创建新 Fragment。
     */
    private fun showFragment(fragment: Fragment, tag: String) {
        // ═══ 关键修复：若有非 Tab 页面在 back stack 顶部，先清栈 ═══
        // 不直接 hide+setMaxLifecycle 非 Tab Fragment——它们在 back stack 中，
        // 生命周期由 FragmentManager 管理；外部干预会导致 popBackStack 时
        // 生命周期恢复不一致 → 白屏（真机复现过的核心原因）。
        val topFrag = supportFragmentManager.fragments.lastOrNull()
        val hasNonTabOnTop = topFrag != null && topFrag.tag !in tabTags
        if (hasNonTabOnTop) {
            // popBackStackImmediate 同步执行：确保下一行 findFragmentByTag
            // 看到的是清栈后 Tab Fragment 恢复完成的状态
            supportFragmentManager.popBackStackImmediate()
        }

        val existing = supportFragmentManager.findFragmentByTag(tag)

        // 防御：如果 existing Fragment 的 view 已被 destroy 但 Fragment 对象
        // 还残留在 FM 中（极端情况：back stack 恢复异常），走 add 重建。
        val needsRecreate = existing != null && existing.view == null

        supportFragmentManager.beginTransaction().apply {
            // 只隐藏其他 Tab Fragment（不再遍历所有 Fragment）
            tabTags.forEach { tabTag ->
                if (tabTag == tag) return@forEach
                val tabFrag = supportFragmentManager.findFragmentByTag(tabTag)
                if (tabFrag != null && tabFrag.isVisible) {
                    hide(tabFrag)
                    // 降级到 STARTED（不能用 CREATED——CREATED 会销毁 view，且经
                    // 详情页 back stack 操作后可能恢复不回来 → 白屏，真机已踩坑）。
                    // 收集器门槛为 RESUMED：STARTED 的隐藏 Tab 收集器照样停跑
                    setMaxLifecycle(tabFrag, androidx.lifecycle.Lifecycle.State.STARTED)
                }
            }

            if (existing != null && !needsRecreate) {
                show(existing)
                setMaxLifecycle(existing, androidx.lifecycle.Lifecycle.State.RESUMED)
            } else {
                if (needsRecreate) {
                    // 移除残骸再重新添加
                    remove(existing!!)
                }
                add(R.id.fragment_container, fragment, tag)
            }
        }.commit()
    }
}
