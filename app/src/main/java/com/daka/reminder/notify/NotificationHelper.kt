package com.daka.reminder.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.daka.reminder.MainActivity
import com.daka.reminder.R
import com.daka.reminder.data.ReminderItem
import com.daka.reminder.util.AppSettings

object NotificationHelper {

    // 注意：渠道的重要性（importance）和声音一旦创建就改不动了，只能换 channel id。
    // v1 用了 IMPORTANCE_LOW，系统强制静音；v2 自带声音，会和 App 主动播放的重复，
    // 而且没法按任务关。v3 统一改为「渠道本身无声无震动」，
    // 声音和震动全部交给 AlertFeedback 按每个任务的开关控制。
    private const val CHANNEL_MAIN = "daka_main_v3"
    private const val CHANNEL_LOUD = "daka_loud_v3"

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        // 渠道保持无声：声音和震动由 AlertFeedback 按「每个任务」的开关播放，
        // 否则渠道一声 + App 一声会重复响，也没法单独关。
        if (nm.getNotificationChannel(CHANNEL_MAIN) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MAIN, "打卡提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "到点提醒：桌面卡片留痕。声音和震动由每个任务单独设置"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        // 没开悬浮窗权限时的兜底：弹横幅，同样不由渠道出声
        if (nm.getNotificationChannel(CHANNEL_LOUD) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_LOUD, "打卡提醒（弹窗）", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "未能弹出桌面卡片时的兜底提醒"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }

        // 清掉历史渠道，免得留在系统设置里造成困惑
        runCatching {
            nm.deleteNotificationChannel("daka_quiet")
            nm.deleteNotificationChannel("daka_loud")
            nm.deleteNotificationChannel("daka_main_v2")
            nm.deleteNotificationChannel("daka_loud_v2")
        }
    }

    fun show(context: Context, item: ReminderItem) {
        // 不用手表/手环时可以彻底关掉通知栏那条；声音震动由 AlertFeedback 独立负责
        if (!AppSettings.sendNotification(context)) return

        ensureChannels(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val canOverlay = Settings.canDrawOverlays(context)
        // 手表/手环走的是通知桥接：开启增强后用 HIGH 档，穿戴设备更容易同步到
        val boost = AppSettings.watchBoost(context)
        val channel = if (canOverlay && !boost) CHANNEL_MAIN else CHANNEL_LOUD
        val priority = if (canOverlay && !boost) {
            NotificationCompat.PRIORITY_DEFAULT
        } else {
            NotificationCompat.PRIORITY_MAX
        }

        // 这条通知只为「让手表/手环收到」而存在 —— 手机上真正的提醒是悬浮卡片。
        //
        // 穿戴设备侧只能看、不能操作：华为运动健康只镜像标题和正文，不渲染 Action 按钮；
        // 表上「点通知」也只在表上查看/收起，不会把动作回传手机。所以这里刻意不挂任何按钮、
        // 不让点击改动打卡状态 —— 交互全部留在悬浮卡片上，通知碰一下只是打开 App。
        val openPi = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("该打卡了：${item.name}")
            .setContentText("${item.timeText} · ${item.ruleText}")
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openPi)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notifyId(item.id), notification)
        } catch (e: SecurityException) {
            // 权限被收回，忽略
        }
    }

    fun cancel(context: Context, itemId: String) {
        try {
            NotificationManagerCompat.from(context).cancel(notifyId(itemId))
        } catch (_: Exception) {
        }
    }

    fun cancelAll(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancelAll()
        } catch (_: Exception) {
        }
    }

    private fun notifyId(itemId: String): Int = itemId.hashCode() and 0x7FFFFFFF
}
