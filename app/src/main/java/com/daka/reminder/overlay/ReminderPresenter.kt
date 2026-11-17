package com.daka.reminder.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import com.daka.reminder.R
import com.daka.reminder.alarm.AlarmScheduler
import com.daka.reminder.data.ReminderItem
import com.daka.reminder.data.TaskStore
import com.daka.reminder.notify.NotificationHelper
import com.daka.reminder.util.AlertFeedback
import com.daka.reminder.util.AppSettings
import com.daka.reminder.util.Dates
import com.daka.reminder.util.KeepAwake

/**
 * 桌面悬浮提醒卡片。
 *
 * 用 WindowManager 直接 addView 到系统窗口层，所以：
 *  - 不需要打开 App，在桌面、其他 App 之上都能弹出
 *  - 需要「显示在其他应用上层」权限；MIUI 还需要「后台弹出界面」
 *  - 打卡 / 稍后 之后立刻 removeView，平时屏幕上完全看不到
 */
object ReminderPresenter {

    /** 卡片最长停留时间，防止忘记处理一直挡屏幕 */
    private const val AUTO_DISMISS_MS = 15 * 60 * 1000L

    /** 手表重复提醒的间隔：手表单条通知震不了多久，靠反复发通知让它多震几轮 */
    private const val REPEAT_NOTIFY_MS = 20_000L

    /** 重复提醒最多持续半小时，之后停手，不然一直没处理会无限刷通知 */
    private const val REPEAT_NOTIFY_MAX_MS = 30 * 60 * 1000L

    /**
     * 最后一次机会的窗口。延迟次数用尽后还会再提醒一次，
     * 这一轮 10 分钟内没处理就自动判定打卡失败。
     */
    private const val LAST_CHANCE_MS = 10 * 60 * 1000L

    /** 「放弃本次」的二次确认窗口：这段时间内再点一次才真的放弃 */
    private const val GIVE_UP_CONFIRM_MS = 4_000L

    private val main = Handler(Looper.getMainLooper())

    private var overlay: View? = null
    private var wm: WindowManager? = null
    private var autoDismiss: Runnable? = null
    private var repeatNotify: Runnable? = null
    private var giveUpReset: Runnable? = null

    /** 「放弃本次」已点过一次，等第二次确认 */
    private var giveUpArmed = false

