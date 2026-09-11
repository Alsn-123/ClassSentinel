package com.classguard.app.selftest

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.classguard.app.data.PrefsStore
import com.classguard.app.recognition.Confidence
import com.classguard.app.recognition.KeywordSpec
import com.classguard.app.recognition.KeywordType
import com.classguard.app.recognition.PinyinIndex
import com.classguard.app.recognition.RosterEntry
import com.classguard.app.recognition.RosterMatcher
import com.classguard.app.recognition.TextRepair
import com.classguard.app.recognition.TriggerEvent
import com.classguard.app.recognition.TriggerMatcher
import com.classguard.app.service.AsrEngine
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import kotlin.math.roundToInt

/**
 * 端到端自测：把内置测试音频（debug 构建打包在 assets/test/ 下）直接喂给识别器和匹配器，
 * 不需要麦克风即可验证「模型加载 → 识别 → 关键词触发」整条链路。
 *
 * - test/tts_test.wav：Windows 中文 TTS 合成的课堂场景语音（含触发关键词）
 * - test/model_sample.wav：模型官方真人录音样例（验证真实语音识别率）
 */
object SelfTest {

    private const val TAG = "SelfTest"
    const val TEST_WAV = "test/tts_test.wav"
    const val MODEL_SAMPLE_WAV = "test/model_sample.wav"
    const val NAME_TEST_WAV = "test/name_test.wav"

    /** 姓名识别探针用的固定名单与词表（与用户配置无关，保证结果可对比）。 */
    private const val PROBE_NAME = "阳一真"

    data class SectionResult(
        val name: String,
        val recognized: List<String>,
        val triggers: List<TriggerEvent>,
        val error: String? = null,
    )

    fun hasTestWav(assets: AssetManager): Boolean = hasAsset(assets, TEST_WAV)

    private fun hasAsset(assets: AssetManager, path: String): Boolean = runCatching {
        assets.open(path).close()
        true
    }.getOrDefault(false)

    /** 后台线程调用。逐段喂音频并实时匹配，模拟真实流式过程。 */
    fun run(context: Context, prefs: PrefsStore): List<SectionResult> {
        val results = ArrayList<SectionResult>()
        listOf(
            TEST_WAV to "合成语音（TTS，含关键词）",
            MODEL_SAMPLE_WAV to "真人录音样例（官方测试音频）",
        ).forEach { (path, name) ->
            if (hasAsset(context.assets, path)) {
                results.add(runOne(context, prefs, path, name))
            }
        }
        if (hasAsset(context.assets, NAME_TEST_WAV)) {
            results.add(runNameProbe(context))
        }
        return results
    }

