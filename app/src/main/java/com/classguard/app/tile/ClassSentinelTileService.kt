package com.classguard.app.tile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.classguard.app.MainActivity
import com.classguard.app.R
import com.classguard.app.ServiceBus
import com.classguard.app.service.RecognitionService

/**
 * 快捷磁贴（v2.2）：下拉快捷面板一键开/关课堂监听。
 *
 * 启动策略（规避 Android 12+/14 后台 FGS 限制）：
 * - 正在监听 → 直接发 ACTION_STOP 停止（对已运行服务的普通 startService 可送达）；
 * - 未监听 → 先尝试前台启动（失败则降级为拉起主界面，由前台自动开始监听）。
 */
class ClassSentinelTileService : TileService() {

    companion object {
        private const val TAG = "TileService"
        const val EXTRA_AUTO_START = "auto_start"

        /** 服务启停后调用，让系统回调 onStartListening 刷新磁贴状态。 */
        fun requestUpdate(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, ClassSentinelTileService::class.java),
                )
            }
        }
    }

    override fun onStartListening() {
        refresh()
    }

    override fun onTileAdded() {
        refresh()
    }

    override fun onClick() {
        val appContext = applicationContext
        if (ServiceBus.running.value) {
            Log.i(TAG, "磁贴：停止监听")
            runCatching {
                startService(
                    Intent(appContext, RecognitionService::class.java)
                        .setAction(RecognitionService.ACTION_STOP)
                )
            }
            refresh()
            return
        }
        Log.i(TAG, "磁贴：开始监听")
        val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        var started = false
        if (micGranted) {
            started = runCatching {
                appContext.startForegroundService(
                    Intent(appContext, RecognitionService::class.java)
                )
                true
            }.getOrDefault(false)
        }
        if (!started) {
            // 后台启动受限或无麦克风权限：拉起主界面（前台）自动开始/引导授权
            val intent = Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_AUTO_START, true)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(
                        appContext, 0, intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            } else {
                // API<34 只有 Intent 重载（34 起废弃，旧系统必须用它）
                @Suppress("DEPRECATION")
                @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        tile.state = if (ServiceBus.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (ServiceBus.running.value) {
                getString(R.string.tile_on)
            } else {
                getString(R.string.tile_off)
            }
        }
        tile.updateTile()
    }
}
