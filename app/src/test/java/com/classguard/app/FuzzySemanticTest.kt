package com.classguard.app

import org.junit.Assert.*
import java.util.Arrays

import com.classguard.app.recognition.Confidence
import com.classguard.app.recognition.KeywordSpec
import com.classguard.app.recognition.KeywordType
import com.classguard.app.recognition.PinyinIndex
import com.classguard.app.recognition.RosterEntry
import com.classguard.app.recognition.RosterMatcher
import com.classguard.app.recognition.TextRepair
import com.classguard.app.recognition.TriggerMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.2 语义校验：容错对齐（单字被展开成多字）、读音层拦截异读、
 * 核心词同音错字、Confidence 模糊惩罚。
 *
 * 索引基于归一后真实同音关系（构建期已去声调、混淆归一）：
 *   找=兆 zhao、答=大 da、学=雪 xue、张=章 zan、同=tong、一种=yi zhong
 */
class FuzzySemanticTest {

    private val index = PinyinIndex.fromMap(
        mapOf(
            "找" to setOf("zhao"), "兆" to setOf("zhao"),
            "个" to setOf("ge"),
            "同" to setOf("tong"),
            "学" to setOf("xue"), "雪" to setOf("xue"),
            "回" to setOf("hui"),
            "答" to setOf("da"), "大" to setOf("da"),
            "一" to setOf("yi"), "来" to setOf("lai"),
            "下" to setOf("xia"),
            "张" to setOf("zan"), "章" to setOf("zan"),
            "伟" to setOf("wei"),
            "种" to setOf("zhong"),
            "说" to setOf("shuo"), "题" to setOf("ti"),
        )
    )

    // ------------------------------------------------------------ 对齐算法

    @Test
    fun `容错对齐_同音错字命中且计入替换`() {
        // 名字"找个同学" vs "兆个同学来回答"：找(zhao)→兆(zhao) 同音替换
        val hit = index.findAlignedMatch("找个同学", "兆个同学来回答")!!
        assertEquals(0, hit.offset)
        assertEquals(0, hit.insertions)
        assertEquals(1, hit.substitutions)
        assertEquals(1, hit.insertions + hit.substitutions)
    }

    @Test
    fun `容错对齐_音节间插入无关字`() {
        // 名字"找个同学" vs "找个一同学"：个与同之间插了一个"一"
        val hit = index.findAlignedMatch("找个同学", "找个一同学")!!
        assertEquals(1, hit.insertions)
        assertEquals(0, hit.substitutions)
    }

    @Test
    fun `容错对齐_插入超预算被拒绝`() {
        // 名字 4 字"找个同学"，预算 maxInsertionsFor(4)=2，插 1 字可接受
        assertNotNull(index.findAlignedMatch("找个同学", "找个一同学"))
        // 名字 3 字"回答一"（回/答/一），预算 maxInsertionsFor(3)=2，插 2 字可接受
        assertNotNull(index.findAlignedMatch("回答一", "这个回答一种一"))
        // 插入数受预算封顶：名字 4 字但要求 3 个无关字 → 拒绝
        assertNull(index.findAlignedMatch("找个同学", "找个一一种同学"))
    }

    @Test
    fun `容错对齐_非读音不匹配被拦截`() {
        // "早"(zao) 与 "找"(zhao) 非同一读音根 → 名字找不到
        val indexZao = PinyinIndex.fromMap(
            mapOf(
                "早" to setOf("zao"), "个" to setOf("ge"),
                "同" to setOf("tong"), "学" to setOf("xue"),
            )
        )
        assertNull(indexZao.findAlignedMatch("找个同学", "早个同学"))
    }

    @Test
    fun `findMatch 仅接受严格连续对齐`() {
        // findMatch（v2.1 旧入口）要求插入=0
        assertNull(index.findMatch("同学", "同一种学")) // 有插入 → 不命中
        assertNotNull(index.findMatch("同学", "同学"))
    }

    // ------------------------------------------------------------ 名单匹配

    @Test
    fun `名单_同音错字纠偏为正确名`() {
        val roster = listOf(RosterEntry("张伟", isMe = true))
        val m = RosterMatcher(roster, specs(), clock = { 1000L }, pinyin = index)
        val e = m.onPartial("章伟来说说", "这道题")
        assertNotNull(e)
        assertEquals("张伟", e!!.keyword)
        assertEquals(1, e.fuzzyEdits) // 章/张 同音替换 1 处
        assertTrue(e.directed)
    }

