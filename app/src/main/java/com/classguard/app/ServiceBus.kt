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
