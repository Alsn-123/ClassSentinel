package com.classguard.app.alert

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.classguard.app.R

/**
 * 定向提醒全屏页（v2.6）：点到"我的名字"时最高优先级的呈现。
 *
 * 设计目标只有一个字：醒。
 * - 熄屏/锁屏时通过 fullScreenIntent 直接亮屏弹出（setShowWhenLocked + setTurnScreenOn）
 * - 全屏深红背景，姓名超大字号居中，题目紧随其后
 * - 12 秒自动关闭；点击"我知道了"立即关闭
 *
 * 触发路径：AlertManager 的 directed 通知 setFullScreenIntent → 本页。
 * 亮屏状态下横幅 + 铃声已足够醒目，通知仍会以 heads-up 形式出现，本页不重复弹出。
 */
class AlertActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁屏可见 + 亮屏（API 27+ 新 API；minSdk 26 用旧 flag 兜底）
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val keyword = intent.getStringExtra(EXTRA_KEYWORD) ?: "你"
        val question = intent.getStringExtra(EXTRA_QUESTION).orEmpty()
        val utterance = intent.getStringExtra(EXTRA_UTTERANCE).orEmpty()

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(40))
            setBackgroundColor(0xF2B71C1C.toInt())
        }

        val icon = TextView(this).apply {
            text = "🎯"
            textSize = 56f
            gravity = android.view.Gravity.CENTER
        }

        val title = TextView(this).apply {
            text = "点到你了！"
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFD54F.toInt())
            gravity = android.view.Gravity.CENTER
        }

        val name = TextView(this).apply {
            text = keyword
            textSize = 52f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
        }

        val questionLabel = TextView(this).apply {
            text = "题目"
            textSize = 14f
            setTextColor(0xFFFFCC80.toInt())
            gravity = android.view.Gravity.CENTER
        }

        val questionText = TextView(this).apply {
            text = question.ifBlank { "（识别中，注意听老师说话）" }
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }

        val utteranceText = TextView(this).apply {
            text = if (utterance.isBlank()) "" else "原话：$utterance"
            textSize = 14f
            setTextColor(0xFFFFCDD2.toInt())
            gravity = android.view.Gravity.CENTER
        }

        val dismiss = Button(this).apply {
            text = "我知道了"
            textSize = 18f
            setOnClickListener {
                AlertManager.cancelDirectedRepeat()
                finish()
            }
        }

        root.addView(icon)
        root.addView(title)
        root.addView(name, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        root.addView(
            questionLabel,
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) },
        )
        root.addView(questionText)
        if (utterance.isNotBlank()) {
            root.addView(
                utteranceText,
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) },
            )
        }
        root.addView(
            dismiss,
            LinearLayout.LayoutParams(-2, dp(56)).apply { topMargin = dp(36) },
        )
        setContentView(root)

        // 自动关闭，避免一直霸屏
        handler.postDelayed({ finish() }, AUTO_DISMISS_MILLIS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        AlertManager.cancelDirectedRepeat()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KEYWORD = "keyword"
        const val EXTRA_QUESTION = "question"
        const val EXTRA_UTTERANCE = "utterance"
        const val AUTO_DISMISS_MILLIS = 12_000L
    }
}
