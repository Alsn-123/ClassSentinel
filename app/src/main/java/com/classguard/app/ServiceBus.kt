package com.classguard.app

import com.classguard.app.recognition.TriggerEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 服务 ↔ 界面的进程内共享状态。 */
object ServiceBus {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** 模型加载中（190MB 模型加载需数秒，界面据此显示状态）。 */
    private val _modelLoading = MutableStateFlow(false)
    val modelLoading: StateFlow<Boolean> = _modelLoading

    /** 启动/运行过程中的致命错误（模型损坏、权限缺失等），界面以横幅展示。 */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** 实时识别的部分文本（界面展示用）。 */
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText

    /** 触发事件流（界面刷新历史/弹提醒预览用）。 */
    private val _alertEvents = MutableSharedFlow<TriggerEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val alertEvents: SharedFlow<TriggerEvent> = _alertEvents

    /** 历史写入计数，界面据此重新加载历史列表。 */
    private val _historyVersion = MutableStateFlow(0)
    val historyVersion: StateFlow<Int> = _historyVersion

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun setModelLoading(value: Boolean) {
        _modelLoading.value = value
    }

    fun setError(message: String?) {
        _error.value = message
    }

    fun setPartial(text: String) {
        _partialText.value = text
    }

    fun emitAlert(event: TriggerEvent) {
        _alertEvents.tryEmit(event)
    }

    fun notifyHistoryChanged() {
        _historyVersion.value += 1
    }
}
