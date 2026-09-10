package com.classguard.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.classguard.app.MainActivity
import com.classguard.app.R
import com.classguard.app.ServiceBus
import com.classguard.app.alert.AlertManager
import com.classguard.app.data.PrefsStore
import com.classguard.app.data.TriggerRecord
import com.classguard.app.recognition.TriggerEvent
import com.classguard.app.recognition.TriggerMatcher
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import kotlin.concurrent.thread

/**
 * 常驻前台识别服务：
 * 麦克风采集 → sherpa-onnx 流式识别 → 关键词匹配 → 悬浮窗/震动/通知提醒。
 *
 * 由主界面（前台）启动，满足 Android 14+ 对 microphone 类型前台服务的
 * “使用中启动”要求；启动后锁屏、切走其他 App 均持续工作。
 */
class RecognitionService : Service() {

    companion object {
        private const val TAG = "RecognitionService"
        const val ACTION_STOP = "com.classguard.app.action.STOP"
        const val ACTION_APPLY_SETTINGS = "com.classguard.app.action.APPLY_SETTINGS"
        private const val CHANNEL_MONITORING = "monitoring"
        private const val NOTIF_ID_MONITORING = 1

        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1600 // 100ms @16kHz
    }

    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var matcher: TriggerMatcher? = null

    @Volatile
    private var hotwordsText: String = ""

    private lateinit var prefs: PrefsStore

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsStore(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPLY_SETTINGS -> {
                // 界面改了关键词/冷却时间，重建匹配器即时生效
                matcher = TriggerMatcher(prefs.keywords, cooldownMillis = prefs.cooldownMillis)
                return START_STICKY
            }
        }

        val micGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            Log.w(TAG, "缺少麦克风权限，服务退出")
            ServiceBus.setRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        if (!running) begin()
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildMonitoringNotification()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID_MONITORING,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun begin() {
        running = true
        ServiceBus.setRunning(true)
        ServiceBus.setPartial("")
        matcher = TriggerMatcher(prefs.keywords, cooldownMillis = prefs.cooldownMillis)
        acquireWakeLock()

        try {
            hotwordsText = AsrEngine.buildHotwordsText(prefs.keywords)
            Log.i(TAG, "热词 ${hotwordsText.lines().size} 条")
            recognizer = AsrEngine.createRecognizer(this)
        } catch (t: Throwable) {
            AsrEngine.logLoadError(t)
            running = false
            ServiceBus.setRunning(false)
            stopSelf()
            return
        }

        worker = thread(name = "asr-loop") { recognitionLoop() }
        Log.i(TAG, "识别服务已启动")
    }

    private fun recognitionLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = maxOf(minBuf * 4, CHUNK * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        audioRecord = record
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "麦克风初始化失败")
            failAndStop()
            return
        }

        val rec = recognizer ?: return
        val stream = rec.createStream(hotwordsText)
        val shorts = ShortArray(CHUNK)
        val floats = FloatArray(CHUNK)

        // 设备级降噪（多数真机可用，模拟器/部分机型会返回 null，跳过即可）
        var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
        if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
            noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)
            noiseSuppressor?.setEnabled(true)
            Log.i(TAG, "NoiseSuppressor: ${noiseSuppressor != null}")
        }

        try {
            record.startRecording()
            while (running) {
                val n = record.read(shorts, 0, CHUNK)
                if (n <= 0) {
                    Thread.sleep(10)
                    continue
                }
                for (i in 0 until n) floats[i] = shorts[i] / 32768f
                stream.acceptWaveform(floats.copyOf(n), SAMPLE_RATE)

                while (rec.isReady(stream)) {
                    rec.decode(stream)
                }

                val partial = rec.getResult(stream).text
                if (partial.isNotEmpty()) {
                    ServiceBus.setPartial(partial)
                    matcher?.onPartial(partial)?.let { onTrigger(it) }
                }

                if (rec.isEndpoint(stream)) {
                    val finalText = rec.getResult(stream).text
                    matcher?.onFinal(finalText)?.let { onTrigger(it) }
                    rec.reset(stream)
                    ServiceBus.setPartial("")
                }
            }
        } catch (t: Throwable) {
            if (running) Log.e(TAG, "识别循环异常", t)
        } finally {
            runCatching { noiseSuppressor?.release() }
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    private fun onTrigger(event: TriggerEvent) {
        Log.i(TAG, "触发关键词「${event.keyword}」：${event.utterance}")
        prefs.addHistory(TriggerRecord(event.timeMillis, event.keyword, event.utterance, event.context))
        ServiceBus.notifyHistoryChanged()
        ServiceBus.emitAlert(event)
        AlertManager.onTrigger(this, event, prefs.soundEnabled, prefs.vibrationEnabled)
    }

    private fun failAndStop() {
        running = false
        ServiceBus.setRunning(false)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClassSentinel:asr").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    private fun buildMonitoringNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecognitionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_MONITORING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_monitoring_title))
            .setContentText(getString(R.string.notif_monitoring_text))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MONITORING,
                getString(R.string.channel_monitoring),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        // 提醒渠道由 AlertManager 创建
    }

    override fun onDestroy() {
        running = false
        worker?.let { w ->
            runCatching { w.join(1000) }
        }
        worker = null
        audioRecord = null
        recognizer?.release()
        recognizer = null
        matcher = null
        releaseWakeLock()
        ServiceBus.setRunning(false)
        ServiceBus.setPartial("")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "识别服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
