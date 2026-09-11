package com.classguard.app

import com.classguard.app.recognition.Confidence
import com.classguard.app.recognition.KeywordSpec
import com.classguard.app.recognition.KeywordSpecCodec
import com.classguard.app.recognition.KeywordType
import com.classguard.app.recognition.RosterCodec
import com.classguard.app.recognition.RosterEntry
import com.classguard.app.recognition.RosterMatcher
import com.classguard.app.recognition.TriggerMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词表编解码与旧数据迁移。
 */
class KeywordSpecCodecTest {

    @Test
    fun `编解码往返保真`() {
        val specs = listOf(
            KeywordSpec("找个同学", KeywordType.CORE),
            KeywordSpec("这道题", KeywordType.CONTEXT),
            KeywordSpec("下课了", KeywordType.EXCLUDE),
        )
        assertEquals(specs, KeywordSpecCodec.decode(KeywordSpecCodec.encode(specs)))
    }

    @Test
    fun `旧数据迁移为全 CORE`() {
        val legacy = listOf("回答一下", "找个同学")
        val migrated = KeywordSpecCodec.migrateFromLegacyKeywords(legacy)
        assertTrue(migrated.all { it.type == KeywordType.CORE })
        assertEquals(listOf("回答一下", "找个同学"), migrated.map { it.text })
    }

    @Test
    fun `解码非法类型回退 CORE`() {
        val decoded = KeywordSpecCodec.decode("""[{"t":"x","k":"WHATEVER"}]""")
        assertEquals(KeywordType.CORE, decoded.first().type)
    }
}

/**
 * 触发匹配核心规则：排除优先 / 语境门控（同句+跨句）/ 按词分级冷却 / 原地更新 / 原词展示。
 */
class TriggerMatcherTest {

    private var now = 0L
    private val clock = { now }

    private fun core(vararg words: String) = words.map { KeywordSpec(it, KeywordType.CORE) }
    private fun context(vararg words: String) = words.map { KeywordSpec(it, KeywordType.CONTEXT) }
    private fun exclude(vararg words: String) = words.map { KeywordSpec(it, KeywordType.EXCLUDE) }

    // ------------------------------------------------------------ 基础行为

    @Test
    fun `核心词命中触发且返回用户原词`() {
        val m = TriggerMatcher(core("找个同学"), clock = clock)
        now = 1000
        val e = m.onPartial("好的，下面我找个同学来回答这道题")
        assertNotNull(e)
        assertEquals("找个同学", e!!.keyword)
    }

    @Test
    fun `无关内容不触发`() {
        val m = TriggerMatcher(core("找个同学", "回答一下"), clock = clock)
        now = 1000
        assertNull(m.onPartial("今天我们讲第三章，大家翻开课本"))
    }

    @Test
    fun `标点空白不影响匹配`() {
        val m = TriggerMatcher(core("回答一下"), clock = clock)
        now = 1000
        assertNotNull(m.onPartial("好——谁来回答一下？！"))
    }

    @Test
    fun `关键词在结尾被切断时 final 补触发`() {
        val m = TriggerMatcher(core("找个同学"), clock = clock)
        now = 1000
        assertNull(m.onPartial("下面我找个同"))
        now = 1100
        assertNotNull(m.onFinal("下面我找个同学来回答"))
    }

    @Test
    fun `final 提交进上下文并作为事件前文`() {
        val m = TriggerMatcher(core("找个同学"), clock = clock)
        m.onFinal("这道题考的是第三章的概率问题")
        now = 20_000
        val e = m.onPartial("好，找个同学来回答")
        assertTrue(e!!.context.contains("概率"))
    }

    @Test
    fun `reset 清空上下文与冷却`() {
        val m = TriggerMatcher(core("找个同学"), clock = clock)
        now = 1000
        assertNotNull(m.onPartial("找个同学")) // 记录冷却
        now = 20_000 // 但仍在冷却期内（找个同学 4 字 → base 15s）
        assertNull(m.onPartial("找个同学"))
        // reset 清空冷却计时
        m.reset()
        now = 21_000
        val e = m.onPartial("找个同学")
        assertNotNull(e)
        assertEquals("", e!!.context)
    }

    @Test
    fun `normalize 只保留汉字字母数字`() {
        assertEquals("回答3下ok", TriggerMatcher.normalize("回 答，3下！OK？"))
    }

    // ------------------------------------------------------------ 排除词

