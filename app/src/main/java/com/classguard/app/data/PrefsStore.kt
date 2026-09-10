package com.classguard.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 一条触发历史。 */
data class TriggerRecord(
    val timeMillis: Long,
    val keyword: String,
    val utterance: String,
    val context: String,
)

/** 设置与历史的本地持久化（SharedPreferences + JSON，纯本地）。 */
class PrefsStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("class_sentinel_prefs", Context.MODE_PRIVATE)

    var keywords: List<String>
        get() {
            val raw = sp.getString(KEY_KEYWORDS, null) ?: return DEFAULT_KEYWORDS
            return runCatching {
                val arr = JSONArray(raw)
                List(arr.length()) { arr.getString(it) }.filter { it.isNotBlank() }
            }.getOrNull().takeIf { !it.isNullOrEmpty() } ?: DEFAULT_KEYWORDS
        }
        set(value) = sp.edit().putString(KEY_KEYWORDS, JSONArray(value).toString()).apply()

    var cooldownMillis: Long
        get() = sp.getLong(KEY_COOLDOWN, 15_000L).coerceIn(3_000L, 120_000L)
        set(value) = sp.edit().putLong(KEY_COOLDOWN, value).apply()

    var soundEnabled: Boolean
        get() = sp.getBoolean(KEY_SOUND, true)
        set(value) = sp.edit().putBoolean(KEY_SOUND, value).apply()

    var vibrationEnabled: Boolean
        get() = sp.getBoolean(KEY_VIBRATION, true)
        set(value) = sp.edit().putBoolean(KEY_VIBRATION, value).apply()

    fun history(): List<TriggerRecord> {
        val raw = sp.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                TriggerRecord(
                    timeMillis = o.getLong("t"),
                    keyword = o.getString("kw"),
                    utterance = o.getString("u"),
                    context = o.optString("c"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addHistory(record: TriggerRecord) {
        val list = ArrayList(history())
        list.add(0, record)
        while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("t", it.timeMillis)
                    .put("kw", it.keyword)
                    .put("u", it.utterance)
                    .put("c", it.context)
            )
        }
        sp.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearHistory() = sp.edit().remove(KEY_HISTORY).apply()

    companion object {
        private const val KEY_KEYWORDS = "keywords"
        private const val KEY_COOLDOWN = "cooldown_millis"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_VIBRATION = "vibration_enabled"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 50

        /** 默认关键词：短语级，降低误触发；用户可在界面增删改。 */
        val DEFAULT_KEYWORDS = listOf(
            "叫人答题",
            "叫人回答",
            "叫个同学",
            "叫一位同学",
            "找个同学",
            "找位同学",
            "找一位同学",
            "找同学",
            "点个同学",
            "点一位同学",
            "点名回答",
            "哪位同学",
            "这位同学",
            "请这位同学",
            "请一位同学",
            "请同学回答",
            "请你回答",
            "你来回答",
            "你来答一下",
            "你来说说",
            "请你来说",
            "起来回答",
            "起来答一下",
            "回答一下",
            "谁来回答",
            "谁能回答",
            "谁来答一下",
            "谁来说说",
            "谁来说一下",
            "谁来试试",
            "谁能说说",
            "举手回答",
        )
    }
}
