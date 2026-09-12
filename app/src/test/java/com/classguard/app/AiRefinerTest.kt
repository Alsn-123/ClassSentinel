package com.classguard.app

import com.classguard.app.ai.AiRefiner
import com.classguard.app.ai.AnswerProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 修正器（v2.6 可选功能）：prompt 构建、输出防御、调用回退。
 */
class AiRefinerTest {

    private class FakeProvider(private val reply: String?, private val fail: Boolean = false) :
        AnswerProvider {
        var lastPrompt: String? = null
        var lastContext: String? = null
        override suspend fun answer(question: String, context: String?): Result<String> {
            lastPrompt = question
            lastContext = context
            if (fail) return Result.failure(IllegalStateException("network down"))
            return reply?.let { Result.success(it) } ?: Result.failure(IllegalStateException("empty"))
        }
    }

    @Test
    fun `prompt 含校对规则与名单`() {
        val prompt = AiRefiner.buildPrompt("阳丽真ED铮珍M", listOf("阳一真", "李小红"))
        assertTrue(prompt.contains("原始识别：阳丽真ED铮珍M"))
        assertTrue(prompt.contains("阳一真、李小红"))
        assertTrue(prompt.contains("只纠正") || prompt.contains("只输出"))
        assertTrue(prompt.contains("英文碎片"))
    }

    @Test
    fun `prompt 无名单时不输出候选段`() {
        val prompt = AiRefiner.buildPrompt("动能定定定理", emptyList())
        assertTrue(!prompt.contains("候选名单"))
    }

    @Test
    fun `修正成功返回清理后的文本`() = runBlocking {
        val p = FakeProvider("动能定理 大家先想一想")
        val refiner = AiRefiner(p, listOf("阳一真"))
        val out = refiner.refine("动动能定定定理 大家先想一想")
        assertEquals("动能定理 大家先想一想", out)
        // question 是原始识别文本
        assertTrue(p.lastPrompt!!.contains("动动能定定定理"))
    }

    @Test
    fun `接口失败或返回空时回退 null`() = runBlocking {
        assertNull(AiRefiner(FakeProvider(null), emptyList()).refine("回答一下"))
        assertNull(AiRefiner(FakeProvider("", fail = true), emptyList()).refine("回答一下"))
    }

    @Test
    fun `空输入不发起请求`() = runBlocking {
        val p = FakeProvider("x")
        assertNull(AiRefiner(p, emptyList()).refine("  "))
        assertEquals(null, p.lastPrompt)
    }

    @Test
    fun `sanitize 防御幻觉与空输出`() {
        val raw = "回答一下"
        assertNull(AiRefiner.sanitize("", raw))
        assertNull(AiRefiner.sanitize("   ", raw))
        // 超长幻觉拒绝
        assertNull(AiRefiner.sanitize("好".repeat(200), raw))
        // 正常输出保留；包裹引号剥掉
        assertEquals("回答一下", AiRefiner.sanitize("回答一下", raw))
        assertEquals("回答一下", AiRefiner.sanitize("\"回答一下\"", raw))
    }
}
