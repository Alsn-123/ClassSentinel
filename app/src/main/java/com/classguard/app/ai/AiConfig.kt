package com.classguard.app.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * AI 可选扩展配置（v2.2 仅架构口子，默认关闭）。
 *
 * 隐私边界：
 * - 识别/触发/提醒核心链路永不联网，也不读取本配置；
 * - 仅当用户在此显式启用并填写接口后，[AnswerProvider] 才会发起网络请求；
 * - apiKey 经 [KeystoreCrypto] 加密后落盘，不存明文。
 */
data class AiConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val model: String = "",
    val apiKeyCipher: String = "",
)

class AiConfigStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("ai_config", Context.MODE_PRIVATE)

    fun load(): AiConfig = AiConfig(
        enabled = sp.getBoolean("enabled", false),
        baseUrl = sp.getString("base_url", "").orEmpty(),
        model = sp.getString("model", "").orEmpty(),
        apiKeyCipher = sp.getString("api_key_cipher", "").orEmpty(),
    )

    fun save(config: AiConfig, plainApiKey: String?) {
        sp.edit()
            .putBoolean("enabled", config.enabled)
            .putString("base_url", config.baseUrl.trim())
            .putString("model", config.model.trim())
            .putString("api_key_cipher", config.apiKeyCipher)
            .apply()
        // 新输入的明文 Key 加密保存；留空则保留旧密文
        if (!plainApiKey.isNullOrBlank()) {
            sp.edit().putString("api_key_cipher", KeystoreCrypto.encrypt(plainApiKey.trim())).apply()
        }
    }

    fun plainApiKey(config: AiConfig): String? =
        if (config.apiKeyCipher.isBlank()) null
        else runCatching { KeystoreCrypto.decrypt(config.apiKeyCipher) }.getOrNull()
}
