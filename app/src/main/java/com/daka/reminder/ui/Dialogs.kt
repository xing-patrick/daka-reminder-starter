package com.daka.reminder.ui

import android.app.TimePickerDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.daka.reminder.R
import com.daka.reminder.alarm.AlarmScheduler
import com.daka.reminder.data.DayTask
import com.daka.reminder.data.ImportParser
import com.daka.reminder.data.ScheduleMode
import com.daka.reminder.data.TaskTemplate
import com.daka.reminder.data.TaskStore
import com.daka.reminder.data.TemplateKind
import com.daka.reminder.util.Dates
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale
import java.util.UUID

/**
 * 共用的几个对话框。
 *
 * 注意区分两件事：
 *  - editTemplate 改的是任务库模板（任务库界面用）
 *  - editInstance 改的是某一天里的实例（主界面用），不碰模板、不碰其他日期
 */
object Dialogs {

    /** 任务库：新建 / 编辑模板 */
    fun editTemplate(
        activity: AppCompatActivity,
        existing: TaskTemplate?,
        onSaved: () -> Unit
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_edit, null)

        val etName = view.findViewById<EditText>(R.id.et_name)
        val btnTime = view.findViewById<TextView>(R.id.btn_pick_time)
        val segRepeat = view.findViewById<TextView>(R.id.seg_repeat)
        val segOnce = view.findViewById<TextView>(R.id.seg_once)
        val blockRepeat = view.findViewById<View>(R.id.block_repeat)
        val blockOnce = view.findViewById<View>(R.id.block_once)

        val chips = listOf(
            R.id.day1, R.id.day2, R.id.day3, R.id.day4,
            R.id.day5, R.id.day6, R.id.day7
        ).map { view.findViewById<TextView>(it) }

        val modeViews: Map<ScheduleMode, TextView> = mapOf(
            ScheduleMode.EVERYDAY to view.findViewById<TextView>(R.id.mode_everyday),
            ScheduleMode.WEEKDAY to view.findViewById<TextView>(R.id.mode_weekday),
            ScheduleMode.WEEKEND to view.findViewById<TextView>(R.id.mode_weekend),
            ScheduleMode.LEGAL_WORKDAY to view.findViewById<TextView>(R.id.mode_legal_workday),
            ScheduleMode.LEGAL_HOLIDAY to view.findViewById<TextView>(R.id.mode_legal_holiday),
            ScheduleMode.CUSTOM to view.findViewById<TextView>(R.id.mode_custom)
        )
        val blockDays = view.findViewById<View>(R.id.block_days)
        val tvRuleHint = view.findViewById<TextView>(R.id.tv_rule_hint)
        val swSound = view.findViewById<SwitchMaterial>(R.id.sw_sound)
        val swVibrate = view.findViewById<SwitchMaterial>(R.id.sw_vibrate)

        var minuteOfDay = existing?.minuteOfDay ?: (8 * 60)
        var kind = existing?.kind ?: TemplateKind.REPEAT
        var mode = existing?.scheduleMode ?: ScheduleMode.EVERYDAY
        var daysMask = existing?.daysMask ?: Dates.ALL_DAYS

        etName.setText(existing?.name.orEmpty())

        // 新建默认「声音 + 震动都开」；改模板时回显当前设置
        swSound.isChecked = existing?.sound ?: true
        swVibrate.isChecked = existing?.vibrate ?: true

        fun renderTime() {
            btnTime.text = String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
        }

        fun renderKind() {
            val isRepeat = kind == TemplateKind.REPEAT
            segRepeat.setBackgroundResource(if (isRepeat) R.drawable.bg_chip_on else R.drawable.bg_chip)
            segOnce.setBackgroundResource(if (isRepeat) R.drawable.bg_chip else R.drawable.bg_chip_on)
            segRepeat.setTextColor(colorOf(activity, isRepeat))
            segOnce.setTextColor(colorOf(activity, !isRepeat))
            blockRepeat.visibility = if (isRepeat) View.VISIBLE else View.GONE
            blockOnce.visibility = if (isRepeat) View.GONE else View.VISIBLE
        }

