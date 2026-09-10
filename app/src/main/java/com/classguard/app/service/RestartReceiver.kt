package com.classguard.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.classguard.app.MainActivity
import com.classguard.app.R
import com.classguard.app.ServiceBus
import com.classguard.app.data.PrefsStore

/**
 * 后台恢复（v1.2，不申请开机自启）：
 * 用户划走任务（onTaskRemoved）时由服务经 AlarmManager 约 3 秒后拉起本 Receiver；
 * 若"监听应处于开启状态"则尝试前台启动服务；
 * Android 12+/14+ 后台启动受限时降级为高优先级"点按恢复监听"通知，
 * 由用户点击回主界面在前台合法拉起。
 */
class RestartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RestartReceiver"
        private const val REQUEST_CODE = 3001
        private const val RECOVER_NOTIF_ID = 3002
        private const val RECOVER_CHANNEL = "alerts"

        /** 供服务在 onTaskRemoved 里安排延时重启。 */
        fun schedule(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, RestartReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // setAndAllowWhileIdle：非精确闹钟，无需 SCHEDULE_EXACT_ALARM 权限，Doze 下也会触发
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3_000L, pending)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = PrefsStore(context)
        if (!prefs.monitoringEnabled) {
            Log.i(TAG, "用户未要求监听运行，跳过恢复")
            return
        }
        if (ServiceBus.running.value) {
            Log.i(TAG, "服务仍在运行，跳过恢复")
            return
        }
        Log.i(TAG, "尝试恢复监听服务")
        try {
            context.startForegroundService(
                Intent(context, RecognitionService::class.java)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "后台启动失败，降级为通知恢复：${t.message}")
            postRecoverNotification(context)
        }
    }

    private fun postRecoverNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val open = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, RECOVER_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(R.string.recover_notif_title))
            .setContentText(context.getString(R.string.recover_notif_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(RECOVER_NOTIF_ID, notification)
        }
    }
}
