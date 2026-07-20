package com.example.meetingtranscriber.util

import android.text.Editable
import android.text.TextWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 带防抖的 TextWatcher — 在指定 [delayMs] 无输入后才回调 [onQueryChanged]。
 *
 * 用法：
 * ```
 * editText.addTextChangedListener(
 *     DebounceTextWatcher(lifecycleScope, delayMs = 300) { query ->
 *         viewModel.search(query)
 *     }
 * )
 * ```
 */
class DebounceTextWatcher(
    private val scope: CoroutineScope,
    private val delayMs: Long = 300L,
    private val onQueryChanged: (String) -> Unit
) : TextWatcher {

    private var job: Job? = null

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

    override fun afterTextChanged(s: Editable?) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            onQueryChanged(s?.toString() ?: "")
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
