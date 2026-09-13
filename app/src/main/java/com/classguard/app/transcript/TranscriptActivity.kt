package com.classguard.app.transcript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.classguard.app.data.transcript.TranscriptRepository
import com.classguard.app.data.transcript.TranscriptSegmentEntity
import com.classguard.app.data.transcript.TranscriptSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 课堂转写复盘（v2.2）：会话列表 → 详情（触发句高亮）→ 复制/分享/删除。
 * 数据仅存本机（Room），导出走剪贴板与系统分享，不申请存储权限。
 */
class TranscriptActivity : ComponentActivity() {

    private val repository by lazy { TranscriptRepository.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TranscriptScreen(activity = this, repository = repository, onFinish = { finish() })
                }
            }
        }
    }
}

@Composable
private fun TranscriptScreen(
    activity: TranscriptActivity,
    repository: TranscriptRepository,
    onFinish: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sessions by repository.sessions().collectAsState(initial = emptyList())
    var openSessionId by remember { mutableStateOf<Long?>(null) }
    // 破坏性操作确认：pending 置为待执行动作，用户确认后执行
    var pendingClearAll by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "课堂记录",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (sessions.isNotEmpty() && openSessionId == null) {
                TextButton(onClick = { pendingClearAll = true }) { Text("清空全部") }
            }
            TextButton(onClick = onFinish) { Text("关闭") }
        }

        val openId = openSessionId
        if (openId == null) {
            if (sessions.isEmpty()) {
                Text(
                    "暂无记录。在主界面打开「转写课堂内容」后开始的监听会自动记录，触发提醒的句子会标 ▶。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sessions, key = { it.id }) { session ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                formatRange(session.startTime, session.endTime),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row {
                                TextButton(onClick = { openSessionId = session.id }) { Text("查看") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val segs = withContext(Dispatchers.IO) {
                                            repository.segments(session.id)
                                        }
                                        copyToClipboard(activity, buildExportText(session, segs))
                                    }
                                }) { Text("复制全文") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val segs = withContext(Dispatchers.IO) {
                                            repository.segments(session.id)
                                        }
                                        shareText(activity, buildExportText(session, segs))
                                    }
                                }) { Text("分享") }
                                TextButton(onClick = { pendingDeleteId = session.id }) {
                                    Text("删除", color = Color(0xFFB71C1C))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            var segments by remember(openId) { mutableStateOf<List<TranscriptSegmentEntity>>(emptyList()) }
            var session by remember(openId) { mutableStateOf<TranscriptSessionEntity?>(null) }
            LaunchedEffect(openId) {
                withContext(Dispatchers.IO) {
                    segments = repository.segments(openId)
                    session = repository.session(openId)
                }
            }
            Row {
                TextButton(onClick = { openSessionId = null }) { Text("← 返回") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    scope.launch {
                        copyToClipboard(activity, buildExportText(session, segments))
                    }
                }) { Text("复制全文") }
                TextButton(onClick = { shareText(activity, buildExportText(session, segments)) }) {
                    Text("分享")
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(segments, key = { it.id }) { seg ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                Text(
                                    formatTime(seg.timeMillis),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                if (seg.isTrigger) {
                                    Text(
                                        "▶ ${seg.keyword.orEmpty()}",
                                        fontSize = 12.sp,
                                        color = Color(0xFFB71C1C),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            Text(
                                seg.text,
                                fontSize = 14.sp,
                                fontWeight = if (seg.isTrigger) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }

    if (pendingClearAll) {
        AlertDialog(
            onDismissRequest = { pendingClearAll = false },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { repository.deleteAll() } }
                    pendingClearAll = false
                }) { Text("清空", color = Color(0xFFB71C1C)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearAll = false }) { Text("取消") }
            },
            title = { Text("清空全部课堂记录？") },
            text = { Text("所有会话与转写内容将被删除，不可恢复。") },
        )
    }

    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.IO) { repository.deleteSession(id) } }
                    pendingDeleteId = null
                }) { Text("删除", color = Color(0xFFB71C1C)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
            },
            title = { Text("删除这条课堂记录？") },
            text = { Text("该节课的全部转写内容将被删除，不可恢复。") },
        )
    }
}

internal fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("transcript", text))
    Toast.makeText(context, "已复制全文", Toast.LENGTH_SHORT).show()
}

internal fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享课堂记录"))
}

internal fun buildExportText(
    session: TranscriptSessionEntity?,
    segments: List<TranscriptSegmentEntity>,
): String = buildString {
    appendLine("课堂哨兵 · 课堂记录")
    appendLine("时间：${formatRange(session?.startTime, session?.endTime)}")
    appendLine()
    segments.forEach { seg ->
        val mark = if (seg.isTrigger) " ▶${seg.keyword.orEmpty()}" else ""
        appendLine("[${formatTime(seg.timeMillis)}]$mark ${seg.text}")
    }
}

internal fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

internal fun formatRange(start: Long?, end: Long?): String {
    val s = start ?: return "时间未知"
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return if (end == null) {
        "${fmt.format(Date(s))} · 进行中"
    } else {
        val minutes = ((end - s) / 60000L).coerceAtLeast(0)
        "${fmt.format(Date(s))} · 约 $minutes 分钟"
    }
}
