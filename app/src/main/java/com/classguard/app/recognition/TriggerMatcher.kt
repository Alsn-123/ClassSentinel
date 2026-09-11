package com.classguard.app.recognition

/**
 * 一次触发事件。
 * @param timeMillis 触发时刻
 * @param keyword 命中的词（用户原始输入，非归一化形式；拼音模糊命中时即为纠偏后的正确词）
 * @param utterance 触发时老师正在说的这句话（识别文本）
 * @param context 这句话之前识别到的上下文（老师往往先出题后点名，前文常是题目本身）
 * @param directed 是否为"点到我的名字"的定向提醒（名单匹配 v2.0）
 * @param confidence 命中片段的识别置信度（0~1，null 表示未知），低置信时走轻提醒
 * @param fuzzyEdits 语义校验纠偏次数（v2.2）：同音错字数 + 音节间被插入的无关字数；
 *   0 表示字面精确命中，>0 表示读音对但字面有出入，置信度会按此扣减
 */
data class TriggerEvent(
    val timeMillis: Long,
    val keyword: String,
    val utterance: String,
    val context: String,
    val directed: Boolean = false,
    val confidence: Double? = null,
    val fuzzyEdits: Int = 0,
)

/**
 * 关键词触发匹配器（纯 Kotlin，不依赖 Android）。
 *
 * 语音识别是流式的：partial 结果随说话不断更新（整句从头重发），
 * 端点检测判定一句话结束后产生 final 文本。因此匹配分两条路径：
 * - [onPartial]：对"当前这句正在说的话"实时检查，保证老师话音未落就能提醒；
 * - [onFinal]：该句结束时再检查一次（防止关键词恰好被部分结果遗漏），然后把它
 *   提交进上下文缓冲区，供语境门控与前文展示使用。
 *
 * 触发规则（按顺序）：
 * 1. **排除优先**：本句命中任一排除词（EXCLUDE）→ 本句不触发（但仍进入上下文）；
 * 2. **语境门控**：语境词（CONTEXT）非空时，需 核心词 + 语境词 同时命中才触发；
 *    语境词匹配窗口 = 本句 + 之前的话（兼容"先出题、后点名"的跨句模式）；
 *    语境词为空时退化为纯核心词匹配（等价旧版行为）；
 * 3. **同句只触发一次** + **按词独立冷却**（不同词互不压制），冷却时长按词长分级。
 *
 * 设置变更走 [updateKeywordSpecs] / [updateBaseCooldown] 原地更新，
 * 保留上下文与冷却计时，避免重建实例导致的上下文丢失与重复提醒。
 *
 * 线程模型：onPartial/onFinal 在识别线程调用，update/reset 在主线程调用，
 * 全部方法以 this 为锁同步。
 */
