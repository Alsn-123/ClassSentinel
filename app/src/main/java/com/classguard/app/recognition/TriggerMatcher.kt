package com.classguard.app.recognition

/**
 * 一次触发事件。
 * @param timeMillis 触发时刻
 * @param keyword 命中的关键词
 * @param utterance 触发时老师正在说的这句话（识别文本）
 * @param context 这句话之前识别到的上下文（老师往往先出题后点名，前文常是题目本身）
 */
data class TriggerEvent(
    val timeMillis: Long,
    val keyword: String,
    val utterance: String,
    val context: String,
)

/**
 * 关键词触发匹配器（纯 Kotlin，不依赖 Android）。
 *
 * 语音识别是流式的：partial 结果会随说话不断更新（整句从头重发），
 * 端点检测判定一句话结束后产生 final 文本。因此匹配分两条路径：
 * - [onPartial]：对“当前这句正在说的话”实时检查，保证老师话音未落就能提醒；
 * - [onFinal]：该句结束时再检查一次（防止关键词恰好被部分结果遗漏），然后把它
 *   提交进上下文缓冲区，供下一次触发时作为“前文”展示。
 *
 * 防重复：同一句话只触发一次（[utteranceTriggered]）+ 全局冷却时间。
 */
class TriggerMatcher(
    keywords: List<String>,
    private val cooldownMillis: Long = 15_000,
    private val maxContextChars: Int = 80,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val normalizedKeywords: List<String> =
        keywords.map { normalize(it) }.filter { it.isNotEmpty() }.distinct()

    private val committed = StringBuilder()

    private var lastTriggerAt = 0L
    private var hasTriggered = false
    private var utteranceTriggered = false

    /** 正在说的这句话有新文本了。命中返回事件，否则返回 null。 */
    fun onPartial(partial: String): TriggerEvent? {
        if (utteranceTriggered) return null
        val event = check(normalize(partial), partial) ?: return null
        utteranceTriggered = true
        return event
    }

    /** 这句话说完了。可能补一次匹配（关键词刚好在结尾处），然后把该句存入上下文。 */
    fun onFinal(final: String): TriggerEvent? {
        val event = if (utteranceTriggered) null else check(normalize(final), final)
        utteranceTriggered = false
        if (final.isNotBlank()) {
            committed.append(final.trim()).append(' ')
            trimCommitted()
        }
        return event
    }

    /** 清空上下文（例如重新开始监听时）。 */
    fun reset() {
        committed.setLength(0)
        lastTriggerAt = 0L
        hasTriggered = false
        utteranceTriggered = false
    }

    private fun check(normUtterance: String, rawUtterance: String): TriggerEvent? {
        if (normUtterance.isEmpty() || normalizedKeywords.isEmpty()) return null
        val now = clock()
        if (hasTriggered && now - lastTriggerAt < cooldownMillis) return null
        for (keyword in normalizedKeywords) {
            if (normUtterance.contains(keyword)) {
                lastTriggerAt = now
                hasTriggered = true
                return TriggerEvent(now, keyword, rawUtterance.trim(), committed.toString().trim())
            }
        }
        return null
    }

    private fun trimCommitted() {
        if (committed.length > maxContextChars) {
            committed.delete(0, committed.length - maxContextChars)
        }
    }

    companion object {
        /**
         * 归一化：只保留汉字、字母、数字（字母小写），去掉标点与空白。
         * 这样“回答一下，谁来……”和“回答一下”能正确匹配。
         */
        fun normalize(text: String): String = buildString {
            for (ch in text) {
                when {
                    ch.code in 0x4E00..0x9FFF -> append(ch)
                    ch in 'a'..'z' -> append(ch)
                    ch in 'A'..'Z' -> append(ch.lowercaseChar())
                    ch in '0'..'9' -> append(ch)
                }
            }
        }
    }
}
