package com.classguard.app.ai

import com.classguard.app.recognition.PinyinIndex

/**
 * AI 修正识别文本（v2.6 可选功能）。
 *
 * 语音识别的原始输出在真实课堂下会有同音错字、英文碎片、人名写错等问题；
 * 本类把这些文本连同**花名册候选**一起交给用户自配的 OpenAI 兼容接口，
 * 请模型只做"文字校对"——纠错、去噪，不改意思、不添加内容。
 *
 * token 控制（省钱的三个手段）：
 * 1. [shouldRefine] 启发式跳过"大概率没问题"的句子——只有含英文残留、
 *    或句中存在与名单读音相近但字面不同的片段（本地纠偏没敢写回的）才发请求，
 *    干净句子零 token；
 * 2. 校对指令压缩成两三行（system），原文单独作 user 消息；
 * 3. max_tokens 按原文长度估算、temperature=0，防幻觉发散。
 *
 * 安全边界：
 * - 仅当用户显式启用（AiConfig.enabled + AI 修正开关）才发起网络请求；
 * - 发送内容 = 识别文本 + 名单姓名（用于人名纠错），设置页已明示；
 * - [sanitize] 保证模型输出不可信时安全回退（调用方用原文兜底）。
 */
class AiRefiner(
    private val provider: AnswerProvider,
    private val rosterNames: List<String>,
    /** 拼音索引：判断"句中是否有与名单读音相近但字面不同"的片段；null 时只看英文残留。 */
    private val pinyin: PinyinIndex? = null,
) {

    /**
     * 启发式：这句值不值得花 token 发给 AI。
     * 发 true 的两类信号：英文残留（本地只过滤粘连噪声，独立英文词交给 AI 判断）；
     * 名单读音相近但字面不同（本地纠偏采信规则没敢写回的边缘情况）。
     */
    fun shouldRefine(text: String): Boolean {
        if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) return true
        val idx = pinyin ?: return false
        if (rosterNames.isEmpty()) return false
        return rosterNames.any { name ->
            name.length >= 2 &&
                name.all { idx.readings(it) != null } &&
                idx.findAlignedMatch(name, text)?.let { it.substitutions > 0 } == true
        }
    }

    /** 修正一句识别文本。判定无需修正/失败/超时/输出不可信返回 null，调用方以原文入库。 */
    suspend fun refine(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (!shouldRefine(text)) return null
        return runCatching {
            val answer = provider
                .answer(text, buildSystemPrompt(rosterNames), maxTokensFor(text))
                .getOrNull() ?: return null
            sanitize(answer, text)
        }.getOrNull()
    }

    companion object {

        /**
         * 校对指令（system message）。压缩到最小 token 量：规则两行，名单一行。
         * 原文走 user 消息，不拼进指令。
         */
        fun buildSystemPrompt(rosterNames: List<String>): String = buildString {
            append("课堂语音识别校对：纠正同音错字、删除英文碎片与重复字词；")
            if (rosterNames.isNotEmpty()) {
                append("人名按名单写法修正（名单：${rosterNames.joinToString("、")}）；")
            }
            append("保持原意。只输出修正后的文本，不解释。")
        }

        /**
         * 输出防御：模型可能输出空串、复述指令、超长幻觉。
         * 返回 null 表示不可信，调用方应使用原始文本。
         */
        fun sanitize(output: String, raw: String): String? {
            val cleaned = output.trim()
                .removePrefix("\"").removeSuffix("\"")
                .trim()
            if (cleaned.isEmpty()) return null
            // 幻觉防护：正常校对不会比原文长出几倍
            if (cleaned.length > raw.length * 3 + 20) return null
            return cleaned
        }

        /** 输出 token 上限估算：校对只会更短，留 1 倍余量 + 常数。 */
        fun maxTokensFor(raw: String): Int = raw.length * 2 + 40
    }
}