    /**
     * 姓名识别探针：同一段含姓名的语音跑两遍——一次带热词偏置、一次不带，
     * 直接量化热词对姓名识别的作用（也用于回归"换配置后是否退化"）。
     *
     * 用固定名单而非用户名单：结果与用户配置无关，便于横向对比。
     */
    private fun runNameProbe(context: Context): SectionResult {
        val name = "姓名识别探针（$PROBE_NAME，带/不带热词对比）"
        return try {
            val pcm = runCatching { readWavAsMono16k(context.assets, NAME_TEST_WAV) }
                .getOrElse { return SectionResult(name, emptyList(), emptyList(), "读取音频失败: ${it.message}") }
            if (pcm.isEmpty()) return SectionResult(name, emptyList(), emptyList(), "测试音频为空")

            val roster = listOf(RosterEntry(PROBE_NAME, isMe = true))
            val specs = listOf(
                KeywordSpec("回答一下", KeywordType.CORE),
                KeywordSpec("找个同学", KeywordType.CORE),
            )
            val pinyin = PinyinIndex.holder(context)

            fun recognize(hotwords: String, score: Float = AsrEngine.DEFAULT_HOTWORDS_SCORE): String {
                val rec = AsrEngine.createRecognizer(context, hotwordsScore = score)
                val stream = rec.createStream(hotwords)
                val sb = StringBuilder()
                var offset = 0
                val chunk = 1600
                while (offset < pcm.size) {
                    val end = minOf(offset + chunk, pcm.size)
                    stream.acceptWaveform(pcm.copyOfRange(offset, end), 16000)
                    offset = end
                    while (rec.isReady(stream)) rec.decode(stream)
                    if (rec.isEndpoint(stream)) {
                        val t = rec.getResult(stream).text
                        if (t.isNotBlank()) sb.append(TextRepair.clean(t))
                        rec.reset(stream)
                    }
                }
                val tail = TextRepair.clean(rec.getResult(stream).text)
                if (tail.isNotBlank()) sb.append(tail)
                rec.release()
                return sb.toString()
            }

            val hotText = AsrEngine.buildHotwordsText(specs, roster)
            val withoutHot = recognize("")
            val withHot = recognize(hotText)
            // 决定性对照：把偏置分拉高到 4 倍。若输出与默认分完全一致，说明热词没有真正
            // 作用于解码（encoding 失败或参数未生效），需要改配置而不是继续调分数。
            val withHotStrong = recognize(hotText, score = 14f)

            // 用与线上一致的匹配链路判断姓名是否命中
            val rosterMatcher = RosterMatcher(
                roster, specs, baseCooldownMillis = 15_000, pinyin = pinyin
            )
            val triggers = ArrayList<TriggerEvent>()
            rosterMatcher.onPartial(withHot, "")?.let { triggers.add(it) }

            val hit = triggers.isNotEmpty()
            val hotActive = withHot != withHotStrong
            val summary = buildString {
                append("无热词：").append(withoutHot.ifBlank { "（无）" })
                append('\n').append("热词 3.5：").append(withHot.ifBlank { "（无）" })
                append('\n').append("热词 14：").append(withHotStrong.ifBlank { "（无）" })
                append('\n').append(
                    if (hotActive) {
                        "→ 热词已作用于解码。注意：人名多为低频字组合（如「臻」），" +
                            "模型可能只给出同音字（「真」「珍」）——读音对了即属识别正确，" +
                            "字形由名单读音纠偏写回真名。"
                    } else {
                        "→ ⚠ 改偏置分输出无变化：热词未生效，需检查热词编码/模型词表"
                    }
                )
                append('\n').append(
                    if (hit) "✓ 姓名命中触发（读音层纠偏生效）" else "✗ 姓名未命中"
                )
            }
            SectionResult(name, listOf(summary), triggers)
        } catch (t: Throwable) {
            Log.e(TAG, "姓名探针失败", t)
            SectionResult(name, emptyList(), emptyList(), "姓名探针失败: ${t.message}")
        }
    }