    @Test
    fun `名单_音节间插入无关字仍能命中`() {
        // "张伟" 2 字，预算 1；"张来伟"：张(目标1)、来(插入, lai≠目标)、伟(目标2) → 插 1 字
        val roster = listOf(RosterEntry("张伟"))
        val m = RosterMatcher(roster, specs(), clock = { 1000L }, pinyin = index)
        val e = m.onPartial("张来伟来说说", "这道题")
        assertNotNull(e)
        assertEquals("张伟", e!!.keyword)
        assertEquals(1, e.fuzzyEdits) // 1 处插入、无同音替换
    }

    @Test
    fun `名单_同音错字写回原句`() {
        val roster = listOf(RosterEntry("张伟", isMe = false))
        val m = RosterMatcher(roster, specs(), clock = { 1000L }, pinyin = index)
        val e = m.onPartial("张来伟来说说", "这道题")
        assertNotNull(e)
        // 原句"张来伟"：张(对齐)来(插入)伟(对齐)，整段替换为正确姓名"张伟"
        assertEquals("张伟来说说", e!!.utterance)
    }

    @Test
    fun `名单_同音错字写回原句_两连卡顿不残留`() {
        // ASR 卡顿"张张伟"：归一化折叠两连后精确命中；写回区间映射到连字首字，不残留重复
        val roster = listOf(RosterEntry("张伟", isMe = false))
        val m = RosterMatcher(roster, specs(), clock = { 1000L }, pinyin = index)
        val e = m.onPartial("张张伟来说说", "这道题")
        assertNotNull(e)
        assertEquals(0, e!!.fuzzyEdits) // 折叠后"张伟"字面精确命中
        assertEquals("张伟来说说", e.utterance)
    }

    // ------------------------------------------------------------ 关键词匹配

    @Test
    fun `核心词_同音错字命中`() {
        val m = TriggerMatcher(listOf(KeywordSpec("回答一下", KeywordType.CORE)), clock = { 1000L }, pinyin = index)
        // "回大一下" →"回"+"大"(答同音)+"一"+"下"；中间"下"仍匹配，本质同音错字+精确
        val e = m.onPartial("回大一下")
        assertNotNull(e)
        assertEquals("回答一下", e!!.keyword)
        assertTrue(e.fuzzyEdits > 0)
    }

    @Test
    fun `核心词_精确命中编辑数为0`() {
        val m = TriggerMatcher(listOf(KeywordSpec("回答一下", KeywordType.CORE)), clock = { 1000L }, pinyin = index)
        val e = m.onPartial("回答一下这道题")!!
        assertEquals(0, e.fuzzyEdits)
    }

    @Test
    fun `核心词_无拼音索引时保持严格字面匹配`() {
        val m = TriggerMatcher(listOf(KeywordSpec("回答一下", KeywordType.CORE)), clock = { 1000L })
        assertNull(m.onPartial("回大一下"))
        assertNotNull(m.onPartial("回答一下"))
    }

    @Test
    fun `核心词_单字展开容错命中`() {
        // "同学" 2 字，预算 maxInsertionsFor(2)=1：
        // "同个学" 中间插 1 字"个"，仍命中；插入数在预算内
        val m = TriggerMatcher(listOf(KeywordSpec("同学", KeywordType.CORE)), clock = { 1000L }, pinyin = index)
        val e = m.onPartial("同个学")
        assertNotNull(e)
        assertEquals("同学", e!!.keyword)
        assertTrue(e.fuzzyEdits > 0)
        // 插入 2 字"同一种学"超出预算 → 不命中
        assertNull(m.onPartial("同一种学"))
    }

    @Test
    fun `归一化映射_同字连发全折叠`() {
        val cases = mapOf(
            // <原文, 归一化>：匹配层 ≥2 连同字一律折叠（词表侧同规则，精确匹配不受影响）
            "来请请请同学回答" to "来请同学回答",
            "动能定定定定理" to "动能定理",
            "动能定定理" to "动能定理",
            "你来说说说说你的思路" to "你来说你的思路",
            "请请张三同学来" to "请张三同学来",
            "谢谢张三" to "谢张三",
            "aabbccdd" to "abcd",
            "!!你好，world！！！12333" to "你好world123",
        )
        cases.forEach { (raw, norm) ->
            val (actual, _) = TriggerMatcher.normalizeWithMap(raw)
            assertEquals(norm, actual)
        }
    }

