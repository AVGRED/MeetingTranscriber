package com.example.mt

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.mt.config.DesktopLogger
import com.example.mt.config.initLogger
import com.example.mt.platform.FileAccess
import com.example.mt.platform.PlatformDatabase
import com.example.mt.platform.PlatformKeyValueStore
import com.example.mt.ui.navigation.AppNavigation
import com.example.mt.ui.theme.AppTheme

fun main() = application {
    // 初始化日志系统
    initLogger(DesktopLogger())

    // 初始化平台服务（应用级单例，DB 在 remember 中同步打开确保就绪）
    val db = remember { PlatformDatabase().also { it.open() } }
    val kvStore = remember { PlatformKeyValueStore("meeting_transcriber") }
    val fileAccess = remember { FileAccess() }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Meeting Transcriber",
        state = rememberWindowState(size = DpSize(960.dp, 720.dp)),
    ) {
        AppTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                AppNavigation(
                    db = db,
                    kvStore = kvStore,
                    fileAccess = fileAccess,
                )
            }
        }
    }
}
