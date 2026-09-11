package com.classguard.app.recognition

import kotlin.math.exp
import kotlin.math.min

/**
 * 识别置信度提取（v2.0 置信度分级提醒）。
 *
 * sherpa-onnx 的 getResult() 返回每个 token 的 ysProbs——注意是对数概率（log-softmax，
 * 负值）。这里在 token 序列中定位关键词命中的片段（中文为字级 token，逐字对齐），
 * 取该片段的平均对数概率后经 exp 回到 0~1 概率域；定位失败时退化为整句平均。
 */
object Confidence {

    /**
     * 置信度达到该值走全量提醒（横幅+震动+声音）；低于则仅通知轻提醒。
     * v2.3 由 0.5 下调至 0.2：整句平均置信度天然偏低（逐 token 对数概率取均值后
     * exp 回概率域），0.5 会把大量正常命中误判为"低置信"而降级成静音通知，
     * 表现为"识别出来了却不报警"。仅明显异常时才降级。
     */
    const val HIGH_THRESHOLD = 0.2

    /**
     * @param tokens 识别结果的 token 文本序列
     * @param logProbs 与 tokens 等长的 token 对数概率
     * @param keywordNorm 归一化后的关键词（纯汉字时按字对齐；含字母数字则退化整句平均）
     * @return 命中片段平均置信度（0~1）；无法计算时返回 null
     */
    fun forKeyword(tokens: List<String>, logProbs: FloatArray, keywordNorm: String): Double? {
        if (tokens.isEmpty() || logProbs.size != tokens.size) return null
        if (keywordNorm.isEmpty()) return overall(tokens, logProbs)

        val chars = keywordNorm.toCharArray()
        var bestLog: Double? = null
        var i = 0
        val last = tokens.size - chars.size
        while (i <= last) {
            var matched = true
            for (j in chars.indices) {
                if (tokens[i + j] != chars[j].toString()) {
                    matched = false
                    break
                }
            }
            if (matched) {
                val avg = averageLog(logProbs, i, chars.size)
                if (avg != null && (bestLog == null || avg > bestLog)) bestLog = avg
                i += chars.size
            } else {
                i++
            }
        }
        return bestLog?.let { exp(it) } ?: overall(tokens, logProbs)
    }

    /** 整句平均置信度（0~1）。 */
    fun overall(tokens: List<String>, logProbs: FloatArray): Double? {
        if (tokens.isEmpty() || logProbs.size != tokens.size) return null
        return averageLog(logProbs, 0, logProbs.size)?.let { exp(it) }
    }

    /**
     * 语义校验的置信度惩罚（v2.2）：拼音容错命中说明 ASR 字面与预期词有出入，
     * 每处纠偏扣 0.1（同音错字与插入展开都算一处），下限 0.05。
     * 惩罚后可能跌破 [HIGH_THRESHOLD] → 自动降级为轻提醒，兼顾召回与防误报。
     */
    fun withFuzzyPenalty(confidence: Double?, edits: Int): Double? {
        if (confidence == null || edits <= 0) return confidence
        return (confidence - 0.1 * edits).coerceAtLeast(0.05)
    }

    /**
     * 是否走全量提醒（横幅 + 震动 + 声音），而非仅静音通知（v2.3）。
     *
     * 逻辑：字面精确命中（无纠偏）一律全量——文本已完全对上，没有静音的理由；
     * 只有"猜测性命中"才看置信度，且阈值放宽到 [HIGH_THRESHOLD]（0.2）。
     * 修复前是"置信度 < 0.5 就静音"，而整句平均置信度天然偏低，
     * 于是大量正常触发被降级成静音通知，表现为"识别出来了却不报警"。
     */
    fun shouldFullAlert(confidence: Double?, fuzzyEdits: Int): Boolean =
        fuzzyEdits == 0 || confidence == null || confidence >= HIGH_THRESHOLD

    private fun averageLog(logProbs: FloatArray, start: Int, length: Int): Double? {
        if (length <= 0) return null
        val end = min(start + length, logProbs.size)
        var sum = 0.0
        for (i in start until end) sum += logProbs[i]
        return sum / (end - start)
    }
}
