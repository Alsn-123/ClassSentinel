package com.classguard.app

import com.classguard.app.recognition.TriggerMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerMatcherTest {

    private var now = 0L
    private val clock = { now }
    private val keywords = listOf("找个同学", "回答一下", "叫人答题")

    private fun matcher(cooldown: Long = 15_000) =
        TriggerMatcher(keywords, cooldownMillis = cooldown, clock = clock)

    @Test
    fun `partial 包含关键词时触发`() {
        val m = matcher()
        now = 1000
        val event = m.onPartial("好的，下面我找个同学来回答这道题")
        assertNotNull(event)
        assertEquals("找个同学", event!!.keyword)
        assertTrue(event.utterance.contains("找个同学"))
    }

    @Test
    fun `无关内容不触发`() {
        val m = matcher()
        now = 1000
        assertNull(m.onPartial("今天我们讲第三章，大家翻开课本"))
        assertNull(m.onPartial("这个概念很重要，考试要考"))
    }

    @Test
    fun `标点空白不影响匹配`() {
        val m = matcher()
        now = 1000
        assertNotNull(m.onPartial("好——谁来回答一下？！"))
    }

    @Test
    fun `同一句话只触发一次`() {
        val m = matcher()
        now = 1000
        assertNotNull(m.onPartial("我找个同学来回答"))
        now = 1100
        assertNull(m.onPartial("我找个同学来回答一下这个问题"))
        assertNull(m.onFinal("我找个同学来回答一下这个问题"))
    }

    @Test
    fun `冷却时间内不重复触发`() {
        val m = matcher()
        now = 1000
        assertNotNull(m.onPartial("找个同学"))
        m.onFinal("找个同学")
        now = 8000
        assertNull(m.onPartial("我再找个同学回答一下"))
        now = 16_001
        assertNotNull(m.onPartial("这次找个同学"))
    }

    @Test
    fun `关键词在结尾被切断时 final 补触发`() {
        val m = matcher()
        now = 1000
        assertNull(m.onPartial("下面我找个同"))
        now = 1100
        val event = m.onFinal("下面我找个同学来回答")
        assertNotNull(event)
        assertEquals("找个同学", event!!.keyword)
    }

    @Test
    fun `final 提交进上下文并作为下次触发的前文`() {
        val m = matcher()
        now = 1000
        m.onFinal("这道题考的是第三章的概率问题，大家先想两分钟")
        now = 20_000
        val event = m.onPartial("好，找个同学来回答")
        assertNotNull(event)
        assertTrue(event!!.context.contains("概率"))
    }

    @Test
    fun `上下文缓冲区被裁剪到上限`() {
        val m = TriggerMatcher(
            keywords, cooldownMillis = 15_000, maxContextChars = 80, clock = clock
        )
        var t = 0L
        // 灌入多句话
        for (i in 1..10) {
            t += 60_000
            now = t
            m.onFinal("第${i}句话的内容比较长一些用来填充上下文缓冲区")
        }
        t += 60_000
        now = t
        val event = m.onPartial("找个同学")
        assertNotNull(event)
        assertTrue("上下文应不超过80字", event!!.context.length <= 80)
        // 最早的话应已被裁掉
        assertTrue(!event.context.contains("第1句话"))
        // 最近的话应保留
        assertTrue(event.context.contains("第10句话"))
    }

    @Test
    fun `reset 清空上下文`() {
        val m = matcher()
        m.onFinal("前面说过的内容")
        m.reset()
        now = 1000
        val event = m.onPartial("找个同学")
        assertNotNull(event)
        assertEquals("", event!!.context)
    }

    @Test
    fun `normalize 只保留汉字字母数字`() {
        assertEquals("回答3下ok", TriggerMatcher.normalize("回 答，3下！OK？"))
    }

    @Test
    fun `空词表不触发`() {
        val m = TriggerMatcher(emptyList(), clock = clock)
        assertNull(m.onPartial("找个同学"))
    }

    @Test
    fun `关键词列表自动去重`() {
        val m = TriggerMatcher(listOf("回答一下", "回答一下"), clock = clock)
        now = 1000
        assertNotNull(m.onPartial("请你回答一下"))
    }
}
