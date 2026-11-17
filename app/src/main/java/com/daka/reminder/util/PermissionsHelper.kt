package com.daka.reminder.util

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionsHelper {

    data class Status(
        val overlay: Boolean,
        val notifications: Boolean,
        val exactAlarm: Boolean
    ) {
        val allOk: Boolean get() = overlay && notifications && exactAlarm

        fun missing(): List<String> = buildList {
            if (!overlay) add("显示在其他应用上层（悬浮窗）")
            if (!notifications) add("通知权限")
            if (!exactAlarm) add("闹钟和提醒（精确定时）")
        }
    }

    fun check(context: Context): Status {
        val overlay = Settings.canDrawOverlays(context)

        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val exactAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(AlarmManager::class.java)
            am?.canScheduleExactAlarms() ?: false
        } else {
            true
        }

        return Status(overlay, notifications, exactAlarm)
    }

    fun openOverlaySettings(context: Context) {
        startSafely(
            context,
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startSafely(
                context,
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    }

    fun openNotificationSettings(context: Context) {
        startSafely(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        )
    }

    /**
     * 应用详情页。这是跳转的最后一站，不能再走 startSafely，
     * 否则连这里都失败时会 startSafely -> openAppDetails -> startSafely 无限递归。
     */
    fun openAppDetails(context: Context) {
        try {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(this)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * MIUI 的「自启动」和「后台弹出界面」没有标准 API，只能尝试跳转小米安全中心。
     * 返回 false 表示机型不支持直达，需要用户手动找。
     */
    fun openMiuiAutoStart(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
            ).putExtra("extra_pkgname", context.packageName),
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                )
            ).putExtra("extra_pkgname", context.packageName)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    /**
     * 尝试打开穿戴设备的配套 App，让用户开启「消息通知」。
     * 华为手表走「华为运动健康」，小米手环走「小米穿戴 / Zepp Life」。
     * 返回 false 表示没装或跳不过去，需要给文字说明。
     */
    fun openWearableApp(context: Context): Boolean {
        val packages = listOf(
            "com.huawei.health",
            "com.xiaomi.wearable",
            "com.xiaomi.hm.health",
            "com.mideindia.zepp.life"
        )
        for (pkg in packages) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    private fun startSafely(context: Context, intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetails(context)
        }
    }
}
