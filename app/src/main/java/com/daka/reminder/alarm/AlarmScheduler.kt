package com.daka.reminder.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.daka.reminder.data.TaskStore
import com.daka.reminder.util.Dates
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 排期基于「某天的任务实例」而不是模板。
 * 所以改某一天里某条任务的时间，只会重排那一条，其他日期完全不受影响。
 */
object AlarmScheduler {

    /** 实例 id */
    const val EXTRA_ID = "item_id"
    const val EXTRA_SNOOZE = "snooze"

    private const val RC_NORMAL = 0
    private const val RC_SNOOZE = 100_000
    private const val DAYS_AHEAD = 8

    fun scheduleAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return

        // 先取消所有已知实例的闹钟，再整体重排
        TaskStore.allInstanceIds(context).forEach {
            am.cancel(pendingIntent(context, it, RC_NORMAL, false))
        }

        val today = Dates.todayEpochDay()
        val now = LocalDateTime.now()

        for (offset in 0..DAYS_AHEAD) {
            val day = today + offset
            // entriesFor 会顺带把重复模板展开成这一天的实例
            for (task in TaskStore.entriesFor(context, day)) {
                // 打过卡、或已判定失败的都不再提醒
                if (task.settled) continue
                val at = triggerAt(day, task.minuteOfDay, now) ?: continue
                setExactSafe(am, at, pendingIntent(context, task.id, RC_NORMAL, false))
            }
        }
    }

    /** 某个实例改动后，只重排它自己（scheduleAll 之外更轻量的路径） */
    fun scheduleOne(context: Context, instanceId: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, instanceId, RC_NORMAL, false)
        am.cancel(pi)

        val today = Dates.todayEpochDay()
        val now = LocalDateTime.now()
        for (offset in 0..DAYS_AHEAD) {
            val day = today + offset
            val task = TaskStore.entriesFor(context, day).firstOrNull { it.id == instanceId }
                ?: continue
            if (task.settled) return
            val at = triggerAt(day, task.minuteOfDay, now) ?: return
            setExactSafe(am, at, pi)
            return
        }
    }

    private fun triggerAt(day: Long, minuteOfDay: Int, now: LocalDateTime): Long? {
        val time = Dates.dateOf(day).atTime(minuteOfDay / 60, minuteOfDay % 60)
        if (!time.isAfter(now)) return null
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun snooze(context: Context, instanceId: String, minutes: Long = 10L) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, instanceId, RC_SNOOZE, true)
        am.cancel(pi)
        setExactSafe(am, System.currentTimeMillis() + minutes * 60_000L, pi)
    }

    fun cancelSnooze(context: Context, instanceId: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(context, instanceId, RC_SNOOZE, true))
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        TaskStore.allInstanceIds(context).forEach { id ->
            am.cancel(pendingIntent(context, id, RC_NORMAL, false))
            am.cancel(pendingIntent(context, id, RC_SNOOZE, true))
        }
    }

    private fun pendingIntent(
        context: Context,
        instanceId: String,
        requestCode: Int,
        snooze: Boolean
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ID, instanceId)
            putExtra(EXTRA_SNOOZE, snooze)
            data = Uri.parse("daka://alarm/${if (snooze) "snooze" else "main"}/$instanceId")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode + instanceId.hashCode(), intent, flags)
    }

    private fun setExactSafe(am: AlarmManager, at: Long, pi: PendingIntent) {
        try {
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.canScheduleExactAlarms()
            } else {
                true
            }
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: Exception) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Exception) {
            }
        }
    }
}
