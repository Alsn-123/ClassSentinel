package com.classguard.app.service

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.security.MessageDigest

/**
 * 模型完整性校验（v1.2）：固化四个模型文件的 SHA-256 与字节数，
 * 首次启动校验并缓存结果；损坏/缺失时给出明确错误与指引，而不是只在
 * logcat 留下一行加载失败。
 */
object ModelIntegrity {

    private const val TAG = "ModelIntegrity"

    private data class Expectation(val path: String, val sha256: String, val sizeBytes: Long)

    // 与 assets 模型目录一一对应；换模型时必须同步更新此表
    private val EXPECTED_BILINGUAL = listOf(
        Expectation(
            "asr-model/" + AsrEngine.ENCODER,
            "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b",
            181_895_032L,
        ),
        Expectation(
            "asr-model/" + AsrEngine.DECODER,
            "1a70c593d71e53f023f5f55b0b4cfff5055abb786ee3992e5f63dc2e273cc4fa",
            13_091_040L,
        ),
        Expectation(
            "asr-model/" + AsrEngine.JOINER,
            "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0",
            3_228_404L,
        ),
        Expectation(
            "asr-model/" + AsrEngine.TOKENS,
            "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3",
            56_317L,
        ),
    )

    /** 轻量中文模型（sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23，int8 四件套）。 */
    private val EXPECTED_ZH_LIGHT = listOf(
        Expectation(
            "asr-model-zh/" + AsrEngine.ENCODER,
            "1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1",
            21_621_684L,
        ),
        Expectation(
            "asr-model-zh/" + AsrEngine.DECODER,
            "22f123bb8cba9b38974b3df18a3f45e7081f4985ebb2e075d9f21f618c468bbf",
            1_888_682L,
        ),
        Expectation(
            "asr-model-zh/" + AsrEngine.JOINER,
            "a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169",
            1_795_562L,
        ),
        Expectation(
            "asr-model-zh/" + AsrEngine.TOKENS,
            "8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac",
            48_697L,
        ),
    )

    private fun expectationsFor(modelId: String): List<Expectation> =
        if (modelId == com.classguard.app.data.PrefsStore.Companion.AsrModelIds.ZH_LIGHT) {
            EXPECTED_ZH_LIGHT
        } else {
            EXPECTED_BILINGUAL
        }

    /**
     * 校验所选模型的全部文件（大小 + SHA-256）。
     * @return null 表示通过；否则返回面向用户的错误描述。
     */
    fun verify(assets: AssetManager, modelId: String): String? {
        for (exp in expectationsFor(modelId)) {
            val problem = verifyOne(assets, exp)
            if (problem != null) {
                Log.e(TAG, "模型校验失败: $problem")
                return problem
            }
        }
        return null
    }

    /** 首次校验通过后缓存结果（按模型记忆），后续启动不再重复哈希模型文件。 */
    fun isVerifiedAndCached(context: Context, assets: AssetManager, modelId: String): Boolean {
        val prefs = com.classguard.app.data.PrefsStore(context)
        if (prefs.modelIntegrityOk && prefs.modelIntegrityModel == modelId) return true
        val problem = verify(assets, modelId)
        if (problem == null) {
            prefs.modelIntegrityOk = true
            prefs.modelIntegrityModel = modelId
        } else {
            prefs.modelIntegrityOk = false
        }
        return problem == null
    }

    fun resetCache(context: Context) {
        val prefs = com.classguard.app.data.PrefsStore(context)
        prefs.modelIntegrityOk = false
        prefs.modelIntegrityModel = ""
    }

    private fun verifyOne(assets: AssetManager, exp: Expectation): String? {
        return try {
            assets.open(exp.path).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) {
                        digest.update(buffer, 0, n)
                        total += n
                    }
                }
                if (total != exp.sizeBytes) {
                    return "${exp.path} 大小异常（$total ≠ ${exp.sizeBytes}），安装包可能不完整"
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(exp.sha256, ignoreCase = true)) {
                    return "${exp.path} 校验失败，安装包可能被改动，请重新安装完整版"
                }
                null
            }
        } catch (t: Throwable) {
            "${exp.path} 缺失或无法读取（${t.message}），请重新安装完整版"
        }
    }
}
