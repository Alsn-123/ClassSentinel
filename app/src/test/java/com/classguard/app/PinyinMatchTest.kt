package com.classguard.app

import com.classguard.app.recognition.KeywordSpec
import com.classguard.app.recognition.KeywordType
import com.classguard.app.recognition.PinyinIndex
import com.classguard.app.recognition.RosterEntry
import com.classguard.app.recognition.RosterMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼音索引与名单模糊匹配（v2.1）：
 * 解决 ASR 把名字错认成同音/近音字导致无法触发的问题。
 */
class PinyinMatchTest {

    // 用真实归一形式构造假索引（构建期已完成去声调与混淆归一）：
    // zan=张/章, san=三/散, shen|sen=沈/审, wang=王/汪, li=李/里, xiang=向/项/相
    private val index = PinyinIndex.fromMap(
        mapOf(
            "张" to setOf("zan"),
            "章" to setOf("zan"),
            "三" to setOf("san"),
            "散" to setOf("san"),
            "沈" to setOf("sen", "tan"),
            "审" to setOf("sen"),
            "叹" to setOf("tan"),
            "王" to setOf("wang"),
            "汪" to setOf("wang"),
            "李" to setOf("li"),
            "里" to setOf("li"),
            "向" to setOf("xiang"),
            "今天" to setOf("jin", "tian"),
        )
    )

    private val specs = listOf(
        KeywordSpec("这道题", KeywordType.CONTEXT),
        KeywordSpec("下课", KeywordType.EXCLUDE),
    )

    @Test
    fun `parseLine 解析字与音节`() {
        val parsed = PinyinIndex.parseLine("张\tzan zhan")!!
        assertEquals("张", parsed.first)
        assertEquals(setOf("zan", "zhan"), parsed.second)
        assertNull(PinyinIndex.parseLine(""))
        assertNull(PinyinIndex.parseLine("没有tab"))
    }

    @Test
    fun `滑动窗口同音命中`() {
        assertEquals(3, index.findMatch("张三", "前面是章三后面还有内容"))
        assertEquals(0, index.findMatch("张三", "章三你来回答"))
    }

    @Test
    fun `混淆归一命中（sh 与 s 同归一）`() {
        // 沈读 sen（构建期已把 shen 归一为 sen），"审"也是 sen
        assertNotNull(index.findMatch("沈", "审三"))
    }

    @Test
    fun `多音字任一读音相配即命中`() {
        // 沈 多音 sen/tan； utterance 字"贪"假设读 tan —— 用"叹"代替
        assertNotNull(index.findMatch("沈", "叹三"))
    }

    @Test
    fun `不同音不命中`() {
        assertNull(index.findMatch("王", "李三"))
    }

    @Test
    fun `名字含未知字则不参与模糊`() {
        assertNull(index.findMatch("孞三", "章三"))
    }

    @Test
    fun `名单模糊命中同音错字`() {
        val roster = listOf(RosterEntry("张三", isMe = true))
        val m = RosterMatcher(roster, specs, clock = { 1000L }, pinyin = index)
        // ASR 识别成"章三"（同音错字），精确匹配不可能命中，模糊匹配应命中并显示真名
        val e = m.onPartial("这道题请章三来说说", "")
        assertNotNull(e)
        assertEquals("张三", e!!.keyword)
        assertTrue(e.directed)
    }

    @Test
    fun `近音归一命中（shan 与 san）`() {
        val roster = listOf(RosterEntry("张三"))
        val m = RosterMatcher(roster, specs, clock = { 1000L }, pinyin = index)
        // "散三"：散(san) 与 张(zan) 不同——不命中；换正确组合验证
        assertNull(m.onPartial("这道题请散三来说说", ""))
        // 章三命中
        assertNotNull(m.onPartial("这道题请章三来说说", ""))
    }

    @Test
    fun `单字名单不参与模糊防误报`() {
        val roster = listOf(RosterEntry("王"))
        val m = RosterMatcher(roster, specs, clock = { 1000L }, pinyin = index)
        // "汪"与"王"同音，但单字名单只做精确匹配
        assertNull(m.onPartial("这道题请汪来说说", ""))
        assertNotNull(m.onPartial("这道题请王来说说", ""))
    }

    @Test
    fun `模糊命中仍受排除词与语境门控约束`() {
        val roster = listOf(RosterEntry("张三"))
        val gated = RosterMatcher(
            roster,
            listOf(KeywordSpec("这道题", KeywordType.CONTEXT)),
            clock = { 1000L },
            pinyin = index,
        )
        assertNull(gated.onPartial("章三来说说", "")) // 无语境
        val excluded = RosterMatcher(roster, specs, clock = { 1000L }, pinyin = index)
        assertNull(excluded.onPartial("章三下课来说说", ""))
    }

    @Test
    fun `无拼音索引时退化为精确匹配`() {
        val roster = listOf(RosterEntry("张三"))
        val m = RosterMatcher(roster, specs, clock = { 1000L }, pinyin = null)
        assertNull(m.onPartial("这道题请章三来说说", ""))
        assertNotNull(m.onPartial("这道题请张三来说说", ""))
    }

    @Test
    fun `模糊命中与精确命中互不重复触发`() {
        val roster = listOf(RosterEntry("张三"))
        val m = RosterMatcher(roster, specs, clock = { 1000L }, pinyin = index)
        assertNotNull(m.onPartial("这道题请章三来说说", ""))
        m.onFinal()
        // 冷却期内（姓名 2 倍冷却），即使用精确同字也不应再触发
        assertNull(m.onPartial("这道题请张三再说说", ""))
    }
}
