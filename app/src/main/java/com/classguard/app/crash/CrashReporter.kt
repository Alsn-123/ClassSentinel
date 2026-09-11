package com.classguard.app.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 本地崩溃捕获（v1.2 门禁 2.7 补齐项）。
 *
 * 崩溃时把时间/版本/线程/堆栈写入应用私有目录（不含任何识别内容），
 * 滚动保留最近 [MAX_FILES] 份；下次启动由界面提示并可复制。
 * 记录完成后交回系统默认处理器，保持系统崩溃流程不变。
 */
object CrashReporter {

    private const val DIR = "crash"
    private const val MAX_FILES = 5

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun crashDir(context: Context): File = File(context.applicationContext.filesDir, DIR)

    fun pendingCrashFiles(context: Context): List<File> =
        crashDir(context).listFiles { f -> f.isFile && f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            .orEmpty()

    fun clearAll(context: Context) {
        pendingCrashFiles(context).forEach { it.delete() }
    }

    /** 纯函数：生成崩溃报告文本（单测覆盖）。 */
    fun format(timeMillis: Long, versionName: String?, threadName: String, stackTrace: String): String =
        buildString {
            appendLine("ClassSentinel 崩溃报告")
            appendLine("time: $timeMillis")
            appendLine("version: ${versionName ?: "?"} (${if (Build.VERSION.SDK_INT >= 30) Build.VERSION.RELEASE else "?"}, API ${Build.VERSION.SDK_INT})")
            appendLine("thread: $threadName")
            appendLine()
            append(stackTrace)
        }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context)
        dir.mkdirs()
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val file = File(dir, "crash-${System.currentTimeMillis()}.txt")
        file.writeText(format(System.currentTimeMillis(), versionName, thread.name, sw.toString()))
        // 滚动：只保留最近 MAX_FILES 份
        pendingCrashFiles(context).drop(MAX_FILES).forEach { it.delete() }
    }
}
