package com.classguard.app

import com.classguard.app.ai.AiRefiner
import com.classguard.app.ai.AnswerProvider
import com.classguard.app.ai.OpenAiCompatibleProvider
import com.classguard.app.recognition.PinyinIndex
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 修正器（v2.6 可选功能）：token 节流（shouldRefine）、prompt 构建、
 * 输出防御、调用回退。
 */
class AiRefinerTest {

    private val pinyin = PinyinIndex.fromMap(
        mapOf(
            "阳" to setOf("yan"), "易" to setOf("yi"), "真" to setOf("zen"),
            "杨" to setOf("yan"), "义" to setOf("yi"), "珍" to setOf("zen"),
            "一" to setOf("yi"),
            "丽" to setOf("li"), "今" to setOf("jin"), "天" to setOf("tian"),
        )
    )

    private class FakeProvider(private val reply: String?, private val fail: Boolean = false) :
        AnswerProvider {
        var lastPrompt: String? = null
        var lastContext: String? = null
        var lastMaxTokens: Int? = null
        override suspend fun answer(question: String, context: String?, maxTokens: Int?): Result<String> {
            lastPrompt = question
            lastContext = context
            lastMaxTokens = maxTokens
            if (fail) return Result.failure(IllegalStateException("network down"))
            return reply?.let { Result.success(it) } ?: Result.failure(IllegalStateException("empty"))
        }
    }

    // ------------------------------------------------------------ token 节流

    @Test
    fun `shouldRefine_含英文残留才修正`() {
        val r = AiRefiner(FakeProvider("x"), listOf("阳一真"), pinyin = pinyin)
        assertTrue(r.shouldRefine("杨义珍ED铮珍M"))   // 英文碎片
        assertTrue(r.shouldRefine("今天学习 IMPORTANT 单词")) // 独立英文词交 AI 判断
    }

    @Test
    fun `shouldRefine_名单读音相近但字面不同才修正`() {
        val r = AiRefiner(FakeProvider("x"), listOf("阳一真"), pinyin = pinyin)
        // "杨义珍"读音≈阳一真但字面不同（本地纠偏没写回的边缘情况）→ 交给 AI
        assertTrue(r.shouldRefine("请杨义珍来说"))
        // 精确命中的名单名（本地已写回）→ 不发
        assertFalse(r.shouldRefine("请阳一真来说"))
    }

    @Test
    fun `shouldRefine_干净句子跳过省token`() {
        val r = AiRefiner(FakeProvider("x"), listOf("阳一真"), pinyin = pinyin)
        assertFalse(r.shouldRefine("今天我们讲第三章"))
        assertFalse(r.shouldRefine("这道题考的是动能定理"))
    }

    @Test
    fun `shouldRefine_无名单只看英文残留`() {
        val r = AiRefiner(FakeProvider("x"), emptyList(), pinyin = pinyin)
        assertTrue(r.shouldRefine("你好WORLD"))
        assertFalse(r.shouldRefine("今天天气不错"))
    }

    @Test
    fun `refine_干净句子零请求`() = runBlocking {
        val p = FakeProvider("x")
        val r = AiRefiner(p, listOf("阳一真"), pinyin = pinyin)
        assertNull(r.refine("今天我们讲第三章"))
        assertEquals(null, p.lastPrompt) // 未发起请求
    }

    // ------------------------------------------------------------ prompt 与请求

    @Test
    fun `system指令压缩且含名单_user为原文`() = runBlocking {
        val p = FakeProvider("阳一真你来回答")
        val r = AiRefiner(p, listOf("阳一真", "李小红"), pinyin = pinyin)
        r.refine("杨义珍ED你来回答") // 含英文碎片 → 发请求
        val sys = p.lastContext!!
        assertTrue(sys.contains("同音错字"))
        assertTrue(sys.contains("阳一真、李小红"))
        assertTrue(sys.length < 200) // 指令压缩上限
        assertEquals("杨义珍ED你来回答", p.lastPrompt) // 原文单独走 user
    }

    @Test
    fun `maxTokens 按原文长度估算`() = runBlocking {
        val p = FakeProvider("ok")
        val r = AiRefiner(p, emptyList(), pinyin = pinyin)
        r.refine("abc")
        assertEquals("abc".length * 2 + 40, p.lastMaxTokens)
    }

    @Test
    fun `请求体含温度0与输出上限`() {
        val body = OpenAiCompatibleProvider.buildRequestJson("原文", "规则", "m", 100)
        assertEquals(0, body.getInt("temperature"))
        assertEquals(100, body.getInt("max_tokens"))
    }

    // ------------------------------------------------------------ 修正与防御

    @Test
    fun `修正成功返回清理后的文本`() = runBlocking {
        val p = FakeProvider("动能定理 大家先想一想")
        val r = AiRefiner(p, listOf("阳一真"), pinyin = pinyin)
        val out = r.refine("动动能定定定理M 大家先想一想")
        assertEquals("动能定理 大家先想一想", out)
    }

    @Test
    fun `接口失败或返回空时回退 null`() = runBlocking {
        assertNull(AiRefiner(FakeProvider(null), emptyList(), pinyin = pinyin).refine("abc"))
        assertNull(AiRefiner(FakeProvider("", fail = true), emptyList(), pinyin = pinyin).refine("abc"))
    }

    @Test
    fun `空输入不发起请求`() = runBlocking {
        val p = FakeProvider("x")
        assertNull(AiRefiner(p, emptyList(), pinyin = pinyin).refine("  "))
        assertEquals(null, p.lastPrompt)
    }

    @Test
    fun `sanitize 防御幻觉与空输出`() {
        val raw = "回答一下"
        assertNull(AiRefiner.sanitize("", raw))
        assertNull(AiRefiner.sanitize("   ", raw))
        assertNull(AiRefiner.sanitize("好".repeat(200), raw))
        assertEquals("回答一下", AiRefiner.sanitize("回答一下", raw))
        assertEquals("回答一下", AiRefiner.sanitize("\"回答一下\"", raw))
    }
}
