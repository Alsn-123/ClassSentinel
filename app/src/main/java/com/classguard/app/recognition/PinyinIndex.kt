package com.classguard.app.recognition

import android.content.Context
import android.content.res.AssetManager
import android.util.Log

/**
 * 紧凑拼音索引（名单模糊匹配 v2.1 用）。
 *
 * 解决的问题：ASR 把"张三"识别成"章三/彰散"等同音/近音字时，按字精确匹配永远无法命中
 * ——用户不可能预知 ASR 会错成哪个字。本索引把名字与识别文本都归一到读音集合上，
 * 滑动窗口对齐比较，**同音字自动命中**，用户只需录一次真名。
 *
 * 数据：assets/pinyin/pinyin.txt（派生自 mozillazg/pinyin-data，20,924 字，约 185KB）。
 * 已在构建期完成：去声调、常见声韵母混淆归一（zh→z / ch→c / sh→s、ang→an / eng→en /
 * ing→in / ong→on、ü→v），运行时零转换成本，也不联网。
 */
class PinyinIndex private constructor(private val map: Map<String, Set<String>>) {

    /**
     * 容错对齐命中（v2.2 语义校验）：
     * @param offset 名字首字在 utterance 中的起始偏移
     * @param end 名字末字对齐到 utterance 中的位置（闭区间，含插入字在内的整段 span）
     * @param insertions 音节之间被 ASR 插入的无关字数（"一字被识别成两三字"的展开现象）
     * @param substitutions 同音/近音错字数（读音对但字不对，语义校验通过、按错字数扣置信度）
     */
    data class FuzzyHit(val offset: Int, val end: Int, val insertions: Int, val substitutions: Int)

    companion object {
        private const val TAG = "PinyinIndex"
        private const val ASSET = "pinyin/pinyin.txt"

        val EMPTY = PinyinIndex(emptyMap())

        /**
         * 插入容差预算：名字 2 字允许插 1 个无关字，≥3 字允许插 2 个。
         * 再宽会把"读音恰好相继出现"的正常语句误判成名字（误报）。
         */
        fun maxInsertionsFor(nameLength: Int): Int = minOf(2, nameLength - 1)

        fun fromAssets(assets: AssetManager): PinyinIndex = try {
            val map = HashMap<String, Set<String>>(24_000)
            assets.open(ASSET).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parsed = parseLine(line) ?: return@forEach
                    map[parsed.first] = parsed.second
                }
            }
            PinyinIndex(map)
        } catch (t: Throwable) {
            Log.e(TAG, "拼音表加载失败，名单模糊匹配降级为精确匹配")
            EMPTY
        }

        /** 进程内缓存，避免重复解析；加载失败缓存 EMPTY（精确匹配兜底）。 */
        fun holder(context: Context): PinyinIndex {
            if (cached != null) return cached!!
            return synchronized(this) {
                cached ?: fromAssets(context.assets).also { cached = it }
            }
        }

        @Volatile
        private var cached: PinyinIndex? = null

        /** 供单元测试注入固定数据。 */
        fun fromMap(map: Map<String, Set<String>>): PinyinIndex = PinyinIndex(map)

        /** 行格式：字<TAB>音节1 音节2 …（构建期已归一）。 */
        fun parseLine(line: String): Pair<String, Set<String>>? {
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab + 1 >= line.length) return null
            val ch = line.substring(0, tab)
            if (ch.length != 1) return null
            val readings = line.substring(tab + 1)
                .split(' ')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            return if (readings.isEmpty()) null else ch to readings
        }
    }

    fun size(): Int = map.size

    fun readings(ch: Char): Set<String>? = map[ch.toString()]

    /**
     * 滑动窗口对齐：name 每个字的读音集合与 utterance 对应位置字的读音集合全部相交
     * 即命中（多音字任一读音相配即可）。
     * @return 命中在 utterance 中的起始偏移；不命中或名字含无法查到的字时返回 null
     */
    fun findMatch(name: String, utterance: String): Int? =
        findAlignedMatch(name, utterance)?.takeIf { it.insertions == 0 }?.offset

    /**
     * 容错对齐（v2.2）：名字的每个字按顺序在 utterance 中找到读音相交的字即算对齐，
     * 相邻两个对齐字之间允许夹带少量无关字（ASR 把单字展开成多字词的现象）。
     * 首尾之外的文本不计成本；在插入数不超过预算（[maxInsertionsFor]）的前提下，
     * 返回"插入数最少、其次替换数最少"的对齐路径。
     * 复杂度 O(len(utterance) × len(name))，匹配层每个 partial 调一次，开销可忽略。
     */
    fun findAlignedMatch(name: String, utterance: String): FuzzyHit? {
        val m = name.length
        if (m == 0 || utterance.length < m) return null
        val budget = maxInsertionsFor(m)
        val nameReads = arrayOfNulls<Set<String>>(m)
        for (j in 0 until m) {
            nameReads[j] = readings(name[j]) ?: return null
        }
        val n = utterance.length
        val INF = Int.MAX_VALUE / 4
        // dp[j][i]：名字前 j+1 个字全部对齐、第 j+1 个字对齐到 utterance[i] 的最小插入数
        val dp = Array(m) { IntArray(n) { INF } }
        for (i in 0 until n) {
            if (readsAt(nameReads[0]!!, utterance[i])) dp[0][i] = 0
        }
        for (j in 1 until m) {
            val prev = dp[j - 1]
            val cur = dp[j]
            // prefix 最小值：min over i' < i of (prev[i'] - i' - 1)，先用于当前 i 再纳入 prev[i]
            var bestPrefix = INF
            for (i in 0 until n) {
                if (bestPrefix < INF && readsAt(nameReads[j]!!, utterance[i])) {
                    val cand = bestPrefix + i
                    if (cand < cur[i]) cur[i] = cand
                }
                val v = prev[i]
                if (v < INF) {
                    val c = v - i - 1
                    if (c < bestPrefix) bestPrefix = c
                }
            }
        }
        // 终点自由（名字后面的文本不计）：取全文最小插入数
        var bestEnd = -1
        var bestIns = INF
        for (i in 0 until n) {
            if (dp[m - 1][i] < bestIns) {
                bestIns = dp[m - 1][i]
                bestEnd = i
            }
        }
        if (bestEnd < 0 || bestIns > budget) return null
        // 回溯：每步取"能构成该插入数的、索引尽量小"的前驱，使替换数最小（读音相同的优先）
        val positions = IntArray(m)
        positions[m - 1] = bestEnd
        var end = bestEnd
        for (j in m - 2 downTo 0) {
            val target = dp[j + 1][end]
            var found = -1
            for (i in 0 until end) {
                if (dp[j][i] + (end - i - 1) == target) {
                    found = i
                    break
                }
            }
            positions[j] = found
            end = found
        }
        var substitutions = 0
        for (j in 0 until m) {
            if (utterance[positions[j]] != name[j]) substitutions++
        }
        return FuzzyHit(positions[0], bestEnd, bestIns, substitutions)
    }

    private fun readsAt(nameReads: Set<String>, ch: Char): Boolean {
        val r = map[ch.toString()] ?: return false
        return nameReads.any { it in r }
    }
}
