package com.classguard.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 答题接口抽象（AI 可选扩展口子，v2.2 仅架构）。
 *
 * 应用内当前没有任何自动调用点；仅设置页"测试连接"按钮会手动触发一次，
 * 便于用户确认自配接口可用。未来接入（如触发提问后生成参考答案）必须：
 * 用户显式启用 AiConfig.enabled 且完成接口配置。
 */
interface AnswerProvider {
    suspend fun answer(question: String, context: String? = null, maxTokens: Int? = null): Result<String>
}

/** OpenAI 兼容 /chat/completions 实现（零第三方依赖，HttpURLConnection）。 */
class OpenAiCompatibleProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val timeoutMillis: Int = 15_000,
    /** 输出上限；校对场景按原文长度估算传入，防幻觉也省 token。 */
    private val maxTokens: Int? = null,
) : AnswerProvider {

    override suspend fun answer(question: String, context: String?, maxTokens: Int?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = timeoutMillis
                    readTimeout = timeoutMillis
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                val body = buildRequestJson(question, context, model, maxTokens ?: this@OpenAiCompatibleProvider.maxTokens).toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                conn.disconnect()
                require(code in 200..299) { "HTTP $code: ${text.take(200)}" }
                parseChoice(text) ?: error("响应中没有回答内容")
            }
        }

    companion object {
        /** 请求体。maxTokens 非空时限制输出长度（省 token + 防幻觉）。 */
        fun buildRequestJson(
            question: String,
            context: String?,
            model: String,
            maxTokens: Int? = null,
        ): JSONObject =
            JSONObject().apply {
                put("model", model)
                put("temperature", 0)
                val messages = org.json.JSONArray()
                if (!context.isNullOrBlank()) {
                    messages.put(JSONObject().put("role", "system").put("content", context))
                }
                messages.put(JSONObject().put("role", "user").put("content", question))
                put("messages", messages)
                maxTokens?.let { put("max_tokens", it) }
            }

        /** 解析 /chat/completions 响应中的回答文本（纯函数，单测覆盖）。 */
        fun parseChoice(responseBody: String): String? = runCatching {
            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices") ?: return null
            val first = choices.optJSONObject(0) ?: return null
            val message = first.optJSONObject("message") ?: return null
            message.optString("content").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
