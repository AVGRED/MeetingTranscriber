package com.example.meetingtranscriber.engine.llm

/**
 * llama.cpp JNI 桥接 — 声明所有 native 方法。
 *
 * C++ 侧实现示例（`llama_jni.cpp`）:
 * ```cpp
 * #include <jni.h>
 * #include "llama.h"
 *
 * static llama_model*   g_model   = nullptr;
 * static llama_context* g_ctx     = nullptr;
 *
 * extern "C" JNIEXPORT jboolean JNICALL
 * Java_com_example_meetingtranscriber_engine_llm_LlmNative_loadModel(
 *     JNIEnv* env, jclass, jstring modelPath, jint nCtx, jint nThreads,
 *     jboolean useMmap, jboolean useMlock) {
 *     // 1. llama_backend_init()
 *     // 2. llama_model_params → llama_load_model_from_file()
 *     // 3. llama_context_params → llama_new_context_with_model()
 *     // ...
 * }
 *
 * extern "C" JNIEXPORT jstring JNICALL
 * Java_com_example_meetingtranscriber_engine_llm_LlmNative_generate(
 *     JNIEnv* env, jclass, jstring prompt, jint maxTokens, jfloat temp, ...) {
 *     // 1. Tokenize prompt
 *     // 2. llama_decode() loop
 *     // 3. Detokenize output
 *     // 4. Return as jstring
 * }
 *
 * extern "C" JNIEXPORT void JNICALL
 * Java_com_example_meetingtranscriber_engine_llm_LlmNative_unloadModel(...) {
 *     llama_free(g_ctx); llama_free_model(g_model); llama_backend_free();
 * }
 * ```
 *
 * ### Android NDK 构建配置 (`app/src/main/cpp/CMakeLists.txt`):
 * ```cmake
 * cmake_minimum_required(VERSION 3.22.1)
 * project("llama_jni")
 *
 * add_library(llama_jni SHARED llama_jni.cpp)
 *
 * # llama.cpp 源码（clone 到 cpp/llama.cpp/ 并设置 LLAMA_BUILD=OFF）
 * add_subdirectory(llama.cpp)
 * target_link_libraries(llama_jni llama)
 *
 * find_library(log-lib log)
 * target_link_libraries(llama_jni ${log-lib})
 * ```
 */
object LlmNative {

    /** 加载 JNI 库。原先全工程无此调用，所有 native 方法必抛 UnsatisfiedLinkError，
     *  本地 Qwen 引擎实际是死代码。首次触碰 LlmNative（即首次生成纪要）时加载，
     *  失败由 QwenEngine.initialize 的 UnsatisfiedLinkError 分支兜底降级 */
    init {
        System.loadLibrary("llama_jni")
    }

    /**
     * 加载 GGUF 模型到 llama.cpp。
     *
     * @param modelPath GGUF 文件绝对路径
     * @param nCtx 上下文窗口大小 (tokens)
     * @param nThreads CPU 线程数
     * @param useMmap 使用内存映射加速加载
     * @param useMlock 将模型锁在 RAM 中（防止 swap）
     * @return true 加载成功，false 失败
     */
    @JvmStatic
    external fun loadModel(
        modelPath: String,
        nCtx: Int = 2048,
        nThreads: Int = 4,
        useMmap: Boolean = true,
        useMlock: Boolean = false
    ): Boolean

    /**
     * 生成文本（阻塞调用，请在 IO 协程中调用）。
     *
     * @param prompt 格式化的输入文本（含 Chat Template）
     * @param maxTokens 最多生成的 token 数
     * @param temperature 采样温度 0-2
     * @param topP nucleus 采样阈值
     * @param topK top-K 采样
     * @param repeatPenalty 重复惩罚系数
     * @param stopStrings 停止词列表（匹配任一即停止）
     * @param callback 每生成一个 token 回调一次，参数为已生成 token 数
     * @return 生成的完整文本
     */
    @JvmStatic
    external fun generate(
        prompt: String,
        maxTokens: Int = 1000,
        temperature: Float = 0.3f,
        topP: Float = 0.9f,
        topK: Int = 40,
        repeatPenalty: Float = 1.1f,
        stopStrings: Array<String> = arrayOf("<|im_end|>", "<|endoftext|>"),
        callback: ((tokenCount: Int) -> Unit)? = null
    ): String

    /**
     * 取消正在进行的生成操作（线程安全，可从任意线程调用）。
     * 导致 [generate] 提前返回已生成的文本。
     */
    @JvmStatic
    external fun cancelGenerate()

    /**
     * 卸载模型，释放显存/内存。
     */
    @JvmStatic
    external fun unloadModel()

    /**
     * 检查 llama.cpp 是否支持当前设备（架构 + 指令集）。
     */
    @JvmStatic
    external fun isSupported(): Boolean
}
