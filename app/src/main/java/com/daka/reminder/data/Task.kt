package com.daka.reminder.data

import com.daka.reminder.util.Dates
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** 模板类型：REPEAT 按规则自动展开，ONCE 不指定日期、由用户主动添加 */
enum class TemplateKind { REPEAT, ONCE }

/**
 * 重复模板的排期规则。
 *
 *  - EVERYDAY      每天
 *  - WEEKDAY       周一至周五（纯星期，不看节假日）
 *  - WEEKEND       周六周日（纯星期，不看节假日）
 *  - LEGAL_WORKDAY 法定上班日：周一至周五 − 法定节假日 + 调休补班日
 *  - LEGAL_HOLIDAY 休息日：周末 + 法定节假日 − 调休补班日
 *                  与 LEGAL_WORKDAY 严格互补，任何一天只会命中其中一边。
 *                  枚举名保留 LEGAL_HOLIDAY 是为了兼容已存进 SharedPreferences 的 JSON，
 *                  语义和显示名都是「休息日」。
 *  - CUSTOM        自己勾星期，用 daysMask
 */
enum class ScheduleMode {
    EVERYDAY, WEEKDAY, WEEKEND, LEGAL_WORKDAY, LEGAL_HOLIDAY, CUSTOM;

    fun label(daysMask: Int): String = when (this) {
        EVERYDAY -> "每天"
        WEEKDAY -> "工作日"
        WEEKEND -> "周末"
        LEGAL_WORKDAY -> "法定上班日"
        LEGAL_HOLIDAY -> "休息日"
        CUSTOM -> Dates.weekdayText(daysMask)
    }
}

/**
 * 任务库里的模板。模板不隶属于任何日期，也不带完成状态。
 *
 *  - REPEAT 重复模板：带排期规则，打开某一天时自动"展开"成那天的实例
 *  - ONCE   单日模板：不指定日期，静静待在任务库里，由用户在某一天主动挑选添加
 *
 * 模板与实例之间是单向可传导的：
 *  - 改实例 → 不回写模板；改过的实例打上 customized，之后模板再改也跳过它
 *  - 改模板 → 只同步「今天尚未到点 + 未来日期」且没被手动改过、没打卡的实例；过去的一律不动
 */
data class TaskTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val minuteOfDay: Int,
    val kind: TemplateKind = TemplateKind.REPEAT,
    val scheduleMode: ScheduleMode = ScheduleMode.EVERYDAY,
    /** CUSTOM 用：星期 bitmask */
    val daysMask: Int = Dates.ALL_DAYS,
    val createdEpochDay: Long = Dates.todayEpochDay(),
    /** 到点是否出声。声音走通知音量，手机静音 / 勿扰时不会响 */
    val sound: Boolean = true,
    /** 到点是否震动 */
    val vibrate: Boolean = true
) {
    val hour: Int get() = minuteOfDay / 60
    val minute: Int get() = minuteOfDay % 60

    val timeText: String get() = String.format(Locale.US, "%02d:%02d", hour, minute)

    val ruleText: String
        get() = if (kind == TemplateKind.REPEAT) scheduleMode.label(daysMask) else "单次任务"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("min", minuteOfDay)
        put("kind", kind.name)
        put("mode", scheduleMode.name)
        put("days", daysMask)
        put("created", createdEpochDay)
        put("s", sound)
        put("v", vibrate)
    }

    companion object {

        fun fromJson(o: JSONObject): TaskTemplate {
            val mask = o.optInt("days", Dates.ALL_DAYS)
            val mode = if (o.has("mode")) {
                runCatching { ScheduleMode.valueOf(o.optString("mode", "EVERYDAY")) }
                    .getOrDefault(ScheduleMode.EVERYDAY)
            } else {
                // 没有 mode 字段的旧数据：按 daysMask 反推一个等价规则
                modeFromMask(mask)
            }
            return TaskTemplate(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name", ""),
                minuteOfDay = o.optInt("min", 480),
                kind = runCatching { TemplateKind.valueOf(o.optString("kind", "REPEAT")) }
                    .getOrDefault(TemplateKind.REPEAT),
                scheduleMode = mode,
                daysMask = mask,
                createdEpochDay = o.optLong("created", Dates.todayEpochDay()),
                // 老数据没有 s / v 字段，默认 true，保持原来「声音 + 震动都开」的行为
                sound = o.optBoolean("s", true),
                vibrate = o.optBoolean("v", true)
            )
        }

        fun modeFromMask(mask: Int): ScheduleMode = when (mask and Dates.ALL_DAYS) {
            Dates.ALL_DAYS -> ScheduleMode.EVERYDAY
            Dates.WEEKDAYS -> ScheduleMode.WEEKDAY
            Dates.WEEKEND -> ScheduleMode.WEEKEND
            else -> ScheduleMode.CUSTOM
        }
    }
}