class TriggerMatcher(
    specs: List<KeywordSpec>,
    baseCooldownMillis: Long = 15_000,
    private val maxContextChars: Int = 80,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 拼音索引（v2.2）：提供时核心词支持同音/近音错字与单字展开容错命中。 */
    private val pinyin: PinyinIndex? = null,
) {
    /** 归一化词 → 用户原词 的映射与三张词表（均为归一化形式）。 */
    private class Words(
        val core: List<Pair<String, String>>, // normalized -> original
        val context: List<String>,
        val exclude: List<String>,
    )

    @Volatile
    private var words = buildWords(specs)

    @Volatile
    private var baseCooldownMillis: Long = baseCooldownMillis

    private val committed = StringBuilder()      // 原文上下文（展示用）
    private val committedNorm = StringBuilder()  // 归一化上下文（语境门控用）
    private val lastTriggerAt = HashMap<String, Long>() // 归一化词 -> 上次触发时刻
    private var utteranceTriggered = false

    /** 正在说的这句话有新文本了。命中返回事件，否则返回 null。 */
    @Synchronized
    fun onPartial(partial: String): TriggerEvent? {
        if (utteranceTriggered) return null
        val norm = normalize(partial)
        if (norm.isEmpty()) return null
        return findTrigger(norm, partial)
    }

    /** 这句话说完了。可能补一次匹配，然后把该句存入上下文（无论是否被排除）。 */
    @Synchronized
    fun onFinal(final: String): TriggerEvent? {
        val norm = normalize(final)
        var event: TriggerEvent? = null
        if (norm.isNotEmpty() && !utteranceTriggered) {
            event = findTrigger(norm, final)
        }
        utteranceTriggered = false
        if (final.isNotBlank()) {
            val trimmed = final.trim()
            committed.append(trimmed).append(' ')
            trimTo(committed)
            committedNorm.append(normalize(trimmed))
            trimTo(committedNorm)
        }
        return event
    }

    /** 当前上下文快照（供名单匹配复用同一份"前文"窗口）。 */
    @Synchronized
    fun contextSnapshot(): String = committed.toString().trim()

    /** 原地更新词表：保留上下文、冷却计时与本句状态。 */
    @Synchronized
    fun updateKeywordSpecs(specs: List<KeywordSpec>) {
        words = buildWords(specs)
    }

    /** 原地更新基础冷却时间。 */
    @Synchronized
    fun updateBaseCooldown(millis: Long) {
        baseCooldownMillis = millis.coerceIn(3_000, 120_000)
    }

    /** 清空上下文与冷却（例如重新开始监听时）。 */
    @Synchronized
    fun reset() {
        committed.setLength(0)
        committedNorm.setLength(0)
        lastTriggerAt.clear()
        utteranceTriggered = false
    }

    // ------------------------------------------------------------ 内部

    private fun findTrigger(normUtterance: String, rawUtterance: String): TriggerEvent? {
        val w = words
        // 1. 排除优先：本句命中排除词则整体屏蔽
        if (w.exclude.any { normUtterance.contains(it) }) return null

        // 2. 语境门控：语境词非空时需要 核心词 +（本句或前文）语境词 同时命中
        if (w.context.isNotEmpty()) {
            val window = normUtterance + committedNorm
            if (w.context.none { window.contains(it) }) return null
        }

        // 3. 核心词 + 按词独立冷却。字面精确优先；未命中时走读音层语义校验
        //    （同音错字"回大一下"≈"回答一下"、单字被展开"找个同同学"≈"找个同学"）
        val now = clock()
        for ((normWord, original) in w.core) {
            val edits = matchEdits(normWord, normUtterance) ?: continue
            val last = lastTriggerAt[normWord]
            val cooldown = cooldownMillisFor(normWord.length, baseCooldownMillis)
            if (last != null && now - last < cooldown) continue
            lastTriggerAt[normWord] = now
            utteranceTriggered = true
            return TriggerEvent(
                timeMillis = now,
                keyword = original,
                utterance = rawUtterance.trim(),
                context = committed.toString().trim(),
                fuzzyEdits = edits,
            )
        }
        return null
    }

    /** 字面精确命中返回 0；读音容错命中返回纠偏次数；都不命中返回 null。 */
    private fun matchEdits(normWord: String, normUtterance: String): Int? {
        if (normUtterance.contains(normWord)) return 0
        val idx = pinyin ?: return null
        if (normWord.length < 2 || normWord.any { it.code !in 0x4E00..0x9FFF }) return null
        val hit = idx.findAlignedMatch(normWord, normUtterance) ?: return null
        return hit.insertions + hit.substitutions
    }

    private fun trimTo(sb: StringBuilder) {
        if (sb.length > maxContextChars) {
            sb.delete(0, sb.length - maxContextChars)
        }
    }

    companion object {
        /** 冷却分级：≥5 字减半（长词误报率低），3–4 字取基准，≤2 字翻倍（短词易误报）。 */
        fun cooldownMillisFor(normWordLength: Int, baseMillis: Long): Long = when {
            normWordLength >= 5 -> baseMillis / 2
            normWordLength <= 2 -> baseMillis * 2
            else -> baseMillis
        }

        /**
         * 归一化：只保留汉字、字母、数字（字母小写），去掉标点与空白；
         * 并折叠连续 ≥2 个相同字符（ASR 解码卡顿，见 [normalizeWithMap]）。
         * 这样"回答一下，谁来……"和"回答一下"能正确匹配，
         * "动能定定定理"也能直接命中关键词"动能定理"。
         */
        fun normalize(text: String): String = normalizeWithMap(text).first

        /**
         * 归一化 + 原文下标映射（语义修复用）。
         * 折叠规则：连续 ≥2 个相同字符折叠为 1 个——正常语流中同字连发几乎只出现在
         * 识别器解码卡顿（"定定定理""来说说说"），两连是最高频形态；
         * 词表侧做同样归一化，两侧一致所以"谢谢"这类叠词的精确匹配不受影响
         * （叠词保留仅在展示层 [TextRepair] 处理）。映射指向连字的首字下标，
         * 语义修复按区间替换时不会残留重复。
         * @return 归一化文本 + 每个归一化字符在原文本中的下标（等长对应）
         */
        fun normalizeWithMap(text: String): Pair<String, IntArray> {
            val sb = StringBuilder(text.length)
            val map = ArrayList<Int>(text.length)
            for (i in text.indices) {
                val kept = when {
                    text[i].code in 0x4E00..0x9FFF -> text[i]
                    text[i] in 'a'..'z' -> text[i]
                    text[i] in 'A'..'Z' -> text[i].lowercaseChar()
                    text[i] in '0'..'9' -> text[i]
                    else -> null
                } ?: continue
                val n = sb.length
                if (n >= 1 && sb[n - 1] == kept) continue
                sb.append(kept)
                map.add(i)
            }
            return sb.toString() to map.toIntArray()
        }

        private fun buildWords(specs: List<KeywordSpec>): Words {
            val core = LinkedHashMap<String, String>() // normalized -> original
            val context = LinkedHashSet<String>()
            val exclude = LinkedHashSet<String>()
            for (spec in specs) {
                val norm = normalize(spec.text)
                if (norm.isEmpty()) continue
                when (spec.type) {
                    KeywordType.CORE -> core.putIfAbsent(norm, spec.text.trim())
                    KeywordType.CONTEXT -> context.add(norm)
                    KeywordType.EXCLUDE -> exclude.add(norm)
                }
            }
            return Words(core.map { it.key to it.value }, context.toList(), exclude.toList())
        }
    }
}
