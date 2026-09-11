package com.classguard.app

import com.classguard.app.ai.OpenAiCompatibleProvider
import com.classguard.app.crash.CrashReporter
import com.classguard.app.data.transcript.TranscriptSegmentEntity
import com.classguard.app.data.transcript.TranscriptSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReporterTest {

    @Test
    fun `崩溃报告包含版本线程与堆栈且不含识别内容`() {
        val report = CrashReporter.format(
            timeMillis = 1_700_000_000_000,
            versionName = "2.2",
            threadName = "asr-loop",
            stackTrace = "java.lang.RuntimeException: boom\n  at Foo.bar(Foo.kt:1)",
        )
        assertTrue(report.contains("2.2"))
        assertTrue(report.contains("asr-loop"))
        assertTrue(report.contains("RuntimeException: boom"))
    }
}

class TranscriptExportTest {

    private val session = TranscriptSessionEntity(id = 1, startTime = 1_000_000, endTime = 1_060_000)

    @Test
    fun `导出文本含头部时间与触发标记`() {
        val segments = listOf(
            TranscriptSegmentEntity(id = 1, sessionId = 1, timeMillis = 1_010_000, text = "这道题考动能定理"),
            TranscriptSegmentEntity(
                id = 2, sessionId = 1, timeMillis = 1_020_000,
                text = "找个同学来回答", isTrigger = true, keyword = "找个同学",
            ),
        )
        val text = com.classguard.app.transcript.buildExportText(session, segments)
        assertTrue(text.contains("课堂哨兵 · 课堂记录"))
        assertTrue(text.contains("约 1 分钟"))
        assertTrue(text.contains("这道题考动能定理"))
        assertTrue(text.contains("▶找个同学"))
        assertTrue(text.contains("找个同学来回答"))
    }

    @Test
    fun `空会话不抛异常`() {
        val text = com.classguard.app.transcript.buildExportText(null, emptyList())
        assertTrue(text.contains("课堂哨兵"))
    }
}

class AiResponseParseTest {

    @Test
    fun `解析标准 chat completions 响应`() {
        val body = """
            {"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"答案是 A"}}]}
        """.trimIndent()
        assertEquals("答案是 A", OpenAiCompatibleProvider.parseChoice(body))
    }

    @Test
    fun `空内容与非法响应返回 null`() {
        assertNull(OpenAiCompatibleProvider.parseChoice("""{"choices":[]}"""))
        assertNull(OpenAiCompatibleProvider.parseChoice("not json"))
        assertNull(OpenAiCompatibleProvider.parseChoice("""{"choices":[{"message":{"content":""}}]}"""))
    }

    @Test
    fun `请求体包含模型与消息`() {
        val body = OpenAiCompatibleProvider.buildRequestJson("题目", "你是助教", "test-model")
        assertTrue(body.toString().contains("test-model"))
        assertTrue(body.toString().contains("题目"))
        assertTrue(body.toString().contains("你是助教"))
    }
}