    @Test
    fun `排除词屏蔽本句 partial 与 final`() {
        val m = TriggerMatcher(core("回答一下") + exclude("同桌"), clock = clock)
        now = 1000
        assertNull(m.onPartial("同桌回答一下"))
        now = 2000
        assertNull(m.onFinal("同桌回答一下这道题"))
    }

    @Test
    fun `被排除的句子仍进入上下文`() {
        val m = TriggerMatcher(core("找个同学") + exclude("同桌"), clock = clock)
        now = 1000
        m.onFinal("同桌刚才回答得不错")
        now = 20_000
        val e = m.onPartial("找个同学")
        assertTrue(e!!.context.contains("同桌"))
    }

    // ------------------------------------------------------------ 语境门控

    @Test
    fun `语境词门控作用于短核心词`() {
        // v2.3：门控只作用于 <4 字的短核心词（长短语语义自足，见下一个用例）
        val m = TriggerMatcher(core("找同学") + context("这道题"), clock = clock)
        now = 1000
        assertNull(m.onPartial("我找同学")) // 无语境
        now = 2000
        assertNotNull(m.onPartial("这道题我找同学")) // 同句语境
    }

    @Test
    fun `长核心词不因缺语境词漏报`() {
        // 回归：教师只说点名语、前后文没有语境词时必须照样触发（原来整句被门控拦掉）
        val m = TriggerMatcher(core("回答一下", "找个同学") + context("这道题"), clock = clock)
        now = 1000
        val e = m.onPartial("下面你回答一下")
        assertNotNull(e)
        assertEquals("回答一下", e!!.keyword)
        m.onFinal("下面你回答一下")
        now = 100_000
        assertNotNull(m.onPartial("我找个同学"))
    }

    @Test
    fun `语境词可跨句命中（前文出题后句点名）`() {
        val m = TriggerMatcher(core("找个同学") + context("这道题"), clock = clock)
        m.onFinal("这道题考的是动能定理")
        now = 30_000
        assertNotNull(m.onPartial("下面我找个同学")) // 语境在上一句
    }

    @Test
    fun `清空语境词退化为纯核心词模式`() {
        val m = TriggerMatcher(core("找个同学") + context(), clock = clock)
        now = 1000
        assertNotNull(m.onPartial("我找个同学"))
    }

    @Test
    fun `语境词表不含排除逻辑干扰`() {
        val m = TriggerMatcher(core("回答一下") + context("这道题") + exclude("同桌"), clock = clock)
        now = 1000
        assertNull(m.onPartial("同桌这道题回答一下")) // 排除优先于门控
    }

    // ------------------------------------------------------------ 冷却

    @Test
    fun `按词独立冷却互不压制`() {
        val m = TriggerMatcher(core("找个同学", "谁来回答"), clock = clock)
        now = 1000
        assertNotNull(m.onPartial("找个同学"))
        m.onFinal("这句话结束")
        now = 2000
        // 另一个词不受"找个同学"冷却影响（语境词表为空，直接匹配）
        assertNotNull(m.onPartial("谁来回答"))
    }

    @Test
    fun `同一词冷却期内不重复触发`() {
        val m = TriggerMatcher(core("找个同学"), baseCooldownMillis = 15_000, clock = clock)
        now = 1000
        assertNotNull(m.onPartial("找个同学"))
        m.onFinal("这句话结束")
        now = 10_000
        assertNull(m.onPartial("我再找个同学"))
        m.onFinal("这句话结束")
        now = 26_001
        assertNotNull(m.onPartial("这次又找个同学"))
    }

    @Test
    fun `冷却按词长分级_长词减半`() {
        // "请你来说说这道题" 7字 → 冷却 = base/2
        val m = TriggerMatcher(core("请你来说说这道题"), baseCooldownMillis = 20_000, clock = clock)
        now = 1000
        assertNotNull(m.onPartial("请你来说说这道题"))
        m.onFinal("这句话结束")
        now = 1_000 + 20_000 / 2 - 1
        assertNull(m.onPartial("请你来说说这道题"))
        m.onFinal("这句话结束")
        now = 1_000 + 20_000 / 2 + 1
        assertNotNull(m.onPartial("请你来说说这道题"))
    }

