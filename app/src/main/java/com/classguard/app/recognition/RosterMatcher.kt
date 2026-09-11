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
        val (norm, normToRaw) = TriggerMatcher.normalizeWithMap(utterance)
        return check(norm, utterance, contextText, normToRaw)
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

    /** 命中结果（不含冷却/触发状态），供触发判定与文本修复共用。 */
    private class Located(
        val entry: RosterEntry,
        val spanStart: Int,
        val spanEnd: Int,
        val fuzzyEdits: Int,
    )

    /**
     * 定位名单命中：精确变体优先，其次拼音模糊（同音/近音 + 单字展开容错）；
     * 多命中时优先"我的名字"，其后按纠偏次数最少（最可能是真名字）。
     */
    private fun locate(norm: String): Located? {
        if (norm.isEmpty()) return null
        val n = names
        val exact = n.variants.filter { (v, _) -> norm.contains(v) }
        val exactHit = exact.firstOrNull { it.second.isMe } ?: exact.firstOrNull()
        if (exactHit != null) {
            val start = norm.indexOf(exactHit.first)
            return Located(exactHit.second, start, start + exactHit.first.length - 1, 0)
        }
        if (pinyin == null) return null

        // 先按完整名字做读音容错对齐
        locateFuzzy(norm)?.let { return it }

        // 删除容错（v2.4）：ASR 可能吞掉名字里的一个字（实测「养一」漏了「臻」、
        // 「杨你」只剩两字），长度不足会让整名对齐直接失败。对 ≥3 字名字，
        // 依次尝试"跳过一个字"再做对齐。
        //
        // 两道护栏，避免退化成"见到姓氏就报"：
        // - 整句至少 3 字：否则"杨一""养一"这类两字片段会命中，误报代价过高；
        // - 姓氏必须精确命中，且读音层面的偏离（近似音 + 插入）有上限——
        //   注意"养一"与"杨易"是同音不同字，属于读音完全相符，不该按字面差异计入。
        if (norm.length < 3) return null
        val reduced = n.fuzzy.mapNotNull { (t, e) ->
            if (t.length < 3) null
            else {
                for (k in t.indices) {
                    val short = t.removeRange(k, k + 1)
                    val hit = pinyin.findAlignedMatch(short, norm) ?: continue
                    if (!hit.surnameExact) continue
                    if (hit.insertions > PinyinIndex.maxInsertionsFor(short.length)) continue
                    if (hit.closeSubstitutions > 1) continue
                    return@mapNotNull Triple(e, hit, hit.insertions + hit.substitutions + 1)
                }
                null
            }
        }
        val matched = reduced.firstOrNull { it.first.isMe } ?: reduced.minByOrNull { it.third }
            ?: return null
        return Located(matched.first, matched.second.offset, matched.second.end, matched.third)
    }

    /** 完整名字的读音容错对齐（同音/近似音 + 插入容错）。 */
    private fun locateFuzzy(norm: String): Located? {
        val n = names
        val p = pinyin ?: return null
        val fuzzy = n.fuzzy.mapNotNull { (t, e) ->
            p.findAlignedMatch(t, norm)
                ?.takeIf { accepted(t.length, it) }
                ?.let { Triple(e, it, it.insertions + it.substitutions) }
        }
        val matched = fuzzy.firstOrNull { it.first.isMe } ?: fuzzy.minByOrNull { it.third }
            ?: return null
        return Located(matched.first, matched.second.offset, matched.second.end, matched.third)
    }

    private fun check(
        norm: String,
        rawUtterance: String,
        contextText: String,
        normToRaw: IntArray,
    ): TriggerEvent? {
        if (norm.isEmpty()) return null
        val n = names

        // 1. 排除优先
        if (n.exclude.any { norm.contains(it) }) return null

        // 2. 语境门控（窗口 = 本句 + 前文）
        if (n.context.isNotEmpty()) {
            val window = norm + TriggerMatcher.normalize(contextText)
            if (n.context.none { window.contains(it) }) return null
        }

        // 3. 姓名命中
        val hit = locate(norm) ?: return null

        val now = clock()
        val cooldown = ROSTER_COOLDOWN_FACTOR * TriggerMatcher.cooldownMillisFor(
            TriggerMatcher.normalize(hit.entry.displayName).length, baseCooldownMillis
        )
        val last = lastTriggerAt[hit.entry.displayName]
        if (last != null && now - last < cooldown) return null
        lastTriggerAt[hit.entry.displayName] = now
        utteranceTriggered = true

        return TriggerEvent(
            timeMillis = now,
            keyword = hit.entry.displayName,
            // 语义修复：把命中区间（同音错字/插字/卡顿连字）整段写回为正确姓名，
            // 转写与提醒里看到的就是真名而不是"章三""张张伟"这类识别畸变
            utterance = repairSpan(
                rawUtterance, normToRaw, hit.spanStart, hit.spanEnd, hit.entry.displayName
            ).trim(),
            context = contextText.trim(),
            directed = hit.entry.isMe,
            fuzzyEdits = hit.fuzzyEdits,
        )
    }

    /**
     * 把整句里的姓名畸变写回真名（v2.3，不改触发状态与冷却）。
     *
     * 触发判定跑在 partial 上，而结束时的 final 文本更完整；转写若直接存 final，
     * 用户看到的就还是「阳丽真」这类识别错字。本方法对 final 重新定位并写回，
     * 让课堂记录里显示的是真名。
     */
    @Synchronized
    fun repairNames(utterance: String): String {
        if (utterance.isBlank()) return utterance
        val (norm, normToRaw) = TriggerMatcher.normalizeWithMap(utterance)
        val n = names
        if (norm.isEmpty() || n.exclude.any { norm.contains(it) }) return utterance
        val hit = locate(norm) ?: return utterance
        return repairSpan(utterance, normToRaw, hit.spanStart, hit.spanEnd, hit.entry.displayName)
    }

    /** 用归一化下标映射把 [start]..[end]（闭区间）对应的原文字区间替换为 [replacement]；映射越界时原样返回。 */
    private fun repairSpan(
        raw: String,
        normToRaw: IntArray,
        start: Int,
        end: Int,
        replacement: String,
    ): String {
        if (start < 0 || end < start || end >= normToRaw.size) return raw
        val rs = normToRaw[start]
        val re = normToRaw[end]
        if (rs < 0 || re < rs || re >= raw.length) return raw
        return raw.substring(0, rs) + replacement + raw.substring(re + 1)
    }

    companion object {
        /** 姓名类冷却放大系数：默认为基础冷却的 2 倍。 */
        const val ROSTER_COOLDOWN_FACTOR = 2L

        /**
         * 近似音采信规则（v2.3）。
         *
         * 人名是高频误识重灾区：`阳一真` 常被识别成 `阳丽真`（易 yi / 丽 li 声母不同、
         * 韵母相同）。需要放宽匹配才能召回，但放宽过头会把无关语句当成点名，因此：
         *
         * - 姓氏（首字）读音必须完全相交——不认姓氏错的名字；
         * - 同音替换（章三→张三，读音相同只是字不同）任何长度都接受，这是 v2.1 起的核心能力；
         * - 近似音（只是韵母相同，如 易/丽）只允许 3 字及以上的名字出现 1 处，
         *   2 字名字不接受——单字音节韵母相同的组合太多，误报代价高。
         */
        fun accepted(nameLength: Int, hit: PinyinIndex.FuzzyHit): Boolean {
            if (hit.insertions > PinyinIndex.maxInsertionsFor(nameLength)) return false
            if (!hit.surnameExact) return false
            val closeBudget = if (nameLength >= 3) 1 else 0
            return hit.closeSubstitutions <= closeBudget
        }

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
