package com.classguard.app.recognition

import org.json.JSONArray
import org.json.JSONObject

/**
 * 名单条目：名单匹配（v2.0）的数据模型。
 *
 * @param displayName 展示名（真实姓名，提醒时显示）
 * @param aliases 别名/昵称，命中等同本人
 * @param asrVariants ASR 容错变体（同音字、常见误识字），命中也视为该名字
 * @param isMe 标记"我的名字"：命中后走定向提醒（横幅加大、专属文案与震动）
 */
data class RosterEntry(
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val asrVariants: List<String> = emptyList(),
    val isMe: Boolean = false,
) {
    /** 所有参与匹配的文本（展示名 + 别名 + 变体，去重）。 */
    fun allTexts(): List<String> =
        (listOf(displayName) + aliases + asrVariants).filter { it.isNotBlank() }.distinct()
}

object RosterCodec {

    private const val KEY_NAME = "n"
    private const val KEY_ALIASES = "a"
    private const val KEY_VARIANTS = "v"
    private const val KEY_IS_ME = "m"

    fun encode(entries: List<RosterEntry>): String {
        val arr = JSONArray()
        entries.forEach { e ->
            val o = JSONObject()
                .put(KEY_NAME, e.displayName)
                .put(KEY_IS_ME, e.isMe)
            o.put(KEY_ALIASES, JSONArray(e.aliases))
            o.put(KEY_VARIANTS, JSONArray(e.asrVariants))
            arr.put(o)
        }
        return arr.toString()
    }

    fun decode(raw: String): List<RosterEntry> {
        val arr = JSONArray(raw)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            fun strList(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return List(a.length()) { j -> a.getString(j) }.filter { it.isNotBlank() }
            }
            RosterEntry(
                displayName = o.getString(KEY_NAME),
                aliases = strList(KEY_ALIASES),
                asrVariants = strList(KEY_VARIANTS),
                isMe = o.optBoolean(KEY_IS_ME, false),
            )
        }.filter { it.displayName.isNotBlank() }
    }

    /**
     * 批量文本导入：每行一条；用"/"分隔别名（第一个词为展示名，其余为别名）。
     * 行如 `张三/老张/小三儿` → displayName=张三, aliases=[老张, 小三儿]。
     */
    fun parseImportText(text: String): List<RosterEntry> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val parts = line.split('/', '／').map { p -> p.trim() }.filter { p -> p.isNotEmpty() }
                RosterEntry(
                    displayName = parts.first(),
                    aliases = parts.drop(1),
                )
            }
}