    @Test
    fun `冷却按词长分级_短词翻倍`() {
        // "回答" 2字 → 冷却 = base*2
        val m = TriggerMatcher(core("回答"), baseCooldownMillis = 10_000, clock = clock)
        now = 1000
        assertNotNull(m.onPartial("这个问题谁来回答"))
        m.onFinal("这句话结束")
        now = 1_000 + 20_000 - 1
        assertNull(m.onPartial("再来回答"))
        m.onFinal("这句话结束")
        now = 1_000 + 20_000 + 1
        assertNotNull(m.onPartial("继续回答"))
    }

    @Test
    fun `同句只触发一次`() {
        val m = TriggerMatcher(core("找个同学"), clock = clock)
        now = 1000
        assertNotNull(m.onPartial("我找个同学来回答"))
        now = 1100
        assertNull(m.onPartial("我找个同学来回答一下这个问题"))
        assertNull(m.onFinal("我找个同学来回答一下这个问题"))
    }

    // ------------------------------------------------------------ 原地更新

    @Test
    fun `原地更新词表保留上下文`() {
        val m = TriggerMatcher(core("找个同学") + context("这道题"), clock = clock)
        m.onFinal("这道题大家先思考")
        now = 20_000
        // 词表换了实例规则但保留上下文：新核心词 + 旧前文语境 → 触发
        m.updateKeywordSpecs(core("谁来说说") + context("这道题"))
        val e = m.onPartial("下面谁来说说")
        assertNotNull(e)
        assertEquals("谁来说说", e!!.keyword)
        assertTrue(e.context.contains("思考"))
    }

    @Test
    fun `原地更新保留冷却计时`() {
        val m = TriggerMatcher(core("找个同学"), baseCooldownMillis = 15_000, clock = clock)
        now = 1000
        assertNotNull(m.onPartial("找个同学"))
        now = 5000
        m.updateKeywordSpecs(core("找个同学"))
        m.updateBaseCooldown(15_000)
        assertNull(m.onPartial("又找个同学")) // 冷却未因更新而清零
    }

    @Test
    fun `原地更新冷却时长立即生效`() {
        val m = TriggerMatcher(core("回答"), baseCooldownMillis = 20_000, clock = clock)
        now = 1000
        assertNotNull(m.onPartial("谁来回答"))
        m.onFinal("这句话结束")
        m.updateBaseCooldown(5_000)
        now = 1_000 + 2 * 5_000 + 1 // 短词 base*2，新 base=5s → 10s 后可再触发
        assertNotNull(m.onPartial("再回答一次"))
    }
}

/**
 * 名单匹配：分层变体 / 语境门控 / 定向提醒 / 独立更长冷却 / 批量导入。
 */
class RosterMatcherTest {

    private var now = 0L
    private val clock = { now }

    private val specs = listOf(
        KeywordSpec("这道题", KeywordType.CONTEXT),
        KeywordSpec("下课", KeywordType.EXCLUDE),
    )
    private val roster = listOf(
        RosterEntry("张三", aliases = listOf("老张"), asrVariants = listOf("章三"), isMe = true),
        RosterEntry("李四"),
    )

    @Test
    fun `别名与误听变体都算命中`() {
        val m = RosterMatcher(roster, specs, clock = clock)
        now = 1000
        assertEquals("张三", m.onPartial("好的老张你来说说", "这道题考动能定理")!!.keyword)
        m.onFinal()
        now = 1_000_000
        assertEquals("张三", m.onPartial("请章三回答", "这道题")!!.keyword)
    }

    @Test
    fun `语境词非空时姓名需语境配合`() {
        val m = RosterMatcher(roster, specs, clock = clock)
        now = 1000
        assertNull(m.onPartial("张三", "")) // 无语境
        now = 100_000
        assertNotNull(m.onPartial("张三", "这道题谁来答")) // 前文含语境
    }

    @Test
    fun `排除词屏蔽姓名触发`() {
        val m = RosterMatcher(roster, specs, clock = clock)
        now = 1000
        assertNull(m.onPartial("张三下课来说说", "这道题"))
    }

    @Test
    fun `我的名字命中为定向提醒`() {
        val m = RosterMatcher(roster, specs, clock = clock)
        now = 1000
        val e = m.onPartial("张三你来说说", "这道题")
        assertTrue(e!!.directed)
        val e2 = RosterMatcher(roster, specs, clock = clock).let {
            now = 1_000_000
            it.onPartial("李四回答一下", "这道题")
        }
        assertFalse(e2!!.directed)
    }