    private fun runOne(context: Context, prefs: PrefsStore, path: String, name: String): SectionResult {
        return try {
            val pcm = runCatching { readWavAsMono16k(context.assets, path) }
                .getOrElse { return SectionResult(name, emptyList(), emptyList(), "读取测试音频失败: ${it.message}") }
            if (pcm.isEmpty()) return SectionResult(name, emptyList(), emptyList(), "测试音频为空")

            // 转写开启时，自测音频同样入库（debug 演示/验证转写闭环）
            val transcriptRepo = if (prefs.transcriptEnabled) {
                com.classguard.app.data.transcript.TranscriptRepository.get(context)
            } else null
            var sessionId: Long? = null
            if (transcriptRepo != null) {
                sessionId = kotlinx.coroutines.runBlocking {
                    runCatching { transcriptRepo.startSession(System.currentTimeMillis()) }.getOrNull()
                }
            }

            val rec: OnlineRecognizer = AsrEngine.createRecognizer(context)
            val stream = rec.createStream(AsrEngine.buildHotwordsText(prefs.keywordSpecs, prefs.roster))
            val pinyin = PinyinIndex.holder(context)
            val matcher = TriggerMatcher(
                prefs.keywordSpecs, baseCooldownMillis = prefs.cooldownMillis, pinyin = pinyin
            )
            val rosterMatcher = RosterMatcher(
                prefs.roster, prefs.keywordSpecs,
                baseCooldownMillis = prefs.cooldownMillis,
                pinyin = pinyin,
            )
            val finals = ArrayList<String>()
            val triggers = ArrayList<TriggerEvent>()
            var pendingTriggerKeyword: String? = null
            val chunk = 1600 // 100ms
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + chunk, pcm.size)
                stream.acceptWaveform(pcm.copyOfRange(offset, end), 16000)
                offset = end
                while (rec.isReady(stream)) rec.decode(stream)

                val result = rec.getResult(stream)
                val partial = result.text
                if (partial.isNotEmpty()) {
                    val kwEvent = matcher.onPartial(partial)
                    val nameEvent = rosterMatcher.onPartial(partial, matcher.contextSnapshot())
                    val event = listOfNotNull(
                        nameEvent?.takeIf { it.directed },
                        kwEvent,
                        nameEvent,
                    ).firstOrNull()
                    event?.let {
                        val conf = Confidence.withFuzzyPenalty(
                            Confidence.forKeyword(
                                result.tokens.toList(), result.ysProbs, TriggerMatcher.normalize(it.keyword)
                            ),
                            it.fuzzyEdits,
                        )
                        pendingTriggerKeyword = it.keyword
                        Log.i(TAG, "自测触发: ${it.keyword} conf=${conf ?: "n/a"} edits=${it.fuzzyEdits}")
                        triggers.add(
                            it.copy(
                                confidence = conf,
                                utterance = TextRepair.collapseStutter(it.utterance),
                            )
                        )
                    }
                }
                if (rec.isEndpoint(stream)) {
                    val finalText = rec.getResult(stream).text
                    if (finalText.isNotBlank()) {
                        finals.add(TextRepair.clean(rosterMatcher.repairNames(finalText)).trim())
                    }
                    matcher.onFinal(finalText)?.let {
                        pendingTriggerKeyword = it.keyword
                        Log.i(TAG, "自测触发(final): ${it.keyword}")
                        triggers.add(it)
                    }
                    rosterMatcher.onFinal()
                    rec.reset(stream)
                    val sid = sessionId
                    if (sid != null && finalText.isNotBlank()) {
                        val kw = pendingTriggerKeyword
                        kotlinx.coroutines.runBlocking {
                            runCatching {
                                transcriptRepo?.addSegment(
                                    sessionId = sid,
                                    timeMillis = System.currentTimeMillis(),
                                    text = TextRepair.clean(rosterMatcher.repairNames(finalText)).trim(),
                                    isTrigger = kw != null,
                                    keyword = kw,
                                )
                            }
                        }
                        pendingTriggerKeyword = null
                    }
                }
            }
            if (sessionId != null && transcriptRepo != null) {
                kotlinx.coroutines.runBlocking {
                    runCatching { transcriptRepo.endSession(sessionId, System.currentTimeMillis()) }
                }
            }
            SectionResult(name, finals, triggers)
        } catch (t: Throwable) {
            Log.e(TAG, "自测失败", t)
            SectionResult(name, emptyList(), emptyList(), "自测失败: ${t.message}")
        }
    }

    // ------------------------------------------------------------ WAV 解析

    /**
     * 读取 WAV（PCM16），混并声道并线性插值重采样到 16kHz 单声道。
     * 覆盖 22.05k/44.1k/48k 等 Windows TTS 常见输出格式。
     */
    fun readWavAsMono16k(assets: AssetManager, path: String): FloatArray {
        assets.open(path).use { input ->
            val bytes = input.readBytes()

            fun u16LE(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
            fun u32LE(o: Int) = (u16LE(o).toLong() and 0xFFFFFFFFL).toInt() or
                ((u16LE(o + 2).toLong() and 0xFFFFFFFFL).toInt() shl 16)

            require(bytes.size > 44 && bytes.decodeToString(0, 4) == "RIFF") { "不是 RIFF/WAV 文件" }

            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataOffset = -1
            var dataLength = 0

            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = bytes.decodeToString(pos, pos + 4)
                val size = u32LE(pos + 4)
                when (id) {
                    "fmt " -> {
                        channels = u16LE(pos + 8)
                        sampleRate = u32LE(pos + 12)
                        bitsPerSample = u16LE(pos + 22)
                    }
                    "data" -> {
                        dataOffset = pos + 8
                        dataLength = minOf(size, bytes.size - dataOffset)
                    }
                }
                pos += 8 + size + (size and 1) // 对齐到偶数字节
            }
            require(dataOffset > 0) { "缺少 data 块" }
            require(bitsPerSample == 16) { "仅支持 16bit PCM，实际 $bitsPerSample bit" }
            require(channels in 1..2) { "声道数异常: $channels" }

            val frames = dataLength / (2 * channels)
            val mono = FloatArray(frames)
            for (i in 0 until frames) {
                val base = dataOffset + i * 2 * channels
                val s = when (channels) {
                    1 -> ((bytes[base].toInt() and 0xFF) or (bytes[base + 1].toInt() shl 8)).toShort()
                    else -> {
                        val l = ((bytes[base].toInt() and 0xFF) or (bytes[base + 1].toInt() shl 8)).toShort()
                        val r = ((bytes[base + 2].toInt() and 0xFF) or (bytes[base + 3].toInt() shl 8)).toShort()
                        ((l + r) / 2).toShort()
                    }
                }
                mono[i] = s / 32768f
            }
            return resample(mono, sampleRate, 16000)
        }
    }

    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input
        val ratio = fromRate.toDouble() / toRate
        val outLen = (input.size / ratio).roundToInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt()
            val i1 = minOf(i0 + 1, input.size - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }
}
