package com.classguard.app

import android.Manifest
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import com.classguard.app.alert.AlertManager
import com.classguard.app.data.PrefsStore
import com.classguard.app.recognition.TriggerEvent
import com.classguard.app.selftest.SelfTest
import com.classguard.app.service.RecognitionService
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

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startMonitoring()
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsStore(this)
        AlertManager.createAlertChannel(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(activity = this)
                }
            }
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

    fun ignoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

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

    /** 设置变化后通知运行中的服务即时生效。 */
    fun notifySettingsChanged() {
        if (ServiceBus.running.value) {
            startService(
                Intent(this, RecognitionService::class.java)
                    .setAction(RecognitionService.ACTION_APPLY_SETTINGS)
            )
        }
    }

    fun testAlert() {
        AlertManager.onTrigger(
            this,
            TriggerEvent(
                timeMillis = System.currentTimeMillis(),
                keyword = "测试",
                utterance = "（演示）同学们，下面这道题，我找个同学来回答一下。",
                context = "这道题考的是第三章的内容，大家先看两分钟。",
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
    val partial by ServiceBus.partialText.collectAsState()
    val historyVersion by ServiceBus.historyVersion.collectAsState()

    var keywords by remember { mutableStateOf(prefs.keywords) }
    var cooldownSeconds by remember { mutableIntStateOf((prefs.cooldownMillis / 1000).toInt()) }
    var soundOn by remember { mutableStateOf(prefs.soundEnabled) }
    var vibrateOn by remember { mutableStateOf(prefs.vibrationEnabled) }
    var history by remember { mutableStateOf(prefs.history()) }

    var newKeyword by remember { mutableStateOf("") }
    var selfTestRunning by remember { mutableStateOf(false) }
    var selfTestResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(historyVersion) { history = prefs.history() }

    val tick = activity.permissionTick.intValue
    val micGranted = remember(tick) { activity.hasMicPermission() }
    val notifGranted = remember(tick) { activity.hasNotifPermission() }
    val overlayGranted = remember(tick) { activity.canDrawOverlays() }
    val batteryExempt = remember(tick) { activity.ignoringBatteryOptimizations() }
    val hasTestWav = remember { SelfTest.hasTestWav(activity.assets) && BuildConfig.DEBUG }

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

        // 状态与启停
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (running) "● 监听中" else "○ 已停止",
                        fontWeight = FontWeight.SemiBold,
                        color = if (running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (running) {
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
                    TextButton(onClick = { activity.testAlert() }) { Text("测试提醒效果") }
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
                                                activity, it, prefs.soundEnabled, prefs.vibrationEnabled
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
        }

        // 设置
        SectionCard("提醒设置") {
            Text("冷却时间：${cooldownSeconds} 秒", fontSize = 14.sp)
            Text(
                "触发一次后，冷却时间内不再重复提醒",
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

        // 关键词
        SectionCard("触发关键词（${keywords.size}）") {
            Text(
                "老师说话内容包含以下任一关键词即提醒，可自行增删",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newKeyword,
                    onValueChange = { newKeyword = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入关键词，如：谁来回答") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val kw = newKeyword.trim()
                        if (kw.isNotEmpty() && keywords.none { it.equals(kw, ignoreCase = true) }) {
                            keywords = keywords + kw
                            prefs.keywords = keywords
                            activity.notifySettingsChanged()
                        }
                        newKeyword = ""
                    },
                ) { Icon(Icons.Default.Add, contentDescription = "添加关键词") }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                keywords.forEach { kw ->
                    AssistChip(
                        onClick = {
                            keywords = keywords - kw
                            prefs.keywords = keywords
                            activity.notifySettingsChanged()
                        },
                        label = { Text(kw) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "删除", Modifier.height(14.dp)) },
                    )
                }
            }
            TextButton(onClick = {
                keywords = PrefsStore.DEFAULT_KEYWORDS
                prefs.keywords = keywords
                activity.notifySettingsChanged()
            }) { Text("恢复默认词表") }
        }

        // 历史
        SectionCard("触发历史") {
            if (history.isEmpty()) {
                Text("暂无记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                history.forEach { record ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row {
                            Text(
                                formatTime(record.timeMillis),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(record.keyword, fontSize = 12.sp, color = Color(0xFFB71C1C))
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
                TextButton(onClick = {
                    prefs.clearHistory()
                    history = emptyList()
                }) { Text("清空历史") }
            }
        }

        // 使用提示
        SectionCard("使用提示") {
            TipLine("手机平放桌面，尽量靠近讲台方向；距离越近识别越准。")
            TipLine("开始监听后可锁屏或切换其他 App，状态栏会保留常驻通知。")
            TipLine("建议上课时插电，持续识别有一定耗电。")
            TipLine("小米：设置 → 应用设置 → 应用管理 → 课堂哨兵 → 自启动；省电策略改为无限制。")
            TipLine("华为：设置 → 电池 → 启动管理 → 课堂哨兵 → 允许自启动/后台运行。")
            TipLine("OPPO/vivo：在电池与自启动管理中允许后台运行。")
            TipLine("提醒常被误触发时，删除过短的词（如单字词）；漏触发时，添加老师说过的原话关键词。")
            TipLine("关键词同时作为识别热词偏置，在下次开始监听时生效。")
        }

        Spacer(Modifier.height(24.dp))
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
