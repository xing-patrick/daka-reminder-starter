package com.daka.reminder.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.daka.reminder.data.ReminderItem
import com.daka.reminder.data.TaskStore
import com.daka.reminder.overlay.ReminderPresenter
import com.daka.reminder.util.Dates

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: return
        val isSnooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false)

        val today = Dates.todayEpochDay()
        val task = TaskStore.findInstance(context, today, id) ?: return
        // 已打卡、或延迟次数用尽被判定失败 → 不再提醒
        if (task.settled) return

        val item = ReminderItem(
            id = task.id,
            name = task.name,
            minuteOfDay = task.minuteOfDay,
            ruleText = Dates.relativeLabel(today),
            sound = task.sound,
            vibrate = task.vibrate,
            snoozeCount = task.snoozeCount
        )
        ReminderPresenter.show(context, item)

        if (!isSnooze) AlarmScheduler.scheduleAll(context)
    }
}