    @Test
    fun `归一化映射_下标正确`() {
        val text = "你好，张三同学来！222"
        val (norm, mapping) = TriggerMatcher.normalizeWithMap(text)
        assertEquals("你好张三同学来2", norm)
        // 你(0) 好(1) 张(3) 三(4) 同(5) 学(6) 来(7) 2(9，三连折叠指向首字)
        val expected = intArrayOf(0, 1, 3, 4, 5, 6, 7, 9)
        assertTrue(Arrays.equals(expected, mapping))
    }

    @Test
    fun `名单_近似音纠偏（声母不同韵母相同）`() {
        // 用户实测案例：阳一真(yang yi zhen) 被识别成 阳丽真(yang li zhen)
        // 易(yi)/丽(li) 声母不同、韵母同为 i → 近似音；姓氏杨必须对上
        val index2 = PinyinIndex.fromMap(
            mapOf(
                "阳" to setOf("yan"), "易" to setOf("yi"), "臻" to setOf("zen"),
                "真" to setOf("zen"),
                "丽" to setOf("li"), "珍" to setOf("zen"),
                "来" to setOf("lai"), "说" to setOf("suo"),
            )
        )
        val roster = listOf(RosterEntry("阳一真", isMe = true))
        val m = RosterMatcher(roster, specs(), clock = { 1000L }, pinyin = index2)
        val e = m.onPartial("这道题请阳丽真来说", "")
        assertNotNull(e)
        assertEquals("阳一真", e!!.keyword) // 事件回传真名
        assertTrue(e.directed)
        assertTrue(e.utterance.contains("阳一真")) // 原句错字已写回真名
    }

    @Test
    fun `名单_近似音不认姓氏错的名字`() {
        // 姓氏读音完全不符（王 wang vs 杨 yan）→ 拒绝，防误报
        val index2 = PinyinIndex.fromMap(
            mapOf(
                "杨" to setOf("yan"), "易" to setOf("yi"), "臻" to setOf("zen"),
                "王" to setOf("wan"), "丽" to setOf("li"), "珍" to setOf("zen"),
            )
        )
        val roster = listOf(RosterEntry("阳一真"))
        val m = RosterMatcher(roster, specs(), clock = { 1000L }, pinyin = index2)
        assertNull(m.onPartial("这道题请王丽珍来说", ""))
    }

    @Test
    fun `名单_两字名不接受近似音`() {
        // 2 字名韵母组合太宽（李/你/米 韵母都是 i），只认同音替换，不认近似音
        val index2 = PinyinIndex.fromMap(
            mapOf(
                "李" to setOf("li"), "娜" to setOf("na"),
                "米" to setOf("mi"), "那" to setOf("na"),
            )
        )
        val roster = listOf(RosterEntry("李娜"))
        val m = RosterMatcher(roster, specs(), clock = { 1000L }, pinyin = index2)
        assertNull(m.onPartial("这道题请米那来说", ""))
    }

    @Test
    fun `告警决策_精确命中一律全量提醒`() {
        // 回归"识别出来了却不报警"：字面完全命中的关键词不再因置信度被静音
        assertTrue(Confidence.shouldFullAlert(0.10, 0))
        assertTrue(Confidence.shouldFullAlert(0.0, 0))
        assertTrue(Confidence.shouldFullAlert(null, 0))
    }

    @Test
    fun `告警决策_普通容错命中仍全量提醒`() {
        // 典型场景：置信 0.55、纠偏 1 处 → 0.45 ≥ 0.2 → 横幅+震动+声音
        val c = Confidence.withFuzzyPenalty(0.55, 1)
        assertTrue(Confidence.shouldFullAlert(c, 1))
    }

    @Test
    fun `告警决策_仅明显异常才降级静音`() {
        val c = Confidence.withFuzzyPenalty(0.25, 2) // → 0.05
        assertFalse(Confidence.shouldFullAlert(c, 2))
    }

    @Test
    fun `名单_repairNames 把 final 句中的畸变写回真名`() {
        // 触发判定跑在 partial 上，转写存的是更完整的 final；repairNames 要对 final 再纠偏一次
        val roster = listOf(RosterEntry("阳一真", isMe = true))
        val m = RosterMatcher(roster, specs(), clock = { 1000L }, pinyin = index2())
        // 近似音（丽 li ↔ 易 yi）与同音（珍/臻 zhen）都应写回
        assertEquals("这道题请阳一真来说", m.repairNames("这道题请阳丽真来说"))
        assertEquals("阳一真", m.repairNames("阳易真"))
        // 无关句原样返回
        assertEquals("今天天气不错", m.repairNames("今天天气不错"))
    }

