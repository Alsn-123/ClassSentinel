package com.classguard.app.data

import android.content.Context
import android.content.SharedPreferences
import com.classguard.app.recognition.KeywordSpec
import com.classguard.app.recognition.KeywordSpecCodec
import com.classguard.app.recognition.KeywordType
import com.classguard.app.recognition.RosterCodec
import com.classguard.app.recognition.RosterEntry
import org.json.JSONArray
import org.json.JSONObject

/** 一条触发历史。 */
data class TriggerRecord(
    val timeMillis: Long,
    val keyword: String,
    val utterance: String,
    val context: String,
    val confidence: Double? = null,
    val directed: Boolean = false,
)

/** 设置、词表、名单与历史的本地持久化（SharedPreferences + JSON，纯本地）。 */
class PrefsStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("class_sentinel_prefs", Context.MODE_PRIVATE)

    // ------------------------------------------------------ 三类关键词

    /**
     * 三类关键词（核心/语境/排除）。
     * 旧版本只有平级 `keywords` 字符串数组——首次读取时自动迁移为全 CORE。
     */
    var keywordSpecs: List<KeywordSpec>
        get() {
            val raw = sp.getString(KEY_KEYWORD_SPECS, null)
            if (raw != null) {
                return runCatching { KeywordSpecCodec.decode(raw) }
                    .getOrNull().takeIf { !it.isNullOrEmpty() } ?: defaultSpecs()
            }
            // 旧格式迁移
            val legacy = sp.getString(KEY_KEYWORDS_LEGACY, null)
            return if (legacy != null) {
                runCatching {
                    val arr = JSONArray(legacy)
                    val words = List(arr.length()) { arr.getString(it) }.filter { it.isNotBlank() }
                    KeywordSpecCodec.migrateFromLegacyKeywords(words)
                }.getOrNull().takeIf { !it.isNullOrEmpty() } ?: defaultSpecs()
            } else {
                defaultSpecs()
            }
        }
        set(value) {
            sp.edit()
                .putString(KEY_KEYWORD_SPECS, KeywordSpecCodec.encode(value))
                .remove(KEY_KEYWORDS_LEGACY) // 迁移完成后清除旧格式
                .apply()
        }

    // ------------------------------------------------------ 名单

    var roster: List<RosterEntry>
        get() {
            val raw = sp.getString(KEY_ROSTER, null) ?: return emptyList()
            return runCatching { RosterCodec.decode(raw) }.getOrDefault(emptyList())
        }
        set(value) = sp.edit().putString(KEY_ROSTER, RosterCodec.encode(value)).apply()

    // ------------------------------------------------------ 其他设置

    var cooldownMillis: Long
        get() = sp.getLong(KEY_COOLDOWN, 15_000L).coerceIn(3_000L, 120_000L)
        set(value) = sp.edit().putLong(KEY_COOLDOWN, value).apply()

    var soundEnabled: Boolean
        get() = sp.getBoolean(KEY_SOUND, true)
        set(value) = sp.edit().putBoolean(KEY_SOUND, value).apply()

    var vibrationEnabled: Boolean
        get() = sp.getBoolean(KEY_VIBRATION, true)
        set(value) = sp.edit().putBoolean(KEY_VIBRATION, value).apply()

    /** 用户是否期望监听处于运行状态（用于系统杀死后的自动恢复判定）。 */
    var monitoringEnabled: Boolean
        get() = sp.getBoolean(KEY_MONITORING_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_MONITORING_ENABLED, value).apply()

    /** 模型完整性校验结果缓存（true=通过），避免每次启动重复校验 190MB 文件。 */
    var modelIntegrityOk: Boolean
        get() = sp.getBoolean(KEY_MODEL_INTEGRITY, false)
        set(value) = sp.edit().putBoolean(KEY_MODEL_INTEGRITY, value).apply()

    /** 课堂转写开关（v2.2，默认关闭；数据仅存本机 Room）。 */
    var transcriptEnabled: Boolean
        get() = sp.getBoolean(KEY_TRANSCRIPT, false)
        set(value) = sp.edit().putBoolean(KEY_TRANSCRIPT, value).apply()

    // ------------------------------------------------------ 历史

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
                    confidence = if (o.has("cf")) o.getDouble("cf") else null,
                    directed = o.optBoolean("d", false),
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
            val o = JSONObject()
                .put("t", it.timeMillis)
                .put("kw", it.keyword)
                .put("u", it.utterance)
                .put("c", it.context)
                .put("d", it.directed)
            it.confidence?.let { cf -> o.put("cf", cf) }
            arr.put(o)
        }
        sp.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearHistory() = sp.edit().remove(KEY_HISTORY).apply()

    companion object {
        private const val KEY_KEYWORD_SPECS = "keyword_specs"
        private const val KEY_KEYWORDS_LEGACY = "keywords"
        private const val KEY_ROSTER = "roster"
        private const val KEY_COOLDOWN = "cooldown_millis"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_VIBRATION = "vibration_enabled"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_MODEL_INTEGRITY = "model_integrity_ok"
        private const val KEY_TRANSCRIPT = "transcript_enabled"
        private const val KEY_HISTORY = "history"
        private const val MAX_HISTORY = 50

        /** 默认核心词：短语级点名语。 */
        val DEFAULT_CORE = listOf(
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

        /**
         * 默认语境词：**默认为空**（v2.3 起）。
         *
         * 语境门控原是"从严"设计：核心词需与语境词同现才触发。实测发现它会把最核心的
         * 点名语漏掉——教师只说"回答一下"、前后文没有"这道题"这类词时整句被拦。
         * 因此默认不再启用；需要压误报的用户可在界面自行添加语境词，
         * 且门控只作用于 2~3 字的短核心词（见 TriggerMatcher.CONTEXT_GATED_MAX_LEN）。
         */
        val DEFAULT_CONTEXT = emptyList<String>()

        fun defaultSpecs(): List<KeywordSpec> =
            DEFAULT_CORE.map { KeywordSpec(it, KeywordType.CORE) } +
                DEFAULT_CONTEXT.map { KeywordSpec(it, KeywordType.CONTEXT) }
    }
}
