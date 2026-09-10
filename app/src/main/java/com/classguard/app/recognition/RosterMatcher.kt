package com.classguard.app.recognition

/**
 * 名单匹配器（v2.0 一等公民）。
 *
 * 名单工具的本质是"听到自己的名字"：短语词表覆盖不了"张三，说说你的看法"这类点名。
 * 匹配规则：
 * 1. 排除优先：本句命中排除词（与关键词词表共用 EXCLUDE）→ 不触发；
 * 2. 语境门控：与短语词表共用语境词（CONTEXT）——语境词非空时，姓名命中 +（本句或前文）语境词
 *    才触发；语境词为空时姓名命中即触发；
 * 3. "我的名字"（isMe）命中 → 定向提醒；他人姓名 + 语境 → 普通提醒；
 * 4. 同句只触发一次；按姓名独立冷却，姓名类冷却为基础值的 2 倍（减少重复轰炸）。
 *
 * 上下文窗口由调用方传入（与 TriggerMatcher 共用同一份前文缓冲）。
 */
class RosterMatcher(
    roster: List<RosterEntry>,
    specs: List<KeywordSpec>,
    baseCooldownMillis: Long = 15_000,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 拼音索引（v2.1）：提供时启用同音/近音模糊命中；null 则仅精确匹配。 */
    private val pinyin: PinyinIndex? = null,
) {
    private class Names(
        val variants: List<Pair<String, RosterEntry>>, // normalized variant -> entry（精确匹配）
        val fuzzy: List<Pair<String, RosterEntry>>,    // ≥2 字纯 CJK 文本（拼音模糊匹配）
        val context: List<String>,
        val exclude: List<String>,
    )

    @Volatile
    private var names = buildNames(roster, specs)

    @Volatile
    private var baseCooldownMillis: Long = baseCooldownMillis

    private val lastTriggerAt = HashMap<String, Long>() // displayName -> time
    private var utteranceTriggered = false

    /** 本句有新识别文本。命中返回事件（directed=true 表示点到"我的名字"）。 */
    @Synchronized
    fun onPartial(utterance: String, contextText: String): TriggerEvent? {
        if (utteranceTriggered) return null
        return check(TriggerMatcher.normalize(utterance), utterance, contextText)
    }

    /** 一句话结束：重置"同句一次"状态（上下文由 TriggerMatcher 统一维护）。 */
    @Synchronized
    fun onFinal() {
        utteranceTriggered = false
    }

    @Synchronized
    fun updateRoster(roster: List<RosterEntry>) {
        names = buildNames(roster, currentSpecs)
    }

    @Synchronized
    fun updateSpecs(specs: List<KeywordSpec>) {
        names = buildNames(currentRoster, specs)
    }

    @Synchronized
    fun updateBaseCooldown(millis: Long) {
        baseCooldownMillis = millis.coerceIn(3_000, 120_000)
    }

    @Synchronized
    fun reset() {
        lastTriggerAt.clear()
        utteranceTriggered = false
    }

    // 保留最近一次构建用的输入，便于单边更新
    private var currentRoster: List<RosterEntry> = roster
    private var currentSpecs: List<KeywordSpec> = specs

    // ------------------------------------------------------------ 内部

    private fun check(norm: String, rawUtterance: String, contextText: String): TriggerEvent? {
        if (norm.isEmpty()) return null
        val n = names

        // 1. 排除优先
        if (n.exclude.any { norm.contains(it) }) return null

        // 2. 语境门控（窗口 = 本句 + 前文）
        if (n.context.isNotEmpty()) {
            val window = norm + TriggerMatcher.normalize(contextText)
            if (n.context.none { window.contains(it) }) return null
        }

        // 3. 姓名命中：精确变体优先，其次拼音模糊（同音/近音）；多命中时优先"我的名字"
        val exact = n.variants.filter { (v, _) -> norm.contains(v) }
        var hit: Pair<String, RosterEntry>? = exact.firstOrNull { it.second.isMe } ?: exact.firstOrNull()
        if (hit == null && pinyin != null) {
            val fuzzy = n.fuzzy.filter { (t, _) -> pinyin.findMatch(t, norm) != null }
            hit = fuzzy.firstOrNull { it.second.isMe } ?: fuzzy.firstOrNull()
        }
        val matched = hit ?: return null
        val entry = matched.second

        val now = clock()
        val cooldown = ROSTER_COOLDOWN_FACTOR * TriggerMatcher.cooldownMillisFor(
            TriggerMatcher.normalize(entry.displayName).length, baseCooldownMillis
        )
        val last = lastTriggerAt[entry.displayName]
        if (last != null && now - last < cooldown) return null
        lastTriggerAt[entry.displayName] = now
        utteranceTriggered = true

        return TriggerEvent(
            timeMillis = now,
            keyword = entry.displayName,
            utterance = rawUtterance.trim(),
            context = contextText.trim(),
            directed = entry.isMe,
        )
    }

    companion object {
        /** 姓名类冷却放大系数：默认为基础冷却的 2 倍。 */
        const val ROSTER_COOLDOWN_FACTOR = 2L

        private fun buildNames(roster: List<RosterEntry>, specs: List<KeywordSpec>): Names {
            val variants = LinkedHashMap<String, RosterEntry>()
            val fuzzy = LinkedHashMap<String, RosterEntry>()
            for (entry in roster) {
                for (text in entry.allTexts()) {
                    val norm = TriggerMatcher.normalize(text)
                    if (norm.isEmpty()) continue
                    variants.putIfAbsent(norm, entry)
                    // 拼音模糊仅用于 ≥2 字纯汉字文本（单字音节常见，误报率高）
                    if (norm.length >= 2 && norm.all { it.code in 0x4E00..0x9FFF }) {
                        fuzzy.putIfAbsent(norm, entry)
                    }
                }
            }
            val context = LinkedHashSet<String>()
            val exclude = LinkedHashSet<String>()
            for (spec in specs) {
                val norm = TriggerMatcher.normalize(spec.text)
                if (norm.isEmpty()) continue
                when (spec.type) {
                    KeywordType.CONTEXT -> context.add(norm)
                    KeywordType.EXCLUDE -> exclude.add(norm)
                    KeywordType.CORE -> Unit
                }
            }
            return Names(
                variants.map { it.key to it.value },
                fuzzy.map { it.key to it.value },
                context.toList(),
                exclude.toList(),
            )
        }
    }
}
