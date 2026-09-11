package com.classguard.app.service

import android.content.Context
import android.util.Log
import com.classguard.app.recognition.KeywordSpec
import com.classguard.app.recognition.KeywordType
import com.classguard.app.recognition.RosterEntry
import com.classguard.app.recognition.TriggerMatcher
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * 识别器工厂。模型文件随 APK 内置在 assets/asr-model/ 下，
 * 由 sherpa-onnx 从 assets 加载，推理全部在本地完成，不访问网络。
 *
 * 准确率相关：
 * - modified_beam_search 解码 + 热词偏置：把关键词表转成热词字符串，
 *   经 createStream(hotwords) 传入解码器做上下文偏置，显著提高
 *   "找个同学/回答一下"等触发词在远场噪声下被正确识别的概率。
 *   注意：不能用 hotwordsFile——assets 模式下 native 端会尝试用
 *   AssetManager 读绝对路径导致 abort（sherpa-onnx issue #2562）。
 * - 热词在每次开始监听（重新创建 stream）时生效。
 */
object AsrEngine {

    private const val TAG = "AsrEngine"

    const val ENCODER = "asr-model/encoder-int8.onnx"
    const val DECODER = "asr-model/decoder-int8.onnx"
    const val JOINER = "asr-model/joiner-int8.onnx"
    const val TOKENS = "asr-model/tokens.txt"

    /**
     * 热词偏置分（v2.3 由 2.0 提到 3.5）。
     *
     * 命中率低的主因之一是解码器不知道"这些词才可能出现"：教师念"阳一真"，声学上
     * 与"阳丽真"接近，无偏置时模型会挑更常见的"阳丽真"。提高偏置分让解码器在
     * 声学相近时优先输出词表里的名字，比事后纠错更有效（纠错只是兜底）。
     * 过高（>5）会压制声学证据、把无关语句硬拉成热词，故取 3.5。
     */
    private const val HOTWORDS_SCORE = 3.5f

    // 端点检测参数（针对老师讲课停顿多的远场场景调优；改动需真机 A/B）：
    // rule2 从 0.8s 放宽到 1.2s、最小语句 2.4s→3.0s：减少"中途停顿被误判为句末"
    // 导致一句话被拆碎、关键词被切到不同句而漏触发的问题；
    // rule1 2.4s→2.2s：略提升短句 final 的实时性。
    private const val ENDPOINT_RULE1_TRAILING_SILENCE = 2.2f
    private const val ENDPOINT_RULE2_TRAILING_SILENCE = 1.2f
    private const val ENDPOINT_RULE2_MIN_UTTERANCE = 3.0f
    private const val ENDPOINT_RULE3_MAX_UTTERANCE = 20f

    /**
     * 关键词与名单 → sherpa-onnx 热词字符串：一行一个词，
     * 核心词 + 语境词 + 名单全部变体（排除词不进热词）；
     * 纯中文按字空格分隔（与 tokens.txt 的字级建模单元对应）；
     * 含字母/数字的词无法稳定映射到建模单元，跳过（触发匹配仍由文本层兜底）。
     */
    fun buildHotwordsText(specs: List<KeywordSpec>, roster: List<RosterEntry>): String {
        val texts = specs.map { it.text } + roster.flatMap { it.allTexts() }
        return texts
            .map { TriggerMatcher.normalize(it) }
            .filter { it.isNotEmpty() && it.all { ch -> ch.code in 0x4E00..0x9FFF } }
            .distinct()
            .joinToString("\n") { it.toCharArray().joinToString(" ") }
    }

    fun createRecognizer(context: Context): OnlineRecognizer =
        OnlineRecognizer(
            assetManager = context.assets,
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = ENCODER,
                        decoder = DECODER,
                        joiner = JOINER,
                    ),
                    tokens = TOKENS,
                    numThreads = 2,
                    debug = false,
                    // 热词按字级单元编码（词表里中文均为单字 token）；
                    // 不设此项，native 端会拒绝热词并静默失效
                    modelingUnit = "cjkchar",
                ),
                enableEndpoint = true,
                // 端点规则（常量含义见上方注释）
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = ENDPOINT_RULE1_TRAILING_SILENCE,
                        minUtteranceLength = 0f,
                    ),
                    rule2 = EndpointRule(
                        mustContainNonSilence = true,
                        minTrailingSilence = ENDPOINT_RULE2_TRAILING_SILENCE,
                        minUtteranceLength = ENDPOINT_RULE2_MIN_UTTERANCE,
                    ),
                    rule3 = EndpointRule(
                        mustContainNonSilence = false,
                        minTrailingSilence = 0f,
                        minUtteranceLength = ENDPOINT_RULE3_MAX_UTTERANCE,
                    ),
                ),
                // 热词偏置要求 modified_beam_search；分数作用于 createStream 传入的热词
                decodingMethod = "modified_beam_search",
                maxActivePaths = 4,
                hotwordsScore = HOTWORDS_SCORE,
            ),
        )

    /** assets 里是否已经放好模型（用于自测按钮的显隐）。 */
    fun modelPresent(assets: android.content.res.AssetManager): Boolean = runCatching {
        assets.open(TOKENS).use { it.read() >= 0 }
    }.getOrDefault(false)

    fun logLoadError(t: Throwable) {
        Log.e(TAG, "模型加载失败，请检查 assets/asr-model", t)
    }
}