    @Test
    fun `名单_repairNames 不改触发状态`() {
        // 修复文本不应消耗冷却或置位"同句已触发"，否则会吞掉真正的触发
        val roster = listOf(RosterEntry("阳一真", isMe = true))
        val m = RosterMatcher(roster, specs(), clock = { 1000L }, pinyin = index2())
        m.repairNames("阳丽真")
        assertNotNull(m.onPartial("这道题请阳丽真来说", "")) // 仍能正常触发
    }

    @Test
    fun `名单_删除容错（ASR 吞掉名字里的字）`() {
        // 真机记录：说"阳一真"被识别成"养一"（yang yi，吞掉了"臻"）——未触发
        val roster = listOf(RosterEntry("阳一真", isMe = true))
        val m = RosterMatcher(roster, specs(), clock = { 1000L }, pinyin = index2())
        val e = m.onPartial("这道题请养一来回答", "")
        assertNotNull(e)
        assertEquals("阳一真", e!!.keyword)
        assertTrue(e.utterance.contains("阳一真")) // 被吞的字补回真名
    }

    @Test
    fun `名单_删除容错仍要求姓氏命中`() {
        // "王一"对"阳一真"：姓氏王(wang)与杨(yan)不符 → 不认
        val indexWang = PinyinIndex.fromMap(
            mapOf(
                "阳" to setOf("yan"), "易" to setOf("yi"), "臻" to setOf("zen"),
                "真" to setOf("zen"),
                "王" to setOf("wan"), "一" to setOf("yi"),
            )
        )
        val roster = listOf(RosterEntry("阳一真"))
        val m = RosterMatcher(roster, specs(), clock = { 1000L }, pinyin = indexWang)
        assertNull(m.onPartial("这道题请王一来回答", ""))
    }

    @Test
    fun `名单_删除容错对两字名不生效`() {
        // 2 字名不启用删除容错（只剩一个字，误报代价高）
        val roster = listOf(RosterEntry("阳一"))
        val m = RosterMatcher(roster, specs(), clock = { 1000L }, pinyin = index2())
        assertNull(m.onPartial("这道题请阳来回答", ""))
    }

    private fun index2() = PinyinIndex.fromMap(
        mapOf(
            "阳" to setOf("yan"), "易" to setOf("yi"), "臻" to setOf("zen"),
            "真" to setOf("zen"),
            "丽" to setOf("li"), "珍" to setOf("zen"), "来" to setOf("lai"),
            "说" to setOf("suo"), "今" to setOf("jin"), "天" to setOf("tian"),
            "气" to setOf("qi"), "不" to setOf("bu"), "错" to setOf("cuo"),
            "养" to setOf("yan"), "一" to setOf("yi"),
        )
    )

    // ------------------------------------------------------------ 置信度惩罚

    @Test
    fun `Confidence_模糊纠偏扣置信度`() {
        // 精确 0 / 同音 1 → 各扣 0.1
        assertEquals(0.9, Confidence.withFuzzyPenalty(1.0, 1)!!, 0.001)
        assertEquals(0.7, Confidence.withFuzzyPenalty(1.0, 3)!!, 0.001)
    }

    @Test
    fun `Confidence_无纠偏或空值不惩罚`() {
        assertEquals(0.6, Confidence.withFuzzyPenalty(0.6, 0)!!, 0.001)
        assertEquals(null, Confidence.withFuzzyPenalty(null, 2))
    }

    @Test
    fun `Confidence_惩罚有下限且阈值放宽为0_2`() {
        assertEquals(0.05, Confidence.withFuzzyPenalty(0.05, 9)!!, 0.001)
        // v2.3 阈值 0.5→0.2：普通命中（0.55 扣 1 处 = 0.45）仍走全量提醒，不再被静音
        assertEquals(0.2, Confidence.HIGH_THRESHOLD, 0.0001)
        assertTrue(Confidence.withFuzzyPenalty(0.55, 1)!! >= Confidence.HIGH_THRESHOLD)
        // 只有明显异常（低置信 + 多处置信惩罚）才降到阈值以下
        assertTrue(Confidence.withFuzzyPenalty(0.25, 2)!! < Confidence.HIGH_THRESHOLD)
    }

    private fun specs() = listOf(KeywordSpec("这道题", KeywordType.CONTEXT))
}