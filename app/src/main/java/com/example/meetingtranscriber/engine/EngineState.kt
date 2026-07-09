package com.example.meetingtranscriber.engine

/**
 * 引擎生命周期状态。
 */
enum class EngineState {
    /** 未初始化 */
    IDLE,
    /** 加载模型/资源中 */
    LOADING,
    /** 就绪，等待 start */
    READY,
    /** 推理运行中 */
    RUNNING,
    /** 出错，见 [message] */
    ERROR
}

/**
 * 引擎状态快照，通过 StateFlow 暴露给 UI 层。
 */
data class EngineStatus(
    val state: EngineState,
    /** 错误描述 / 加载提示，如"正在下载模型..." */
    val message: String? = null,
    /** 加载/下载进度 0f → 1f */
    val progress: Float = 0f
) {
    val isReady: Boolean get() = state == EngineState.READY
    val isRunning: Boolean get() = state == EngineState.RUNNING
}