        fun renderMode() {
            modeViews.forEach { (m, v) ->
                val on = m == mode
                v.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
                v.setTextColor(colorOf(activity, on))
            }
            blockDays.visibility = if (mode == ScheduleMode.CUSTOM) View.VISIBLE else View.GONE
            tvRuleHint.text = when (mode) {
                ScheduleMode.EVERYDAY -> "每天都提醒。"
                ScheduleMode.WEEKDAY -> "周一到周五提醒，不看节假日。"
                ScheduleMode.WEEKEND -> "周六周日提醒，不看节假日。"
                ScheduleMode.LEGAL_WORKDAY -> "跳过法定节假日；调休补班的周末也要提醒。数据来自手机日历的节假日日历，可在菜单「节假日与调休」里维护。"
                ScheduleMode.LEGAL_HOLIDAY -> "周末 + 法定节假日都提醒；调休补班的周末不提醒。和「法定上班日」正好互补，任何一天只会命中其中一边。数据可在菜单「节假日与调休」里维护。"
                ScheduleMode.CUSTOM -> "自己勾选星期，不参考节假日。"
            }
        }

        fun renderDays() {
            chips.forEachIndexed { index, chip ->
                val on = (daysMask shr index) and 1 == 1
                chip.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
                chip.setTextColor(colorOf(activity, on))
            }
        }

        renderTime()
        renderKind()
        renderMode()
        renderDays()

        btnTime.setOnClickListener {
            TimePickerDialog(
                activity,
                { _, hour, minute ->
                    minuteOfDay = hour * 60 + minute
                    renderTime()
                },
                minuteOfDay / 60,
                minuteOfDay % 60,
                true
            ).show()
        }

        segRepeat.setOnClickListener { kind = TemplateKind.REPEAT; renderKind() }
        segOnce.setOnClickListener { kind = TemplateKind.ONCE; renderKind() }

        modeViews.forEach { (m, v) -> v.setOnClickListener { mode = m; renderMode() } }

