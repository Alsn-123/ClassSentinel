package com.classguard.app.alert

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.classguard.app.MainActivity
import com.classguard.app.R
import com.classguard.app.recognition.TriggerEvent

/**
 * 触发提醒：顶部悬浮横幅（需"显示在其他应用上层"权限）+ 震动 + 提示音 + 高优先级通知兜底。
 *
 * v2.6 定向提醒醒目化：
 * - 熄屏/锁屏：通知挂 fullScreenIntent → 全屏提醒页（AlertActivity）点亮屏幕弹出
 * - 亮屏：加强横幅 + 闹钟级铃声（比通知音更刺耳，尊重提示音开关）
 * - 重复提醒：定向提醒 8 秒后未处理则再震一次响一次（最多补 1 次）
 *
 * 悬浮窗视图生命周期完全由本对象管理（显示/超时自动移除/替换时先移除旧视图），
 * 视图持有的是应用级 Service Context，不指向 Activity，无实际泄漏。
 */
@SuppressLint("StaticFieldLeak")
object AlertManager {

    private const val TAG = "AlertManager"
    private const val CHANNEL_ALERTS = "alerts"
    private const val NOTIF_ID_ALERT = 2001
    private const val OVERLAY_TIMEOUT_MS = 8_000L
    private const val DIRECTED_REPEAT_DELAY_MS = 8_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null
    private var repeatRunnable: Runnable? = null
    private var alertPlayer: MediaPlayer? = null

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 触发提醒（v2.0 分级 / v2.6 定向醒目化）。
     * @param fullAlert true=高置信/未知置信：悬浮横幅+震动+通知全量；
     *                  false=低置信：仅轻量通知（压降识别错字导致的误触发骚扰）
     * @param event.directed true=点到"我的名字"：全屏提醒（熄屏亮屏弹出）+ 横幅 + 铃声 + 重复提醒
     */
    fun onTrigger(
        context: Context,
        event: TriggerEvent,
        sound: Boolean,
        vibration: Boolean,
        fullAlert: Boolean = true,
    ) {
        if (fullAlert) {
            showOverlay(context, event)
            if (vibration) vibrate(context, directed = event.directed)
            if (event.directed && sound) playLoudAlert(context)
            postNotification(context, event, sound = sound, highPriority = event.directed || sound)
            scheduleDirectedRepeat(context, event, sound, vibration)
        } else {
            postNotification(context, event, sound = false, highPriority = false)
        }
    }

    /**
     * 定向提醒的补响：8 秒后若可能没注意到（横幅已超时消失），再震一次响一次。
     * 仅补 1 次，避免骚扰；用户点了"我知道了"（全屏页）说明已看到，横幅场景无法感知，接受这次补响。
     */
    private fun scheduleDirectedRepeat(
        context: Context,
        event: TriggerEvent,
        sound: Boolean,
        vibration: Boolean,
    ) {
        if (!event.directed) return
        repeatRunnable?.let(mainHandler::removeCallbacks)
        repeatRunnable = Runnable {
            if (vibration) vibrate(context, directed = true)
            if (sound) playLoudAlert(context)
        }.also { mainHandler.postDelayed(it, DIRECTED_REPEAT_DELAY_MS) }
    }

    /**
     * 播放闹钟级提示音（directed 专用）：普通通知音在课堂上不够响。
     * 依次尝试 闹钟铃声 → 电话铃声 → 通知音，播放约 3.5 秒后停止。
     */
    private fun playLoudAlert(context: Context) {
        runCatching {
            stopLoudAlert()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: return
            val player = MediaPlayer()
            player.setDataSource(context, uri)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.prepare()
            player.start()
            alertPlayer = player
            mainHandler.postDelayed({ stopLoudAlert() }, 3_500L)
        }.onFailure { Log.w(TAG, "响铃播放失败", it) }
    }

    private fun stopLoudAlert() {
        alertPlayer?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        alertPlayer = null
    }

    // ---------------------------------------------------------------- 悬浮窗

    @SuppressLint("InflateParams")
    fun showOverlay(context: Context, event: TriggerEvent) {
        if (!canDrawOverlays(context)) {
            Log.i(TAG, "未授予悬浮窗权限，跳过悬浮横幅")
            return
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mainHandler.post {
            try {
                dismissRunnable?.let(mainHandler::removeCallbacks)
                overlayView?.let {
                    runCatching { wm.removeView(it) }
                    overlayView = null
                }

                val view = buildOverlayView(context, event)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (72 * context.resources.displayMetrics.density).toInt()
                    horizontalMargin = if (event.directed) 0.02f else 0.04f
                }

                wm.addView(view, params)
                overlayView = view
                // 定向提醒（点到名字）停留更久，给用户足够的反应时间
                val timeout = if (event.directed) OVERLAY_TIMEOUT_MS * 9 / 4 else OVERLAY_TIMEOUT_MS
                dismissRunnable = Runnable { dismissOverlay(context) }
                    .also { mainHandler.postDelayed(it, timeout) }
            } catch (t: Throwable) {
                Log.w(TAG, "悬浮窗显示失败", t)
            }
        }
    }

