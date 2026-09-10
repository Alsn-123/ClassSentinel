package com.classguard.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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
import com.classguard.app.recognition.Confidence
import com.classguard.app.recognition.PinyinIndex
import com.classguard.app.recognition.TriggerEvent
import com.classguard.app.recognition.TriggerMatcher
import com.classguard.app.recognition.RosterMatcher
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import kotlin.concurrent.thread

/**
 * 常驻前台识别服务：
 * 麦克风采集 → sherpa-onnx 流式识别 → 关键词/名单匹配 → 悬浮窗/震动/通知提醒。
 *
 * 由主界面（前台）启动，满足 Android 14+ 对 microphone 类型前台服务的
 * "使用中启动"要求；启动后锁屏、切走其他 App 均持续工作。
 *
 * v1.2 加固：
 * - startForeground 失败（后台拉活被限）→ 记录错误并优雅退出，不再崩溃；
 * - 模型完整性校验 + 模型加载全部在工作线程，主线程零阻塞；
 * - 日志脱敏：不打印完整识别文本与用户词表，只记关键词与长度；
 * - onTaskRemoved 经 AlarmManager 安排恢复（详见 RestartReceiver）。
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

    @Volatile
    private var starting = false

    private var worker: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var matcher: TriggerMatcher? = null

    @Volatile
    private var rosterMatcher: RosterMatcher? = null

    @Volatile
    private var hotwordsText: String = ""

    /** 界面改了词表/名单后置位，识别循环在下一帧重建 stream 使热词即时生效。 */
    @Volatile
    private var streamRebuildNeeded = false

    private lateinit var prefs: PrefsStore

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsStore(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.monitoringEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPLY_SETTINGS -> {
                applySettingsInPlace()
                return START_STICKY
            }
        }

        val micGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            Log.w(TAG, "缺少麦克风权限，服务退出")
            ServiceBus.setError(getString(R.string.error_no_mic))
            ServiceBus.setRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startAsForeground()) return START_NOT_STICKY
        if (!running && !starting) begin()
        return START_STICKY
    }

    /** 前台化；Android 14+ 后台拉活被限时抛异常 → 记录错误并优雅退出（不崩溃）。 */
    @SuppressLint("InlinedApi") // 常量内联值；ServiceCompat 内部已按 API 版本处理
    private fun startAsForeground(): Boolean {
        val notification = buildMonitoringNotification()
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID_MONITORING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "前台化失败：${t.message}")
            ServiceBus.setError(getString(R.string.error_fgs_start_failed))
            ServiceBus.setRunning(false)
            stopSelf()
            false
        }
    }

    private fun begin() {
        starting = true
        running = true
        prefs.monitoringEnabled = true
        ServiceBus.setRunning(true)
        ServiceBus.setError(null)
        ServiceBus.setPartial("")
        ServiceBus.setModelLoading(true)
        rebuildMatchers()
        hotwordsText = AsrEngine.buildHotwordsText(prefs.keywordSpecs, prefs.roster)
        acquireWakeLock()

        // 模型完整性校验（190MB 哈希）与模型加载都在工作线程，主线程零阻塞
        worker = thread(name = "asr-loop") {
            val integrityProblem = if (prefs.modelIntegrityOk) null else ModelIntegrity.verify(assets)
            if (integrityProblem != null) {
                ServiceBus.setError(integrityProblem)
                failAndStop()
                return@thread
            }
            prefs.modelIntegrityOk = true

            try {
                recognizer = AsrEngine.createRecognizer(this@RecognitionService)
            } catch (t: Throwable) {
                AsrEngine.logLoadError(t)
                ServiceBus.setError(getString(R.string.error_model_load))
                failAndStop()
                return@thread
            }
            ServiceBus.setModelLoading(false)
            recognitionLoop()
        }
        Log.i(TAG, "识别服务启动中（热词 ${hotwordsText.lines().size} 条）")
    }

    /** 词表/名单/冷却变化：原地更新匹配器状态（保留上下文与冷却），并标记重建热词流。 */
    private fun applySettingsInPlace() {
        if (!running) return
        rebuildMatchers()
        hotwordsText = AsrEngine.buildHotwordsText(prefs.keywordSpecs, prefs.roster)
        streamRebuildNeeded = true
        Log.i(TAG, "设置已原地生效，待重建热词流")
    }

    private fun rebuildMatchers() {
        val cooldown = prefs.cooldownMillis
        val specs = prefs.keywordSpecs
        val roster = prefs.roster
        val m = matcher
        if (m == null) {
            matcher = TriggerMatcher(specs, baseCooldownMillis = cooldown)
        } else {
            m.updateKeywordSpecs(specs)
            m.updateBaseCooldown(cooldown)
        }
        val r = rosterMatcher
        if (r == null) {
            rosterMatcher = RosterMatcher(
                roster, specs, baseCooldownMillis = cooldown, pinyin = PinyinIndex.holder(this)
            )
        } else {
            r.updateRoster(roster)
            r.updateSpecs(specs)
            r.updateBaseCooldown(cooldown)
        }
    }

    // RECORD_AUDIO 已在 onStartCommand 入口校验，未授权时服务直接退出
    @SuppressLint("MissingPermission")
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
            ServiceBus.setError(getString(R.string.error_mic_init))
            failAndStop()
            return
        }

        val rec = recognizer ?: return
        var stream = rec.createStream(hotwordsText)
        val shorts = ShortArray(CHUNK)
        val floats = FloatArray(CHUNK)

        // 设备级降噪 + 自动增益（多数真机可用；不可用时 VOICE_RECOGNITION 源自带 HAL 预处理，属预期）
        var noiseSuppressor: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        Log.i(TAG, "音效可用性 NS=${NoiseSuppressor.isAvailable()} AGC=${AutomaticGainControl.isAvailable()}")
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)
            noiseSuppressor?.setEnabled(true)
            Log.i(TAG, "NoiseSuppressor attached: ${noiseSuppressor != null}")
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(record.audioSessionId)
            // 远场小音量场景：开启限制器行为，抬高轻声
            agc?.setEnabled(true)
            Log.i(TAG, "AutomaticGainControl attached: ${agc != null}")
        }

        try {
            record.startRecording()
            while (running) {
                if (streamRebuildNeeded) {
                    streamRebuildNeeded = false
                    stream = rec.createStream(hotwordsText)
                    ServiceBus.setPartial("")
                    Log.i(TAG, "热词流已重建")
                }

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

                val result = rec.getResult(stream)
                val partial = result.text
                if (partial.isNotEmpty()) {
                    ServiceBus.setPartial(partial)
                    pickEvent(partial, matcherContext = matcher?.contextSnapshot().orEmpty())
                        ?.let { onTrigger(it, result.tokens.toList(), result.ysProbs) }
                }

                if (rec.isEndpoint(stream)) {
                    val finalResult = rec.getResult(stream)
                    // onFinal 内部完成"补一次匹配 + 提交上下文"，只会调用一次
                    matcher?.onFinal(finalResult.text)
                        ?.let { onTrigger(it, finalResult.tokens.toList(), finalResult.ysProbs) }
                    rosterMatcher?.onFinal()
                    rec.reset(stream)
                    ServiceBus.setPartial("")
                }
            }
        } catch (t: Throwable) {
            if (running) Log.e(TAG, "识别循环异常", t)
        } finally {
            runCatching { noiseSuppressor?.release() }
            runCatching { agc?.release() }
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    /** 关键词匹配与名单匹配合并：定向提醒 > 关键词 > 普通名单命中。 */
    private fun pickEvent(partial: String, matcherContext: String): TriggerEvent? {
        val keywordEvent = matcher?.onPartial(partial)
        val nameEvent = rosterMatcher?.onPartial(partial, matcherContext)
        return listOfNotNull(
            nameEvent?.takeIf { it.directed },
            keywordEvent,
            nameEvent,
        ).firstOrNull()
    }

    /** 日志脱敏：只记录关键词与句长，不落完整识别文本与用户词表。 */
    private fun onTrigger(event: TriggerEvent, tokens: List<String>, probs: FloatArray) {
        val confidence = Confidence.forKeyword(tokens, probs, TriggerMatcher.normalize(event.keyword))
        val full = event.copy(confidence = confidence)
        Log.i(
            TAG,
            "触发「${event.keyword}」directed=${event.directed} conf=${
                confidence?.let { "%.2f".format(it) } ?: "n/a"
            } len=${event.utterance.length}",
        )
        prefs.addHistory(
            TriggerRecord(
                timeMillis = event.timeMillis,
                keyword = event.keyword,
                utterance = event.utterance,
                context = event.context,
                confidence = confidence,
                directed = event.directed,
            )
        )
        ServiceBus.notifyHistoryChanged()
        ServiceBus.emitAlert(full)
        AlertManager.onTrigger(
            this,
            full,
            sound = prefs.soundEnabled,
            vibration = prefs.vibrationEnabled,
            fullAlert = confidence == null || confidence >= Confidence.HIGH_THRESHOLD,
        )
    }

    private fun failAndStop() {
        running = false
        starting = false
        ServiceBus.setRunning(false)
        ServiceBus.setModelLoading(false)
        stopSelf()
    }

    // 刻意不设超时：WakeLock 与服务生命周期一致（课堂全程数小时），onDestroy 必然释放；
    // 设短超时反而会导致长课堂中捕获中断
    @SuppressLint("WakelockTimeout")
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
    }

    /** 用户划走任务：安排 3 秒后尝试恢复（见 RestartReceiver，不申请开机自启）。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs.monitoringEnabled && running) {
            RestartReceiver.schedule(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        starting = false
        worker?.let { w -> runCatching { w.join(1000) } }
        worker = null
        audioRecord = null
        recognizer?.release()
        recognizer = null
        matcher = null
        rosterMatcher = null
        releaseWakeLock()
        ServiceBus.setRunning(false)
        ServiceBus.setModelLoading(false)
        ServiceBus.setPartial("")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "识别服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