        chips.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                daysMask = daysMask xor (1 shl index)
                renderDays()
            }
        }

        fun effectiveMask(): Int = when (mode) {
            ScheduleMode.EVERYDAY -> Dates.ALL_DAYS
            ScheduleMode.WEEKDAY, ScheduleMode.LEGAL_WORKDAY -> Dates.WEEKDAYS
            ScheduleMode.WEEKEND -> Dates.WEEKEND
            ScheduleMode.LEGAL_HOLIDAY -> 0
            ScheduleMode.CUSTOM -> daysMask
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) R.string.edit_title_new else R.string.edit_title_edit)
            .setView(view)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = etName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(activity, R.string.edit_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val template = TaskTemplate(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name,
                    minuteOfDay = minuteOfDay,
                    kind = kind,
                    scheduleMode = mode,
                    daysMask = effectiveMask(),
                    createdEpochDay = existing?.createdEpochDay ?: Dates.todayEpochDay(),
                    sound = swSound.isChecked,
                    vibrate = swVibrate.isChecked
                )
                TaskStore.upsertTemplate(activity, template)

                // 改模板 → 传导到「今天还没到点 + 未来」且未被手动改过的实例
                val synced = if (existing != null) TaskStore.syncTemplate(activity, template) else 0
                AlarmScheduler.scheduleAll(activity)
                onSaved()
                if (synced > 0) {
                    Toast.makeText(
                        activity,
                        "已同步 $synced 条未来未发生的打卡项",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    /** 主界面：新建当天临时任务，或只改这一天里的这一条实例（instance 为 null 表示新建） */
    fun editInstance(
        activity: AppCompatActivity,
        day: Long,
        instance: DayTask?,
        onSaved: () -> Unit
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_edit_instance, null)
        val etName = view.findViewById<EditText>(R.id.et_name)
        val btnTime = view.findViewById<TextView>(R.id.btn_pick_time)
        val swSound = view.findViewById<SwitchMaterial>(R.id.sw_sound)
        val swVibrate = view.findViewById<SwitchMaterial>(R.id.sw_vibrate)

        var minuteOfDay = instance?.minuteOfDay ?: (8 * 60)
        etName.setText(instance?.name.orEmpty())
        swSound.isChecked = instance?.sound ?: true
        swVibrate.isChecked = instance?.vibrate ?: true

        fun renderTime() {
            btnTime.text = String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
        }
        renderTime()

        btnTime.setOnClickListener {
            TimePickerDialog(
                activity,
                { _, hour, minute ->
                    minuteOfDay = hour * 60 + minute
                    renderTime()
                },
                minuteOfDay / 60,
                minuteOfDay % 60,
                true
            ).show()
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(if (instance == null) R.string.new_instance_title else R.string.edit_instance_title)
            .setView(view)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = etName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(activity, R.string.edit_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val sound = swSound.isChecked
                val vibrate = swVibrate.isChecked
                if (instance == null) {
                    TaskStore.addStandalone(activity, day, name, minuteOfDay, sound, vibrate)
                } else {
                    TaskStore.updateInstance(activity, day, instance.id, name, minuteOfDay, sound, vibrate)
                }
                AlarmScheduler.scheduleAll(activity)
                onSaved()
            }
            .show()
    }

    /** 从任务库挑一个模板，添加到指定日期 */
    fun addFromLibrary(
        activity: AppCompatActivity,
        day: Long,
        onSaved: () -> Unit
    ) {
        val list = TaskStore.templates(activity)
        if (list.isEmpty()) {
            Toast.makeText(activity, R.string.library_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { "${it.name}  ${it.timeText} · ${it.ruleText}" }.toTypedArray()

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.add_from_library_title)
            .setItems(labels) { _, which ->
                TaskStore.addFromTemplate(activity, day, list[which].id)
                AlarmScheduler.scheduleAll(activity)
                onSaved()
                Toast.makeText(
                    activity,
                    "已添加到${Dates.relativeLabel(day)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 批量导入：产出模板，不绑定任何日期 */
    fun importTemplates(activity: AppCompatActivity, onDone: () -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_import, null)
        val et = view.findViewById<EditText>(R.id.et_import)
        val tvResult = view.findViewById<TextView>(R.id.tv_import_result)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.import_title)
            .setView(view)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.import_title) { _, _ ->
                doImport(activity, et.text?.toString().orEmpty(), onDone)
            }
            .create()

        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // 点一下把对应类型的示例追加进输入框，不用记语法
        val samples = mapOf(
            R.id.chip_everyday to "早起 07:00 每天",
            R.id.chip_weekday to "喝水 09:30 工作日",
            R.id.chip_weekend to "游泳 15:00 周末",
            R.id.chip_legal_workday to "收邮件 09:00 法定上班日",
            R.id.chip_legal_holiday to "大扫除 10:00 休息日",
            R.id.chip_once to "买牛奶 18:00 单日"
        )
        samples.forEach { (id, sample) ->
            view.findViewById<View>(id).setOnClickListener {
                val cur = et.text?.toString().orEmpty()
                et.setText(if (cur.isBlank()) sample else cur.trimEnd() + "\n" + sample)
                et.setSelection(et.text?.length ?: 0)
            }
        }

        et.doAfterTextChanged { text ->
            val raw = text?.toString().orEmpty()
            if (raw.isBlank()) {
                tvResult.visibility = View.GONE
                return@doAfterTextChanged
            }
            val parsed = ImportParser.parse(raw)
            tvResult.visibility = View.VISIBLE

            val once = parsed.items.count { it.kind == TemplateKind.ONCE }
            val repeat = parsed.items.size - once
            val sb = StringBuilder("可导入 ${parsed.items.size} 条模板")
            sb.append("（重复 $repeat / 单日 $once）")
            if (parsed.failed.isNotEmpty()) {
                sb.append("，${parsed.failed.size} 行无法识别")
            }
            if (parsed.items.isNotEmpty()) {
                sb.append("\n")
                sb.append(parsed.items.take(4).joinToString("；") { previewOf(it) })
                if (parsed.items.size > 4) sb.append(" …")
            }
            tvResult.text = sb.toString()
        }
    }

    private fun previewOf(t: TaskTemplate): String =
        "${t.name} ${t.timeText} [${t.ruleText}]"

    private fun doImport(context: Context, raw: String, onDone: () -> Unit) {
        val parsed = ImportParser.parse(raw)
        if (parsed.items.isEmpty()) {
            Toast.makeText(context, R.string.import_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val merged = TaskStore.templates(context).toMutableList()
        var added = 0
        parsed.items.forEach { candidate ->
            val duplicate = merged.any {
                it.name == candidate.name &&
                    it.minuteOfDay == candidate.minuteOfDay &&
                    it.kind == candidate.kind &&
                    it.scheduleMode == candidate.scheduleMode &&
                    it.daysMask == candidate.daysMask
            }
            if (!duplicate) {
                merged.add(candidate)
                added++
            }
        }

        TaskStore.saveTemplates(context, merged)
        AlarmScheduler.scheduleAll(context)
        onDone()

        val skipped = parsed.failed.size
        val dupCount = parsed.items.size - added
        val msg = buildString {
            append("导入 $added 条模板")
            if (dupCount > 0) append("，$dupCount 条重复已跳过")
            if (skipped > 0) append("，$skipped 行无法识别")
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    private fun colorOf(context: Context, selected: Boolean): Int =
        ContextCompat.getColor(context, if (selected) R.color.c_on_primary else R.color.c_text)
}
