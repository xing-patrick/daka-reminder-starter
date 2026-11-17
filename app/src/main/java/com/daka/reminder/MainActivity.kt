package com.daka.reminder

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.viewpager2.widget.ViewPager2
import com.daka.reminder.alarm.AlarmScheduler
import com.daka.reminder.data.DayTask
import com.daka.reminder.data.ReminderItem
import com.daka.reminder.data.TaskStore
import com.daka.reminder.notify.NotificationHelper
import com.daka.reminder.overlay.ReminderPresenter
import com.daka.reminder.ui.DayPagerAdapter
import com.daka.reminder.ui.Dialogs
import com.daka.reminder.ui.HolidayActivity
import com.daka.reminder.ui.TaskLibraryActivity
import com.daka.reminder.util.AlertFeedback
import com.daka.reminder.util.AppSettings
import com.daka.reminder.util.Dates
import com.daka.reminder.util.PermissionsHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 主界面：左右滑动切换日期，每天只显示这一天的打卡项。
 *
 * 这里是「日期任务列表」，任务库只提供模板：
 *  - 重复模板按星期规则自动展开到命中的日期
 *  - 单日模板不指定日期，需要在这里点「从库添加」挑进来
 *  - 列表里的每一项都是某一天独有的实例，改它不影响模板、不影响其他日期
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var dayAdapter: DayPagerAdapter
    private lateinit var tvDate: TextView
    private lateinit var tvSub: TextView
    private lateinit var banner: View

    private var currentDay: Long = Dates.todayEpochDay()

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateHeader() }

    /** 系统铃声选择器：返回选中的铃声 URI，null 表示用户选了「静音」 */
    private val ringtonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) return@registerForActivityResult

            val picked = IntentCompat.getParcelableExtra(
                data, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java
            )

            if (picked == null) {
                AppSettings.setRingtoneUri(this, AppSettings.RINGTONE_SILENT)
                Toast.makeText(this, R.string.ringtone_silent, Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            // 本地文件需要持久化读权限；系统铃声不支持时会抛，忽略即可
            try {
                contentResolver.takePersistableUriPermission(
                    picked, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }

            AppSettings.setRingtoneUri(this, picked.toString())
            Toast.makeText(this, R.string.ringtone_saved, Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 旧版本数据转成 v3 模型；只在首次执行
        TaskStore.migrateIfNeeded(this)
        TaskStore.seedIfNeeded(this)

        setContentView(R.layout.activity_main)

        tvDate = findViewById(R.id.tv_date)
        tvSub = findViewById(R.id.tv_sub)
        banner = findViewById(R.id.banner_permission)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setOnMenuItemClickListener { onMenuItemClick(it) }
        toolbar.menu.findItem(R.id.action_persistent)?.isChecked =
            AppSettings.persistentAlarm(this)

        // 点上方日期 → 弹月历快速跳转
        val openCalendar = View.OnClickListener { showDatePicker() }
        tvDate.setOnClickListener(openCalendar)
        tvSub.setOnClickListener(openCalendar)

        pager = findViewById(R.id.pager)
        dayAdapter = DayPagerAdapter(
            onToggle = { id, day -> onToggle(id, day) },
            onLongPress = { id, day -> onLongPress(id, day) }
        )
        pager.adapter = dayAdapter
        pager.offscreenPageLimit = 1
        pager.setCurrentItem(dayAdapter.positionOf(currentDay), false)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentDay = dayAdapter.epochDayAt(position)
                updateHeader()
            }
        })

        findViewById<View>(R.id.btn_prev).setOnClickListener { gotoDay(currentDay - 1) }
        findViewById<View>(R.id.btn_next).setOnClickListener { gotoDay(currentDay + 1) }

        // 从任务库挑模板，加进这一天
        findViewById<View>(R.id.btn_add_from_library).setOnClickListener {
            if (!canEdit(currentDay)) {
                Toast.makeText(this, R.string.row_readonly, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Dialogs.addFromLibrary(this, currentDay) { refresh() }
        }

        // 只属于这一天的临时任务
        findViewById<View>(R.id.btn_add).setOnClickListener {
            if (!canEdit(currentDay)) {
                Toast.makeText(this, R.string.row_readonly, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Dialogs.editInstance(this, currentDay, null) { refresh() }
        }

        findViewById<View>(R.id.btn_library).setOnClickListener {
            startActivity(Intent(this, TaskLibraryActivity::class.java))
        }

        banner.setOnClickListener { showPermissionDialog() }

        updateHeader()
        maybeAskNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // 每次回到前台重排一遍闹钟：系统清掉闹钟、改时间、换时区后都能自愈
        AlarmScheduler.scheduleAll(this)
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ---------------- 导航 ----------------

    /** 今天及以后可以安排；过去的日期只读 */
    private fun canEdit(day: Long): Boolean = day >= Dates.todayEpochDay()

    private fun gotoDay(day: Long) {
        pager.setCurrentItem(dayAdapter.positionOf(day), true)
    }

    /** 月历选日期。MaterialDatePicker 用的是 UTC 零点毫秒，整除就是 epochDay */
    private fun showDatePicker() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(getString(R.string.pick_date_title))
            .setSelection(currentDay * DAY_MS)
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            gotoDay(selection / DAY_MS)
        }
        picker.show(supportFragmentManager, "date_picker")
    }

    private fun refresh() {
        dayAdapter.notifyDataSetChanged()
        updateHeader()
    }

    private fun updateHeader() {
        tvDate.text = Dates.dateHeader(currentDay)

        val (done, total) = TaskStore.progress(this, currentDay)
        val label = Dates.relativeLabel(currentDay)
        tvSub.text = if (total == 0) label else "$label · 已完成 $done/$total"

        banner.visibility = if (PermissionsHelper.check(this).allOk) View.GONE else View.VISIBLE
    }

    // ---------------- 打卡 ----------------

    private fun onToggle(id: String, day: Long) {
        if (day != Dates.todayEpochDay()) {
            Toast.makeText(this, R.string.row_readonly, Toast.LENGTH_SHORT).show()
            return
        }
        // 这条已经判过失败，点一下等于手动补卡：时间照样记实际时刻
        val revive = TaskStore.findInstance(this, day, id)?.let { it.failed && !it.done } == true

        val nowDone = TaskStore.toggleDone(this, day, id)
        if (nowDone) {
            AlarmScheduler.cancelSnooze(this, id)
            NotificationHelper.cancel(this, id)
            // 提醒可能还在响（用户切进 App 点打卡），一并收掉
            AlertFeedback.stop()
            ReminderPresenter.dismissAll()
            if (revive) {
                Toast.makeText(this, R.string.row_failed_manual, Toast.LENGTH_SHORT).show()
            }
        }
        AlarmScheduler.scheduleAll(this)
        refresh()
    }

    private fun onLongPress(id: String, day: Long) {
        val instance = TaskStore.findInstance(this, day, id) ?: return

        if (!canEdit(day)) {
            Toast.makeText(this, R.string.row_readonly, Toast.LENGTH_SHORT).show()
            return
        }

        val actions = mutableListOf<Pair<String, () -> Unit>>()

        actions.add(getString(R.string.row_edit_instance) to {
            Dialogs.editInstance(this, day, instance) { refresh() }
        })

        actions.add(getString(R.string.row_remove) to {
            TaskStore.removeInstance(this, day, id)
            AlarmScheduler.cancelSnooze(this, id)
            AlarmScheduler.scheduleAll(this)
            refresh()
            Toast.makeText(this, R.string.row_remove_done, Toast.LENGTH_SHORT).show()
        })

        // 只有来自任务库的实例才能删模板；删模板只影响任务库，已加进各天的实例保留
        if (instance.templateId.isNotBlank()) {
            actions.add(getString(R.string.row_delete_task) to { confirmDeleteTemplate(instance) })
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(instance.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
            }
            .show()
    }

    private fun confirmDeleteTemplate(instance: DayTask) {
        val template = TaskStore.findTemplate(this, instance.templateId) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("删除「${template.name}」？")
            .setMessage("和改模板一样：今天还没到点的、以及以后日期里由这条生成的打卡项会一起删掉；过去的、已打卡的、你手动改过的都保留。")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val removed = TaskStore.deleteTemplateWithSync(this, template.id)
                AlarmScheduler.scheduleAll(this)
                refresh()
                Toast.makeText(
                    this,
                    if (removed > 0) "已删除模板，并同步移除 $removed 条未来未发生的打卡项"
                    else "已删除模板",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    // ---------------- 权限 ----------------

    private fun showPermissionDialog() {
        val status = PermissionsHelper.check(this)
        val actions = mutableListOf<Pair<String, () -> Unit>>()

        if (!status.notifications) {
            actions.add("① 开启通知权限" to { PermissionsHelper.openNotificationSettings(this) })
        }
        if (!status.exactAlarm) {
            actions.add("② 允许「闹钟和提醒」精确定时" to { PermissionsHelper.openExactAlarmSettings(this) })
        }
        if (!status.overlay) {
            actions.add("③ 允许「显示在其他应用上层」" to { PermissionsHelper.openOverlaySettings(this) })
        }
        actions.add("④ 电池 → 设为无限制" to {
            try {
                startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                PermissionsHelper.openAppDetails(this)
            }
        })
        actions.add("⑤ 小米：自启动 + 后台弹出界面" to {
            if (!PermissionsHelper.openMiuiAutoStart(this)) {
                Toast.makeText(
                    this,
                    "没找到小米安全中心，请手动到 设置 → 应用设置 → 打卡提醒 → 权限管理 里开启「自启动」和「后台弹出界面」",
                    Toast.LENGTH_LONG
                ).show()
                PermissionsHelper.openAppDetails(this)
            }
        })
        actions.add("⑥ 手表 / 手环通知推送" to { showWatchDialog() })

        showOptionDialog(
            title = "权限设置",
            hint = if (status.allOk) {
                "基础权限都齐了。若小米上仍弹不出来，重点检查 ④ 和 ⑤。"
            } else {
                "以下这些没开，到点可能弹不出提醒。点击逐项开启："
            },
            options = actions.map { it.first }
        ) { which ->
            actions[which].second()
        }
    }

    // ---------------- 菜单 ----------------

    /**
     * 带说明文字的选项列表。
     *
     * 不能直接用 AlertDialog 的 setMessage + setItems：AndroidX 的 setupContent 是 else-if 链，
     * 一旦有 message，listView 就被整个丢掉，界面只剩说明和「关闭」按钮。
     * 所以这里用自定义布局：说明在上，选项在下，行数不限。
     */
    private fun showOptionDialog(
        title: String,
        hint: String,
        options: List<String>,
        onPick: (Int) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_option_list, null)
        val tvHint = view.findViewById<TextView>(R.id.tv_option_hint)
        if (hint.isBlank()) {
            tvHint.visibility = View.GONE
        } else {
            tvHint.text = hint
        }
        val box = view.findViewById<LinearLayout>(R.id.ll_options)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(R.string.action_close, null)
            .create()
        dialog.show()

        options.forEachIndexed { index, label ->
            val row = layoutInflater.inflate(R.layout.item_option_row, box, false) as TextView
            row.text = label
            row.setOnClickListener {
                dialog.dismiss()
                onPick(index)
            }
            box.addView(row)
        }
    }

    /**
     * 提醒铃声：全局一个。
     *
     * 默认用系统通知音，会和微信/QQ 的通知音混在一起，不容易分辨。
     * 这里可以换成手机里其它候选铃声（通知音 / 闹钟 / 来电铃声），甚至本地音频文件。
     */
    private fun showRingtoneDialog() {
        val current = AppSettings.ringtoneUri(this)
        val currentText = when {
            current == AppSettings.RINGTONE_SILENT -> getString(R.string.ringtone_current_silent)
            current.isBlank() -> getString(R.string.ringtone_current_default)
            else -> getString(R.string.ringtone_current_custom, ringtoneTitle(current))
        }

        showOptionDialog(
            title = getString(R.string.ringtone_title),
            hint = currentText + "\n\n" + getString(R.string.ringtone_hint),
            options = listOf(
                getString(R.string.ringtone_pick_notification),
                getString(R.string.ringtone_pick_alarm),
                getString(R.string.ringtone_pick_ringtone),
                getString(R.string.ringtone_restore_default),
                getString(R.string.ringtone_silent)
            )
        ) { which ->
            when (which) {
                0 -> openRingtonePicker(RingtoneManager.TYPE_NOTIFICATION)
                1 -> openRingtonePicker(RingtoneManager.TYPE_ALARM)
                2 -> openRingtonePicker(RingtoneManager.TYPE_RINGTONE)
                3 -> {
                    AppSettings.setRingtoneUri(this, "")
                    Toast.makeText(this, R.string.ringtone_current_default, Toast.LENGTH_SHORT)
                        .show()
                }
                4 -> {
                    AppSettings.setRingtoneUri(this, AppSettings.RINGTONE_SILENT)
                    Toast.makeText(this, R.string.ringtone_silent, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 调起系统铃声选择器；type 决定候选范围（通知音 / 闹钟 / 来电铃声） */
    private fun openRingtonePicker(type: Int) {
        val current = AppSettings.ringtoneUri(this)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.ringtone_picker_title))
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            if (current.isNotBlank() && current != AppSettings.RINGTONE_SILENT) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current))
            }
        }
        try {
            ringtonePickerLauncher.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.ringtone_picker_missing, Toast.LENGTH_LONG).show()
        }
    }

    /** 从 URI 取铃声名称；拿不到就退回路径尾巴，至少别显示一串乱码 */
    private fun ringtoneTitle(uriText: String): String {
        return try {
            val uri = Uri.parse(uriText)
            val title = RingtoneManager.getRingtone(this, uri)?.getTitle(this)
            if (title.isNullOrBlank()) uri.lastPathSegment ?: uriText else title.toString()
        } catch (_: Exception) {
            uriText
        }
    }

    /** 手表走的是系统通知桥接，不是悬浮卡片；表上只能看，操作仍在卡片上 */
    private fun showWatchDialog() {
        val boost = AppSettings.watchBoost(this)
        val sendNotif = AppSettings.sendNotification(this)
        val repeat = AppSettings.watchRepeat(this)

        val labels = listOf(
            getString(R.string.watch_open_app),
            getString(if (boost) R.string.watch_boost_off else R.string.watch_boost_on),
            getString(if (sendNotif) R.string.watch_notify_off else R.string.watch_notify_on),
            getString(if (repeat) R.string.watch_repeat_off else R.string.watch_repeat_on),
            getString(R.string.watch_test)
        )
        showOptionDialog(
            title = getString(R.string.watch_title),
            hint = getString(R.string.watch_message),
            options = labels
        ) { which ->
            when (which) {
                0 -> {
                    if (!PermissionsHelper.openWearableApp(this)) {
                        Toast.makeText(this, R.string.watch_no_app, Toast.LENGTH_LONG).show()
                        PermissionsHelper.openAppDetails(this)
                    }
                }
                1 -> {
                    AppSettings.setWatchBoost(this, !boost)
                    Toast.makeText(
                        this,
                        if (!boost) R.string.watch_boost_on else R.string.watch_boost_off,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                2 -> {
                    AppSettings.setSendNotification(this, !sendNotif)
                    Toast.makeText(
                        this,
                        if (!sendNotif) R.string.watch_notify_on else R.string.watch_notify_off,
                        Toast.LENGTH_LONG
                    ).show()
                }
                3 -> {
                    AppSettings.setWatchRepeat(this, !repeat)
                    Toast.makeText(
                        this,
                        if (!repeat) R.string.watch_repeat_on else R.string.watch_repeat_off,
                        Toast.LENGTH_LONG
                    ).show()
                }
                4 -> ReminderPresenter.show(this, pickSample())
            }
        }
    }

    private fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_today -> {
                gotoDay(Dates.todayEpochDay())
                true
            }

            R.id.action_holidays -> {
                startActivity(Intent(this, HolidayActivity::class.java))
                true
            }

            R.id.action_persistent -> {
                val next = !AppSettings.persistentAlarm(this)
                AppSettings.setPersistentAlarm(this, next)
                item.isChecked = next
                Toast.makeText(
                    this,
                    if (next) R.string.persistent_on else R.string.persistent_off,
                    Toast.LENGTH_LONG
                ).show()
                true
            }

            R.id.action_ringtone -> {
                showRingtoneDialog()
                true
            }

            R.id.action_watch -> {
                showWatchDialog()
                true
            }

            R.id.action_test -> {
                ReminderPresenter.show(this, pickSample())
                // 预览的用途就是排查「为什么不响 / 不震」，把实际结果直接显示出来，
                // 免得失败被静默吞掉、只能靠猜
                window.decorView.postDelayed({
                    val s = AlertFeedback.lastStatus
                    Toast.makeText(this, s.ifBlank { "已触发提醒" }, Toast.LENGTH_LONG).show()
                }, 600)
                true
            }

            R.id.action_permission -> {
                showPermissionDialog()
                true
            }

            R.id.action_clear -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("清空全部")
                    .setMessage("会删除任务库里所有模板、所有日期的打卡记录和已排的提醒，无法恢复。节假日表会保留。")
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton("清空") { _, _ ->
                        AlarmScheduler.cancelAll(this)
                        TaskStore.clearAll(this)
                        refresh()
                    }
                    .show()
                true
            }

            else -> false
        }
    }

    /** 预览用：优先取今天还没结束（没打卡、没判失败）的第一项 */
    private fun pickSample(): ReminderItem {
        val today = Dates.todayEpochDay()
        val entries = TaskStore.entriesFor(this, today)
        val pick = entries.firstOrNull { !it.settled } ?: entries.firstOrNull()
        if (pick != null) {
            return ReminderItem(
                id = pick.id,
                name = pick.name,
                minuteOfDay = pick.minuteOfDay,
                ruleText = Dates.relativeLabel(today),
                sound = pick.sound,
                vibrate = pick.vibrate,
                snoozeCount = pick.snoozeCount
            )
        }
        val tpl = TaskStore.templates(this).firstOrNull()
        return ReminderItem(
            id = tpl?.id ?: "preview",
            name = tpl?.name ?: "早起",
            minuteOfDay = tpl?.minuteOfDay ?: (7 * 60),
            ruleText = tpl?.ruleText ?: Dates.relativeLabel(today),
            sound = tpl?.sound ?: true,
            vibrate = tpl?.vibrate ?: true
        )
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
