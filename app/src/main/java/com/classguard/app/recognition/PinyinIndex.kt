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

    companion object {
        private const val TAG = "PinyinIndex"
        private const val ASSET = "pinyin/pinyin.txt"

        val EMPTY = PinyinIndex(emptyMap())

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
    fun findMatch(name: String, utterance: String): Int? {
        val n = name.length
        if (n == 0 || utterance.length < n) return null
        val nameReadings = ArrayList<Set<String>>(n)
        for (i in 0 until n) {
            val r = readings(name[i]) ?: return null
            nameReadings.add(r)
        }
        for (off in 0..utterance.length - n) {
            var ok = true
            for (j in 0 until n) {
                val r = readings(utterance[off + j])
                if (r == null || nameReadings[j].intersect(r).isEmpty()) {
                    ok = false
                    break
                }
            }
            if (ok) return off
        }
        return null
    }
}
