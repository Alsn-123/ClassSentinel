package com.classguard.app.ai

/**
 * AI 修正识别文本（v2.6 可选功能）。
 *
 * 语音识别的原始输出在真实课堂下会有同音错字、英文碎片、人名写错等问题；
 * 本类把这些文本连同**花名册候选**一起交给用户自配的 OpenAI 兼容接口，
 * 请模型只做"文字校对"——纠错、去噪，不改意思、不添加内容。
 *
 * 安全边界：
 * - 仅当用户显式启用（AiConfig.enabled + AI 修正开关）才发起网络请求；
 * - 发送内容 = 识别文本 + 名单姓名（用于人名纠错），设置页已明示；
 * - [sanitize] 保证模型输出不可信时安全回退（调用方用原文兜底）。
 */
class AiRefiner(
    private val provider: AnswerProvider,
    private val rosterNames: List<String>,
) {

    /** 修正一句识别文本。失败/超时/输出不可信返回 null，调用方以原文入库。 */
    suspend fun refine(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching {
            val answer = provider.answer(buildPrompt(text, rosterNames)).getOrNull() ?: return null
            sanitize(answer, text)
        }.getOrNull()
    }

    companion object {

        /** 校对指令（system message）。只纠错，不续写、不回答、不解释。 */
        fun buildPrompt(raw: String, rosterNames: List<String>): String {
            val names = rosterNames.joinToString("、")
            return buildString {
                appendLine("你是课堂语音识别的文字校对器。输入是语音识别的原始输出，可能包含：")
                appendLine("1) 同音/近音错字 2) 粘在汉字上的英文碎片噪声 3) 字词重复卡顿 4) 被写错的人名。")
                appendLine("任务：输出修正后的中文文本。规则：")
                appendLine("- 只纠正明显的错字与噪声，保持原意，不增删句意，不做任何解释或回答；")
                appendLine("- 修复后不得再含无意义的英文碎片；")
                if (rosterNames.isNotEmpty()) {
                    appendLine("- 文中的人名若与名单中的名字同音/近音，修正为名单里的写法。候选名单：$names")
                }
                appendLine("- 若原文无法理解，原样输出。")
                appendLine("只输出修正后的文本本身。")
                append("原始识别：$raw")
            }
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
    }
}
