package com.example.mt.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.mt.platform.FileAccess
import com.example.mt.platform.PlatformDatabase
import com.example.mt.platform.PlatformKeyValueStore
import com.example.mt.ui.screens.*

/** 页面枚举 */
enum class Screen(val label: String) {
    HOME("首页"),
    API_CONFIG("API 配置"),
    MEETING("会议"),
    HISTORY("历史"),
    SETTINGS("设置"),
}

/** 导航项配置 */
private data class NavItem(
    val screen: Screen,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val navItems = listOf(
    NavItem(Screen.HOME, Icons.Filled.Home, Icons.Outlined.Home),
    NavItem(Screen.API_CONFIG, Icons.Filled.Key, Icons.Outlined.Key),
    NavItem(Screen.MEETING, Icons.Filled.Mic, Icons.Outlined.Mic),
    NavItem(Screen.HISTORY, Icons.Filled.History, Icons.Outlined.History),
    NavItem(Screen.SETTINGS, Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun AppNavigation(
    db: PlatformDatabase,
    kvStore: PlatformKeyValueStore,
    fileAccess: FileAccess,
) {
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var hasActiveMeeting by remember { mutableStateOf(false) }
    var pendingScreen by remember { mutableStateOf<Screen?>(null) }

    // 离开会议确认弹窗
    pendingScreen?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingScreen = null },
            title = { Text("离开会议页面？") },
            text = { Text("当前有正在进行的会议，切换页面将导致录制和转写数据丢失。确定要离开吗？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingScreen = null
                    hasActiveMeeting = false
                    currentScreen = target
                }) { Text("确定离开") }
            },
            dismissButton = {
                TextButton(onClick = { pendingScreen = null }) { Text("取消") }
            },
        )
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // 侧边导航栏
        NavigationRail(
            modifier = Modifier.fillMaxHeight(),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Spacer(Modifier.height(16.dp))
            navItems.forEach { item ->
                NavigationRailItem(
                    icon = {
                        Icon(
                            imageVector = if (currentScreen == item.screen)
                                item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.screen.label,
                        )
                    },
                    label = { Text(item.screen.label) },
                    selected = currentScreen == item.screen,
                    onClick = {
                        if (item.screen != Screen.MEETING && hasActiveMeeting) {
                            pendingScreen = item.screen
                        } else {
                            currentScreen = item.screen
                        }
                    },
                )
            }
        }

        // 页面内容区
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (currentScreen) {
                Screen.HOME -> HomeScreen(
                    db = db,
                    onNavigateToMeeting = { currentScreen = Screen.MEETING },
                )
                Screen.API_CONFIG -> ApiConfigScreen(kvStore = kvStore)
                Screen.MEETING -> MeetingScreen(
                    db = db,
                    kvStore = kvStore,
                    fileAccess = fileAccess,
                    onMeetingActiveChanged = { active -> hasActiveMeeting = active },
                )
                Screen.HISTORY -> HistoryScreen(db = db)
                Screen.SETTINGS -> SettingsScreen(kvStore = kvStore)
            }
        }
    }
}