/**
 * 某一天里的一条任务实例。
 *
 * 实例是从模板"复制"出来的，之后基本独立：改名称、改时间、打卡、删除
 * 都只作用于这一条，不碰模板、不碰其他日期的实例。
 *
 *  - templateId 为空串 = 用户直接在这天新建的临时任务，不来自任务库
 *  - removed    = 墓碑：用户把这条从这天移除了，标记后不再被模板自动补回来
 *  - customized = 用户手动改过这条，之后改模板时不再覆盖它
 *  - auto       = 这条是重复模板按规则自动展开出来的；规则变了才会被自动撤掉。
 *                 用户点「从库添加」主动放进来的一律 auto=false，永远不会被撤
 *  - doneAt     = 实际打卡时刻（当天第几分钟）。打卡成功后列表和卡片显示这个时间，
 *                 而不是原定时间 —— 提前打卡、延后打卡都算实际时间
 *  - snoozeCount= 已经点过几次「稍后 10 分钟」
 *  - failed     = 延迟次数用尽仍未完成，判定打卡失败：不再提醒、列表置灰。
 *                 仍然可以手动点一下补上（补上就清掉 failed）
 */
data class DayTask(
    val id: String = UUID.randomUUID().toString(),
    val templateId: String = "",
    val name: String,
    val minuteOfDay: Int,
    val done: Boolean = false,
    val removed: Boolean = false,
    val customized: Boolean = false,
    val auto: Boolean = false,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val doneAt: Int = -1,
    val snoozeCount: Int = 0,
    val failed: Boolean = false
) {
    /** 显示用的时间：打过卡就显示实际打卡时间，否则显示原定时间 */
    val displayMinute: Int get() = if (done && doneAt >= 0) doneAt else minuteOfDay

    val timeText: String get() = format(displayMinute)

    /** 原定时间，用于「原定 08:00」这类提示 */
    val plannedTimeText: String get() = format(minuteOfDay)

    /** 实际打卡时间和原定时间不一致（提前或延后） */
    val timeShifted: Boolean get() = done && doneAt >= 0 && doneAt != minuteOfDay

    /** 已经结束了（打卡成功或判定失败），不再需要排提醒 */
    val settled: Boolean get() = done || failed

    private fun format(minute: Int): String =
        String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("tpl", templateId)
        put("name", name)
        put("min", minuteOfDay)
        put("done", done)
        put("removed", removed)
        put("custom", customized)
        put("auto", auto)
        put("s", sound)
        put("v", vibrate)
        // 只有用得上才写，老数据读出来仍然是默认值
        if (doneAt >= 0) put("dat", doneAt)
        if (snoozeCount > 0) put("snz", snoozeCount)
        if (failed) put("fail", true)
    }

    companion object {
        fun fromJson(o: JSONObject): DayTask = DayTask(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            templateId = o.optString("tpl", ""),
            name = o.optString("name", ""),
            minuteOfDay = o.optInt("min", 480),
            done = o.optBoolean("done", false),
            removed = o.optBoolean("removed", false),
            customized = o.optBoolean("custom", false),
            auto = o.optBoolean("auto", false),
            sound = o.optBoolean("s", true),
            vibrate = o.optBoolean("v", true),
            // 老数据没有 dat：已打卡的按原定时间算，显示上没有变化
            doneAt = o.optInt("dat", -1),
            snoozeCount = o.optInt("snz", 0),
            failed = o.optBoolean("fail", false)
        )
    }
}

/** 传给悬浮卡片/通知的提醒内容，和具体存储模型解耦 */
data class ReminderItem(
    val id: String,
    val name: String,
    val minuteOfDay: Int,
    val ruleText: String,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    /** 已经延迟过几次，卡片据此决定「稍后」还能不能点 */
    val snoozeCount: Int = 0
) {
    val timeText: String get() = String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
}