    fun show(context: Context, item: ReminderItem) {
        val app = context.applicationContext
        // 息屏时广播一返回 CPU 就睡，先把唤醒锁拿到手 ——
        // 否则下面的 main.post 排不到执行，表现就是「通知有、声音和震动都没有」
        KeepAwake.acquire(app)
        // 这条任务既不发声也不震动时，「持续提醒」没意义：按一次性处理，卡片 15 分钟后自动收起
        val ringing = item.sound || item.vibrate
        // 持续提醒：不点「已完成 / 稍后」就一直响；关闭时只响一轮
        val persistent = ringing && AppSettings.persistentAlarm(app)

        // 通知作为兜底/留痕，也是手表/手环收到提醒的通路
        NotificationHelper.show(app, item)

        if (!Settings.canDrawOverlays(app)) {
            // 没有悬浮窗权限也要提醒：通知 + 循环声音/震动。
            // 此时没有卡片可点，用户只能点通知打开 App、在今日列表里打勾来收尾
            AlertFeedback.start(app, persistent, item.sound, item.vibrate)
            scheduleRepeatNotify(app, item, persistent)
            scheduleLastChance(app, item)
            main.post {
                Toast.makeText(
                    app,
                    "没有悬浮窗权限，无法弹出桌面提醒。打开 App 首页可一键开启。",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        main.post { showOverlay(app, item, persistent) }
    }

    private fun showOverlay(app: Context, item: ReminderItem, persistent: Boolean) {
        dismissAll()
        // dismissAll 会释放唤醒锁，这条提醒还要继续跑，补回来
        KeepAwake.acquire(app)

        // 持续提醒：声音 + 震动循环，直到点「已完成 / 稍后 / 放弃」
        AlertFeedback.start(app, persistent, item.sound, item.vibrate)

        val manager = app.getSystemService(WindowManager::class.java) ?: return
        val themed = ContextThemeWrapper(app, R.style.Theme_Daka)
        val view = try {
            LayoutInflater.from(themed).inflate(R.layout.overlay_reminder, null)
        } catch (e: Exception) {
            return
        }

        view.findViewById<TextView>(R.id.tv_overlay_time).text = item.timeText
        view.findViewById<TextView>(R.id.tv_overlay_name).text = item.name
        // 把这条任务的提醒方式也标出来，免得用户以为「怎么不响」
        val modeText = when {
            !item.sound && !item.vibrate -> "只弹卡片"
            !item.sound -> "不响铃"
            !item.vibrate -> "不震动"
            else -> ""
        }
        // 延迟了几次、还剩几次机会，一眼能看到
        val snoozeText = when {
            item.snoozeCount >= TaskStore.MAX_SNOOZE ->
                app.getString(R.string.overlay_meta_last_chance)
            item.snoozeCount > 0 ->
                app.getString(R.string.overlay_meta_snoozed, item.snoozeCount, TaskStore.MAX_SNOOZE)
            else -> ""
        }
        view.findViewById<TextView>(R.id.tv_overlay_meta).text =
            // 延迟次数放最前：宽度不够时会被截断，但这条信息最要紧
            listOf(snoozeText, modeText, item.ruleText)
                .filter { it.isNotBlank() }
                .joinToString(" · ")

        view.findViewById<View>(R.id.btn_overlay_done).setOnClickListener {
            // 打卡成功 = 记下实际打卡时刻，列表里的时间随即变成这个时间
            TaskStore.setDone(app, Dates.todayEpochDay(), item.id, true)
            NotificationHelper.cancel(app, item.id)
            AlarmScheduler.cancelSnooze(app, item.id)
            AlertFeedback.stop()
            dismissAll()
            AlarmScheduler.scheduleAll(app)
        }

        // 稍后次数用尽后，这个按钮变成「放弃本次」——再点就判定失败
        val snoozeBtn = view.findViewById<TextView>(R.id.btn_overlay_snooze)
        val lastChance = item.snoozeCount >= TaskStore.MAX_SNOOZE
        if (lastChance) snoozeBtn.text = app.getString(R.string.overlay_give_up)

        snoozeBtn.setOnClickListener {
            if (lastChance) {
                giveUp(app, item.id)
                return@setOnClickListener
            }

            val count = TaskStore.bumpSnooze(app, Dates.todayEpochDay(), item.id)
            NotificationHelper.cancel(app, item.id)
            AlertFeedback.stop()
            dismissAll()
            AlarmScheduler.snooze(app, item.id, 10L)
            Toast.makeText(
                app,
                app.getString(R.string.snooze_pending, count, TaskStore.MAX_SNOOZE),
                Toast.LENGTH_SHORT
            ).show()
        }

        // 放弃本次的出口。判失败不可逆（当天不再提醒、列表置灰），
        // 所以第一次点只把按钮换成确认态，4 秒内再点一次才真的放弃 ——
        // 悬浮窗里弹不出 AlertDialog，用这种方式防止误触。
        // 延迟次数用尽时左边那个按钮已经变成「放弃本次」，这里就不再重复出现。
        val giveUpBtn = view.findViewById<TextView>(R.id.btn_overlay_giveup)
        giveUpBtn.visibility = if (lastChance) View.GONE else View.VISIBLE
        giveUpBtn.text = app.getString(R.string.overlay_give_up)
        giveUpBtn.setOnClickListener {
            if (!giveUpArmed) {
                giveUpArmed = true
                giveUpBtn.text = app.getString(R.string.overlay_give_up_confirm)
                val reset = Runnable {
                    giveUpArmed = false
                    giveUpBtn.text = app.getString(R.string.overlay_give_up)
                }
                giveUpReset?.let { main.removeCallbacks(it) }
                giveUpReset = reset
                main.postDelayed(reset, GIVE_UP_CONFIRM_MS)
                return@setOnClickListener
            }
            giveUp(app, item.id)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            val h = app.resources.displayMetrics.heightPixels
            y = (h * 0.08f).toInt()
        }

        try {
            manager.addView(view, lp)
        } catch (e: Exception) {
            // MIUI 拦截「后台弹出界面」时也会走到这里
            return
        }

        overlay = view
        wm = manager

        // 从上方滑入
        view.translationY = -80f
        view.alpha = 0f
        view.animate().translationY(0f).alpha(1f).setDuration(260).start()

        autoDismiss?.let { main.removeCallbacks(it) }
        autoDismiss = null

        // 卡片已经显示出来了，开始按间隔重发通知让手表反复震
        scheduleRepeatNotify(app, item, persistent)

        // 持续提醒模式下卡片不会自己收起来，必须点按钮处理；
        // 非持续模式保持原来的 15 分钟保护，避免忘记处理一直挡屏幕。
        if (!persistent) {
            val task = Runnable { dismissAll() }
            autoDismiss = task
            main.postDelayed(task, AUTO_DISMISS_MS)
        }

        // 延迟次数已经用尽的这一轮，10 分钟内不处理就判失败（会覆盖上面的 15 分钟保护）
        scheduleLastChance(app, item)
    }

    /**
     * 最后一次机会的倒计时。
     *
     * 延迟满 [TaskStore.MAX_SNOOZE] 次之后的这一轮提醒，是这条任务当天最后一次挣扎：
     * 10 分钟内点「已完成」就正常打卡；点「放弃本次」立刻判失败；
     * 什么都不做，到点也自动判失败 —— 这样「延迟三次仍未完成」不需要用户额外操作就能落地。
     */
    private fun scheduleLastChance(app: Context, item: ReminderItem) {
        if (item.snoozeCount < TaskStore.MAX_SNOOZE) return
        autoDismiss?.let { main.removeCallbacks(it) }
        val task = Runnable { giveUp(app, item.id) }
        autoDismiss = task
        main.postDelayed(task, LAST_CHANCE_MS)
    }

    /**
     * 判定本次打卡失败：收起提醒、撤掉所有排期、列表里置灰。
     *
     * 两个入口，行为完全一致：
     *  - 卡片上的「放弃本次」（二次确认后）
     *  - 延迟次数用尽后，最后一次机会超时没处理
     *
     * 通知栏没有放弃入口 —— 手表上点不到按钮，通知只做展示，交互全留在卡片上。
     */
    private fun giveUp(context: Context, id: String) {
        val app = context.applicationContext
        val ok = TaskStore.markFailed(app, Dates.todayEpochDay(), id)
        NotificationHelper.cancel(app, id)
        AlarmScheduler.cancelSnooze(app, id)
        AlertFeedback.stop()
        cancelRepeatNotify()
        dismissAll()
        if (ok) {
            AlarmScheduler.scheduleAll(app)
            Toast.makeText(app, R.string.snooze_give_up_done, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 每隔 20 秒重发一次通知。
     *
     * 手表只认系统通知，而单条通知能让手表震多久由手表固件决定，App 改不了。
     * 唯一能"延长"的办法就是重复发 —— 手表每收到一条就震一轮。
     * 只在三种情况下启动：持续提醒开着、任务要声音或震动、通知开关开着。
     * 点「已完成 / 稍后 / 放弃本次」都会停掉。
     */
    private fun scheduleRepeatNotify(app: Context, item: ReminderItem, persistent: Boolean) {
        cancelRepeatNotify()
        if (!persistent) return
        if (!AppSettings.watchRepeat(app)) return
        if (!AppSettings.sendNotification(app)) return

        val tick = object : Runnable {
            var elapsed = 0L
            override fun run() {
                elapsed += REPEAT_NOTIFY_MS
                if (elapsed > REPEAT_NOTIFY_MAX_MS) {
                    repeatNotify = null
                    return
                }
                NotificationHelper.show(app, item)
                main.postDelayed(this, REPEAT_NOTIFY_MS)
            }
        }
        repeatNotify = tick
        main.postDelayed(tick, REPEAT_NOTIFY_MS)
    }

    private fun cancelRepeatNotify() {
        repeatNotify?.let { main.removeCallbacks(it) }
        repeatNotify = null
    }

    fun dismissAll() {
        AlertFeedback.stop()
        cancelRepeatNotify()
        KeepAwake.release()
        giveUpReset?.let {
            main.removeCallbacks(it)
            giveUpReset = null
        }
        giveUpArmed = false
        autoDismiss?.let {
            main.removeCallbacks(it)
            autoDismiss = null
        }
        val v = overlay ?: return
        overlay = null
        try {
            wm?.removeView(v)
        } catch (_: Exception) {
        }
        wm = null
    }

    fun isShowing(): Boolean = overlay != null
}
