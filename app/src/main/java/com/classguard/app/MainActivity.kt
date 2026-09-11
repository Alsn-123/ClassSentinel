package com.classguard.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import com.classguard.app.alert.AlertManager
import com.classguard.app.ai.AiConfigStore
import com.classguard.app.ai.OpenAiCompatibleProvider
import com.classguard.app.crash.CrashReporter
import com.classguard.app.data.PrefsStore
import com.classguard.app.data.TriggerRecord
import com.classguard.app.recognition.KeywordSpec
import com.classguard.app.recognition.KeywordType
import com.classguard.app.recognition.RosterCodec
import com.classguard.app.recognition.TriggerEvent
import com.classguard.app.selftest.SelfTest
import com.classguard.app.service.RecognitionService
import com.classguard.app.tile.ClassSentinelTileService
import com.classguard.app.transcript.TranscriptActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    lateinit var prefs: PrefsStore
        private set

    /** onResume 时 +1，驱动界面重新读取权限状态。 */
    val permissionTick = mutableIntStateOf(0)

    /** 磁贴点击的"自动开始监听"请求。 */
    private var pendingAutoStart = false

    /** 上次异常退出的崩溃报告（界面弹窗展示后清除）。 */
    var pendingCrashReport: String? by mutableStateOf(null)

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startMonitoring()
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(this)
        prefs = PrefsStore(this)
        AlertManager.createAlertChannel(this)
        maybeAutoStart(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(activity = this)
                }
            }
        }
        pendingCrashReport = runCatching {
            CrashReporter.pendingCrashFiles(this).firstOrNull()?.readText()
        }.getOrNull()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeAutoStart(intent)
    }

    /** 快捷磁贴降级路径：拉起主界面后自动开始监听。 */
    private fun maybeAutoStart(intent: Intent?) {
        if (intent?.getBooleanExtra(ClassSentinelTileService.EXTRA_AUTO_START, false) == true) {
            pendingAutoStart = true
        }
        if (pendingAutoStart && !ServiceBus.running.value) {
            pendingAutoStart = false
            if (hasMicPermission()) startMonitoring()
        }
    }

    override fun onResume() {
        super.onResume()
        permissionTick.intValue += 1
    }

    // ------------------------------------------------------ 权限

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotifPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun canDrawOverlays(): Boolean = AlertManager.canDrawOverlays(this)

    fun requestMicPermission() {
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    fun openAutoStartSettings() {
        startActivity(RomGuides.autoStartIntent(this))
    }

    fun ignoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    // 侧载课堂工具的合理使用场景（非 Play 渠道分发），且可随时在系统设置撤销
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    // ------------------------------------------------------ 启停与设置

    fun toggleMonitoring() {
        if (ServiceBus.running.value) {
            stopService(Intent(this, RecognitionService::class.java))
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        if (!hasMicPermission()) {
            requestMicPermission()
            return
        }
        if (!hasNotifPermission()) requestNotifPermission()
        ContextCompat.startForegroundService(
            this, Intent(this, RecognitionService::class.java)
        )
    }

    /** 设置变化后通知运行中的服务原地生效。 */
    fun notifySettingsChanged() {
        if (ServiceBus.running.value) {
            startService(
                Intent(this, RecognitionService::class.java)
                    .setAction(RecognitionService.ACTION_APPLY_SETTINGS)
            )
        }
    }

    fun testAlert(directed: Boolean) {
        AlertManager.onTrigger(
            this,
            TriggerEvent(
                timeMillis = System.currentTimeMillis(),
                keyword = if (directed) "张三" else "测试",
                utterance = if (directed) {
                    "（演示）张三，你来回答一下这道题。"
                } else {
                    "（演示）同学们，下面这道题，我找个同学来回答一下。"
                },
                context = "这道题考的是第三章的内容，大家先看两分钟。",
                directed = directed,
            ),
            sound = prefs.soundEnabled,
            vibration = prefs.vibrationEnabled,
        )
    }
}

// ============================================================ 界面

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppScreen(activity: MainActivity) {
    val prefs = remember { activity.prefs }

    val running by ServiceBus.running.collectAsState()
    val modelLoading by ServiceBus.modelLoading.collectAsState()
    val errorMessage by ServiceBus.error.collectAsState()
    val partial by ServiceBus.partialText.collectAsState()
    val historyVersion by ServiceBus.historyVersion.collectAsState()

    var specs by remember { mutableStateOf(prefs.keywordSpecs) }
    var roster by remember { mutableStateOf(prefs.roster) }
    var cooldownSeconds by remember { mutableIntStateOf((prefs.cooldownMillis / 1000).toInt()) }
    var soundOn by remember { mutableStateOf(prefs.soundEnabled) }
    var vibrateOn by remember { mutableStateOf(prefs.vibrationEnabled) }
    var history by remember { mutableStateOf(prefs.history()) }

    var newKeyword by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf(KeywordType.CORE) }
    var newName by remember { mutableStateOf("") }
    var showImportDialog by remember { mutableStateOf(false) }
    var selfTestRunning by remember { mutableStateOf(false) }
    var selfTestResult by remember { mutableStateOf<String?>(null) }

    var transcriptOn by remember { mutableStateOf(prefs.transcriptEnabled) }
    var showExperimental by remember { mutableStateOf(false) }
    val aiStore = remember { AiConfigStore(activity) }
    val aiInitial = remember { aiStore.load() }
    var aiEnabled by remember { mutableStateOf(aiInitial.enabled) }
    var aiBaseUrl by remember { mutableStateOf(aiInitial.baseUrl) }
    var aiModel by remember { mutableStateOf(aiInitial.model) }
    var aiKey by remember { mutableStateOf("") }
    var aiTesting by remember { mutableStateOf(false) }
    var aiTestResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(historyVersion) { history = prefs.history() }

    val tick = activity.permissionTick.intValue
    val micGranted = remember(tick) { activity.hasMicPermission() }
    val notifGranted = remember(tick) { activity.hasNotifPermission() }
    val overlayGranted = remember(tick) { activity.canDrawOverlays() }
    val batteryExempt = remember(tick) { activity.ignoringBatteryOptimizations() }
    // 自测音频已随 main/assets 打进所有构建（v2.3），release 包同样可用自测；
    // 仅以"资源是否存在"判定，不再用 BuildConfig.DEBUG 屏蔽（否则正式版没有自测入口）
    val hasTestWav = remember { SelfTest.hasTestWav(activity.assets) }
    val romSteps = remember { RomGuides.steps() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 标题
        Column {
            Text("课堂哨兵", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "本地离线识别 · 不联网 · 不上传任何音频",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 错误横幅（v1.2 错误可见性）
        errorMessage?.let { err ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    "⚠ $err",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 13.sp,
                )
            }
        }

        // 状态与启停
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running && modelLoading) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("模型加载中…", fontWeight = FontWeight.SemiBold)
                    } else {
                        Text(
                            if (running) "● 监听中" else "○ 已停止",
                            fontWeight = FontWeight.SemiBold,
                            color = if (running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (running && !modelLoading) {
                    Text(
                        if (partial.isEmpty()) "（正在听…说句话试试）" else "正在听：$partial",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                }
                Button(
                    onClick = { activity.toggleMonitoring() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (running) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text(if (running) "停止监听" else "开始监听", fontSize = 17.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { activity.testAlert(directed = false) }) { Text("测试提醒") }
                    TextButton(onClick = { activity.testAlert(directed = true) }) { Text("测试定向提醒") }
                    if (hasTestWav) {
                        TextButton(
                            enabled = !selfTestRunning,
                            onClick = {
                                selfTestRunning = true
                                activity.lifecycleScope.launch {
                                    val results = withContext(Dispatchers.Default) {
                                        SelfTest.run(activity, prefs)
                                    }
                                    selfTestRunning = false
                                    results.forEach { r ->
                                        r.triggers.forEach {
                                            AlertManager.onTrigger(
                                                activity, it, prefs.soundEnabled, prefs.vibrationEnabled,
                                            )
                                        }
                                    }
                                    selfTestResult = buildString {
                                        results.forEach { r ->
                                            appendLine("【${r.name}】")
                                            appendLine("识别结果：")
                                            if (r.recognized.isEmpty()) appendLine("（无）")
                                            r.recognized.forEach { appendLine("· $it") }
                                            appendLine(
                                                "触发关键词：" + if (r.triggers.isEmpty()) "（无）"
                                                else r.triggers.joinToString("、") { it.keyword }
                                            )
                                            r.error?.let { appendLine("错误：$it") }
                                            appendLine()
                                        }
                                    }
                                }
                            },
                        ) {
                            Text(if (selfTestRunning) "自测中…" else "语音自测")
                        }
                    }
                }
            }
        }

        // 权限引导
        SectionCard("权限检查") {
            PermissionRow(
                "麦克风", "识别老师声音的必要权限", micGranted
            ) { activity.requestMicPermission() }
            PermissionRow(
                "悬浮窗", "在其他应用上层弹出提醒横幅", overlayGranted
            ) { activity.openOverlaySettings() }
            PermissionRow(
                "通知", "Android 13+ 需要授权才能发提醒通知", notifGranted
            ) { activity.requestNotifPermission() }
            PermissionRow(
                "电池优化白名单", "防止系统休眠时杀掉后台监听", batteryExempt
            ) { activity.requestIgnoreBatteryOptimizations() }
            // 自启动（无法探测授权状态，固定展示入口）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自启动 / 后台管理", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "点按直接跳转${RomGuides.detectVendor().label}的设置页",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { activity.openAutoStartSettings() }) { Text("去设置") }
            }
        }

        // 提醒设置
        SectionCard("提醒设置") {
            Text("冷却时间：${cooldownSeconds} 秒", fontSize = 14.sp)
            Text(
                "同一关键词触发后的静默期（短词自动延长、长词自动减半）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = cooldownSeconds.toFloat(),
                onValueChange = { cooldownSeconds = it.toInt() },
                onValueChangeFinished = {
                    prefs.cooldownMillis = cooldownSeconds * 1000L
                    activity.notifySettingsChanged()
                },
                valueRange = 5f..60f,
            )
            SettingSwitch("提示音", soundOn) {
                soundOn = it; prefs.soundEnabled = it
            }
            SettingSwitch("震动", vibrateOn) {
                vibrateOn = it; prefs.vibrationEnabled = it
            }
        }

        // 课堂记录（v2.2 转写）
        SectionCard("课堂记录（转写）") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("转写课堂内容", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "把识别到的每句话存到本机，可回看/复制/分享；触发提醒的句子会标 ▶。默认关闭。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = transcriptOn, onCheckedChange = {
                    transcriptOn = it
                    prefs.transcriptEnabled = it
                })
            }
            TextButton(onClick = { activity.startActivity(Intent(activity, TranscriptActivity::class.java)) }) {
                Text("查看课堂记录")
            }
        }

        // 三类关键词
        SectionCard("触发关键词") {
            Text(
                "核心词命中即可能提醒；语境词非空时需 核心词+语境词 同时命中（可跨句）；排除词屏蔽整句。清空语境词即回到只按核心词提醒。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newKeyword,
                    onValueChange = { newKeyword = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入关键词") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val kw = newKeyword.trim()
                        if (kw.isNotEmpty() && specs.none { it.text == kw }) {
                            specs = specs + KeywordSpec(kw, newType)
                            prefs.keywordSpecs = specs
                            activity.notifySettingsChanged()
                        }
                        newKeyword = ""
                    },
                ) { Icon(Icons.Default.Add, contentDescription = "添加关键词") }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KeywordType.entries.forEach { t ->
                    FilterChip(
                        selected = newType == t,
                        onClick = { newType = t },
                        label = { Text(typeLabel(t)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                specs.forEach { spec ->
                    AssistChip(
                        onClick = {
                            specs = specs - spec
                            prefs.keywordSpecs = specs
                            activity.notifySettingsChanged()
                        },
                        label = { Text("${typeLabel(spec.type)}·${spec.text}") },
                        trailingIcon = {
                            Icon(Icons.Default.Close, contentDescription = "删除", Modifier.height(14.dp))
                        },
                    )
                }
            }
            TextButton(onClick = {
                specs = PrefsStore.defaultSpecs()
                prefs.keywordSpecs = specs
                activity.notifySettingsChanged()
            }) { Text("恢复默认词表") }
        }

        // 名单（v2.0）
        SectionCard("名单（听到名字就提醒）") {
            Text(
                "老师点到名字即提醒；标记⭐的名字走专属强提醒。同音字自动匹配" +
                    "（如「张三」被识别成「章三」也能触发），无需手动录入错字。" +
                    "每行一条可用 / 分隔别名，如：张三/老张",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("姓名或别名") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty() && roster.none { it.displayName == name }) {
                            roster = roster + com.classguard.app.recognition.RosterEntry(displayName = name)
                            prefs.roster = roster
                            activity.notifySettingsChanged()
                        }
                        newName = ""
                    },
                ) { Icon(Icons.Default.Add, contentDescription = "添加名单") }
            }
            TextButton(onClick = { showImportDialog = true }) { Text("批量导入") }
            roster.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (entry.isMe) "⭐ " else "") + entry.displayName,
                            fontSize = 14.sp,
                            fontWeight = if (entry.isMe) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        val extra = (entry.aliases + entry.asrVariants).joinToString("、")
                        if (extra.isNotEmpty()) {
                            Text(
                                "别名/变体：$extra",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = {
                        roster = roster.map {
                            if (it == entry) it.copy(isMe = !it.isMe) else it.copy(isMe = false)
                        }
                        prefs.roster = roster
                        activity.notifySettingsChanged()
                    }) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "标记为我的名字",
                            tint = if (entry.isMe) Color(0xFFFFB300) else Color(0xFFCCCCCC),
                        )
                    }
                    IconButton(onClick = {
                        roster = roster - entry
                        prefs.roster = roster
                        activity.notifySettingsChanged()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            }
        }

        // 历史
        SectionCard("触发历史") {
            if (history.isEmpty()) {
                Text("暂无记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                history.forEach { record ->
                    HistoryRow(record)
                }
                TextButton(onClick = {
                    prefs.clearHistory()
                    history = emptyList()
                }) { Text("清空历史") }
            }
        }

        // 使用提示（按检测到的 ROM 动态渲染）
        SectionCard("使用提示") {
            TipLine("手机平放桌面，尽量靠近讲台方向；距离越近识别越准。")
            TipLine("开始监听后可锁屏或切换其他 App，状态栏会保留常驻通知。")
            TipLine("建议上课时插电，持续识别有一定耗电。")
            romSteps.forEach { TipLine(it) }
            TipLine("低置信度命中只发轻量通知，避免识别错字造成的误提醒打扰。")
            TipLine("下拉快捷面板可把「课堂哨兵」磁贴加到首页，一键开/关监听。")
        }

        // 实验功能（v2.2 AI 口子：默认折叠、默认关闭）
        TextButton(onClick = { showExperimental = !showExperimental }) {
            Text(if (showExperimental) "收起实验功能" else "实验功能 ▾", fontSize = 12.sp)
        }
        if (showExperimental) {
            SectionCard("实验性 · AI 答题口子") {
                Text(
                    "默认关闭。核心识别链路永不联网；仅当你在此显式启用并配置自己的接口后，" +
                        "对应模块才会发起网络请求。接口需兼容 OpenAI /chat/completions。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitch("启用（当前版本无任何自动调用）", aiEnabled) { aiEnabled = it }
                OutlinedTextField(
                    value = aiBaseUrl,
                    onValueChange = { aiBaseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("接口地址，如 https://api.example.com/v1") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = aiModel,
                    onValueChange = { aiModel = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("模型名，如 gpt-4o-mini") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = aiKey,
                    onValueChange = { aiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("API Key（Keystore 加密保存；留空保留旧值）") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        aiStore.save(
                            com.classguard.app.ai.AiConfig(
                                enabled = aiEnabled,
                                baseUrl = aiBaseUrl,
                                model = aiModel,
                                apiKeyCipher = aiInitial.apiKeyCipher,
                            ),
                            plainApiKey = aiKey.takeIf { it.isNotBlank() },
                        )
                        Toast.makeText(activity, "已保存（Key 已加密）", Toast.LENGTH_SHORT).show()
                    }) { Text("保存") }
                    TextButton(
                        enabled = !aiTesting && aiBaseUrl.isNotBlank(),
                        onClick = {
                            aiTesting = true
                            activity.lifecycleScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    val cfg = aiStore.load()
                                    val key = aiStore.plainApiKey(cfg) ?: ""
                                    OpenAiCompatibleProvider(cfg.baseUrl, key, cfg.model.ifBlank { "gpt-4o-mini" })
                                        .answer("回复：OK")
                                }
                                aiTesting = false
                                aiTestResult = result.fold(
                                    onSuccess = { "连接成功：${it.take(50)}" },
                                    onFailure = { "失败：${it.message?.take(120)}" },
                                )
                            }
                        },
                    ) { Text(if (aiTesting) "测试中…" else "测试连接") }
                }
                aiTestResult?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showImportDialog) {
        var importText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = RosterCodec.parseImportText(importText)
                    if (parsed.isNotEmpty()) {
                        val existing = roster.map { it.displayName }.toSet()
                        roster = roster + parsed.filter { it.displayName !in existing }
                        prefs.roster = roster
                        activity.notifySettingsChanged()
                    }
                    showImportDialog = false
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("取消") }
            },
            title = { Text("批量导入名单") },
            text = {
                Column {
                    Text("每行一条，用 / 分隔别名：\n张三/老张\n李四", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    )
                }
            },
        )
    }

    selfTestResult?.let { text ->
        AlertDialog(
            onDismissRequest = { selfTestResult = null },
            confirmButton = {
                TextButton(onClick = { selfTestResult = null }) { Text("好的") }
            },
            title = { Text("语音自测结果") },
            text = {
                Column(
                    Modifier
                        .height(320.dp)
                        .verticalScroll(rememberScrollState())
                ) { Text(text, fontSize = 13.sp) }
            },
        )
    }

    // 崩溃捕获（v2.2）：上次异常退出的本机日志
    activity.pendingCrashReport?.let { report ->
        AlertDialog(
            onDismissRequest = {
                activity.pendingCrashReport = null
                CrashReporter.clearAll(activity)
            },
            title = { Text("检测到上次异常退出") },
            text = {
                Text(
                    "已在本机生成崩溃日志（不含识别内容）。可复制以供反馈，复制后日志将被清除。",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", report))
                    CrashReporter.clearAll(activity)
                    activity.pendingCrashReport = null
                }) { Text("复制并清除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    CrashReporter.clearAll(activity)
                    activity.pendingCrashReport = null
                }) { Text("忽略") }
            },
        )
    }
}

private fun typeLabel(type: KeywordType): String = when (type) {
    KeywordType.CORE -> "核心"
    KeywordType.CONTEXT -> "语境"
    KeywordType.EXCLUDE -> "排除"
}

@Composable
private fun HistoryRow(record: TriggerRecord) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(record.timeMillis),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                (if (record.directed) "🎯 " else "") + record.keyword,
                fontSize = 12.sp,
                color = Color(0xFFB71C1C),
            )
            record.confidence?.let { cf ->
                Spacer(Modifier.width(8.dp))
                Text(
                    "置信 %.0f%%".format(cf * 100),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(record.utterance, fontSize = 14.sp)
        if (record.context.isNotBlank()) {
            Text(
                "前文：${record.context}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    desc: String,
    granted: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Text("✓ 已授权", color = Color(0xFF2E7D32), fontSize = 13.sp)
        } else {
            TextButton(onClick = onAction) { Text("去授权") }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TipLine(text: String) {
    Text("· $text", fontSize = 13.sp, lineHeight = 19.sp)
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
