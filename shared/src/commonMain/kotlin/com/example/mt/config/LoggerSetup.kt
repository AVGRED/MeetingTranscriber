package com.example.mt.config

import io.github.aakira.napier.Napier

/**
 * 日志初始化（跨平台）。
 *
 * 每个平台的 Application / Main 入口必须调用 [initLogger()]，
 * 否则所有 Napier 调用静默无输出。
 */
fun initLogger(base: io.github.aakira.napier.Antilog) {
    Napier.base(base)
}