    @Test
    fun `多个命中优先我的名字`() {
        val m = RosterMatcher(roster, specs, clock = clock)
        now = 1000
        val e = m.onPartial("张三和李四都来", "这道题")
        assertEquals("张三", e!!.keyword)
        assertTrue(e.directed)
    }

    @Test
    fun `姓名冷却为基础两倍且按名独立`() {
        val m = RosterMatcher(roster, specs, baseCooldownMillis = 10_000, clock = clock)
        now = 1000
        assertNotNull(m.onPartial("张三说说", "这道题"))
        m.onFinal()
        now = 100_000 // 99 秒后：张三（冷却 2*2*10s=40s 内的字级 grade）已过期，李四本就无冷却
        val e = m.onPartial("李四说说", "这道题")
        assertEquals("李四", e!!.keyword) // 不被张三的冷却压制
    }

    @Test
    fun `同句只触发一次`() {
        val m = RosterMatcher(roster, specs, clock = clock)
        now = 1000
        assertNotNull(m.onPartial("张三说说", "这道题"))
        assertNull(m.onPartial("张三再说点", "这道题"))
        m.onFinal()
        now = 1_000_000
        assertNotNull(m.onPartial("张三又说", "这道题"))
    }

    @Test
    fun `批量导入解析`() {
        val parsed = RosterCodec.parseImportText("张三/老张/小三\n\n李四\n王五/小王")
        assertEquals(3, parsed.size)
        assertEquals("张三", parsed[0].displayName)
        assertEquals(listOf("老张", "小三"), parsed[0].aliases)
        assertEquals("李四", parsed[1].displayName)
        assertTrue(parsed[1].aliases.isEmpty())
    }

    @Test
    fun `名单编解码往返保真`() {
        val decoded = RosterCodec.decode(RosterCodec.encode(roster))
        assertEquals(roster, decoded)
    }

    @Test
    fun `allTexts 去重收集`() {
        val e = RosterEntry("张三", aliases = listOf("张三", "老张"))
        assertEquals(listOf("张三", "老张"), e.allTexts())
    }
}

/**
 * 置信度提取：字级 token 对齐关键词片段；ysProbs 为对数概率，结果经 exp 回概率域。
 */
class ConfidenceTest {

    private val ln0_2 = Math.log(0.2).toFloat()
    private val ln0_9 = Math.log(0.9).toFloat()

    @Test
    fun `命中片段取平均`() {
        val tokens = listOf("这", "道", "题", "找", "个", "同", "学")
        val probs = floatArrayOf(ln0_9, ln0_9, ln0_9, ln0_2, ln0_2, ln0_2, ln0_2)
        val c = Confidence.forKeyword(tokens, probs, "找个同学")!!
        assertEquals(0.2, c, 0.01)
    }

    @Test
    fun `取最优命中段`() {
        val tokens = listOf("找", "个", "同", "学", "找", "个", "同", "学")
        val probs = floatArrayOf(ln0_2, ln0_2, ln0_2, ln0_2, ln0_9, ln0_9, ln0_9, ln0_9)
        val c = Confidence.forKeyword(tokens, probs, "找个同学")!!
        assertEquals(0.9, c, 0.01)
    }

    @Test
    fun `定位失败退化为整句平均`() {
        val tokens = listOf("今", "天", "天", "气")
        val lnHalf = Math.log(0.5).toFloat()
        val probs = floatArrayOf(lnHalf, lnHalf, lnHalf, lnHalf)
        assertEquals(0.5, Confidence.forKeyword(tokens, probs, "找个同学")!!, 0.01)
    }

    @Test
    fun `对数概率转概率域`() {
        // avg(log)=-0.5664 → exp≈0.5676
        val tokens = listOf("找", "个", "同", "学")
        val p = -0.5664460957050323f
        val c = Confidence.forKeyword(tokens, floatArrayOf(p, p, p, p), "找个同学")!!
        assertEquals(0.5676, c, 0.001)
    }

    @Test
    fun `长度不匹配返回 null`() {
        assertNull(Confidence.forKeyword(listOf("a"), floatArrayOf(0.5f, 0.5f), "a"))
        assertNull(Confidence.forKeyword(emptyList(), floatArrayOf(), "a"))
    }

    @Test
    fun `overall 整句平均`() {
        val c = Confidence.overall(
            listOf("a", "b"),
            floatArrayOf(Math.log(0.5).toFloat(), Math.log(0.7).toFloat()),
        )!!
        assertEquals(Math.sqrt(0.35), c, 0.01)
    }
}
