package com.daka.reminder.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机、应用更新、系统时间/时区变化后，AlarmManager 里的闹钟会失效，需要全部重排。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val pending = goAsync()
                try {
                    AlarmScheduler.scheduleAll(context.applicationContext)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
