package com.classguard.app.recognition

import org.json.JSONArray
import org.json.JSONObject

/** 关键词类型：核心点名语 / 辅助语境 / 排除词。 */
enum class KeywordType { CORE, CONTEXT, EXCLUDE }

/**
 * 一条关键词。
 * @param text 用户原始输入（展示与事件回传用原词，不展示归一化后的词）
 * @param type 词类型
 */
data class KeywordSpec(val text: String, val type: KeywordType)

object KeywordSpecCodec {

    private const val KEY_TEXT = "t"
    private const val KEY_TYPE = "k"

    fun encode(specs: List<KeywordSpec>): String {
        val arr = JSONArray()
        specs.forEach { spec ->
            arr.put(
                JSONObject()
                    .put(KEY_TEXT, spec.text)
                    .put(KEY_TYPE, spec.type.name)
            )
        }
        return arr.toString()
    }

    fun decode(raw: String): List<KeywordSpec> {
        val arr = JSONArray(raw)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val type = runCatching { KeywordType.valueOf(o.getString(KEY_TYPE)) }
                .getOrDefault(KeywordType.CORE)
            KeywordSpec(text = o.getString(KEY_TEXT), type = type)
        }.filter { it.text.isNotBlank() }
    }

    /** 旧版数据迁移：旧 `keywords` 字符串数组整体升级为 CORE 类型。 */
    fun migrateFromLegacyKeywords(legacyKeywords: List<String>): List<KeywordSpec> =
        legacyKeywords.filter { it.isNotBlank() }.map { KeywordSpec(it, KeywordType.CORE) }
}