    fun dismissOverlay(context: Context, userDismissed: Boolean = false) {
        if (userDismissed) cancelDirectedRepeat()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mainHandler.post {
            overlayView?.let {
                runCatching { wm.removeView(it) }
                overlayView = null
            }
        }
    }

    /** 用户已确认看到提醒（点了横幅或全屏页的"我知道了"），取消补响。 */
    fun cancelDirectedRepeat() {
        repeatRunnable?.let(mainHandler::removeCallbacks)
        repeatRunnable = null
        stopLoudAlert()
    }

    private fun buildOverlayView(context: Context, event: TriggerEvent): LinearLayout {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                // 定向提醒（点到名字）用醒目的深红底
                setColor(if (event.directed) 0xEE5C1010.toInt() else 0xEE1C1C1E.toInt())
                setStroke(dp(1), if (event.directed) 0xFFE57373.toInt() else 0xFF3A3A3C.toInt())
            }
            setOnClickListener { dismissOverlay(context, userDismissed = true) }
        }

        val title = TextView(context).apply {
            text = if (event.directed) {
                context.getString(R.string.alert_directed_title, event.keyword)
            } else {
                context.getString(R.string.alert_title)
            }
            setTextColor(0xFFFFD54F.toInt())
            textSize = if (event.directed) 22f else 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)

        // 题目主位（v2.5）：报警时最先要看的是老师念的题干，放最大字号；
        // 原句识别噪声多（错字/英文碎片），降为辅助信息
        if (event.context.isNotBlank()) {
            val label = TextView(context).apply {
                text = "题目"
                setTextColor(0xFF90A4AE.toInt())
                textSize = if (event.directed) 13f else 11f
            }
            val question = TextView(context).apply {
                text = event.context
                setTextColor(Color.WHITE)
                textSize = if (event.directed) 24f else 18f
                typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(dp(3).toFloat(), 1f)
            }
            root.addView(
                label,
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) },
            )
            root.addView(question)
        }

        if (event.utterance.isNotBlank()) {
            val utterance = TextView(context).apply {
                text = "原话：" + event.utterance
                setTextColor(0xFFCFD8DC.toInt())
                textSize = 13f
                setLineSpacing(dp(1).toFloat(), 1f)
            }
            root.addView(
                utterance,
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) },
            )
        }
        return root
    }

    // ---------------------------------------------------------------- 震动

    private fun vibrate(context: Context, directed: Boolean) {
        runCatching {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (vibrator.hasVibrator()) {
                val pattern = if (directed) longArrayOf(0, 500, 150, 500, 150, 500) else longArrayOf(0, 350, 150, 350)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        }.onFailure { Log.w(TAG, "震动失败", it) }
    }

    // ---------------------------------------------------------------- 通知

    fun createAlertChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.channel_alerts),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alerts_desc)
            enableVibration(false) // 震动由代码控制，便于开关
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            setSound(
                uri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        nm.createNotificationChannel(channel)
    }

    private fun postNotification(context: Context, event: TriggerEvent, sound: Boolean, highPriority: Boolean) {
        createAlertChannel(context)
        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (event.directed) {
            context.getString(R.string.alert_directed_title, event.keyword)
        } else {
            context.getString(R.string.alert_title)
        }
        // 题目优先（v2.5）：通知首行展示题干，原话放展开文本
        val hasQuestion = event.context.isNotBlank()
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(if (hasQuestion) event.context else event.utterance)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (hasQuestion) {
                        buildString {
                            appendLine("题目：" + event.context)
                            if (event.utterance.isNotBlank()) append("原话：" + event.utterance)
                        }
                    } else {
                        event.utterance
                    }
                )
            )
            .setPriority(
                if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openIntent)

        // 定向提醒（v2.6）：熄屏/锁屏时通过全屏意图直接点亮屏幕弹出提醒页。
        // Android 14+ 首次触发时系统会弹授权引导；用户拒绝则自动降级为横幅通知。
        if (event.directed) {
            val fullScreen = PendingIntent.getActivity(
                context, 2025,
                Intent(context, AlertActivity::class.java)
                    .putExtra(AlertActivity.EXTRA_KEYWORD, event.keyword)
                    .putExtra(AlertActivity.EXTRA_QUESTION, event.context)
                    .putExtra(AlertActivity.EXTRA_UTTERANCE, event.utterance)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(fullScreen, true)
        }

        if (!sound) builder.setSilent(true)

        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID_ALERT, builder.build())
        }.onFailure { Log.w(TAG, "通知发送失败", it) }
    }
}
