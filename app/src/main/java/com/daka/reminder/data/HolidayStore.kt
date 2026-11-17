package com.daka.reminder.data

import android.content.Context
import com.daka.reminder.util.Dates
import org.json.JSONArray

/**
 * 法定节假日 / 调休补班日。
 *
 * 数据来源两条路：
 *  1. 从手机自带的「节假日」日历读（见 util/CalendarHolidays.kt）
 *  2. 读不到就手动维护（粘贴导入）
 *
 * 优先级：调休补班 > 法定放假 > 按星期推。
 * 也就是说「春节调休的那个周日」只要被标成补班，就算上班日。
 *
 * 每一天最终只落在两类里：
 *  - 上班日 WORKDAY：周一至周五（含调休补班的周末），或表里没标放假的平日
 *  - 休息日 其余全部：普通周末 WEEKEND_OFF + 法定放假 HOLIDAY
 *
 * 「法定上班日」和「休息日」两条排期规则就是按这两类分工的，互不重叠、完全互补。
 */
object HolidayStore {

    /** 上班 / 放假 / 普通周末休息 */
    enum class DayType { WORKDAY, HOLIDAY, WEEKEND_OFF }

    private const val PREF = "daka_store_v3"
    private const val KEY_HOLIDAYS = "holidays"
    private const val KEY_WORKDAYS = "workdays"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun holidays(context: Context): MutableSet<Long> = readSet(context, KEY_HOLIDAYS)
    fun workdays(context: Context): MutableSet<Long> = readSet(context, KEY_WORKDAYS)

    private fun readSet(context: Context, key: String): MutableSet<Long> {
        val raw = prefs(context).getString(key, null) ?: return mutableSetOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableSetOf<Long>()
            for (i in 0 until arr.length()) out.add(arr.optLong(i))
            out
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    private fun writeSet(context: Context, key: String, set: Set<Long>) {
        val arr = JSONArray()
        set.sorted().forEach { arr.put(it) }
        prefs(context).edit().putString(key, arr.toString()).apply()
    }

    fun saveHolidays(context: Context, set: Set<Long>) = writeSet(context, KEY_HOLIDAYS, set)
    fun saveWorkdays(context: Context, set: Set<Long>) = writeSet(context, KEY_WORKDAYS, set)

    /**
     * 合并一批数据进去。已有的保留，只补新的。
     * 返回新增条数。
     */
    fun merge(context: Context, newHolidays: Collection<Long>, newWorkdays: Collection<Long>): Int {
        val h = holidays(context)
        val w = workdays(context)
        var added = 0
        newHolidays.forEach {
            // 同一天不能既是放假又是补班，后写的补班优先
            if (w.contains(it)) return@forEach
            if (h.add(it)) added++
        }
        newWorkdays.forEach {
            h.remove(it)
            if (w.add(it)) added++
        }
        saveHolidays(context, h)
        saveWorkdays(context, w)
        return added
    }

    fun remove(context: Context, day: Long) {
        val h = holidays(context).apply { remove(day) }
        val w = workdays(context).apply { remove(day) }
        saveHolidays(context, h)
        saveWorkdays(context, w)
    }

    fun clear(context: Context) {
        saveHolidays(context, emptySet())
        saveWorkdays(context, emptySet())
    }

    fun count(context: Context): Int = holidays(context).size + workdays(context).size

    /** 这一天是上班日还是休息日 */
    fun dayType(context: Context, day: Long): DayType {
        if (workdays(context).contains(day)) return DayType.WORKDAY
        if (holidays(context).contains(day)) return DayType.HOLIDAY
        return if (Dates.dayIndexOf(Dates.dateOf(day)) < 5) {
            DayType.WORKDAY
        } else {
            DayType.WEEKEND_OFF
        }
    }

    /**
     * 是不是休息日：周末 + 法定放假 − 调休补班。
     * 与「上班日」严格互补，任何一天只会命中一边。
     */
    fun isRestDay(context: Context, day: Long): Boolean = dayType(context, day) != DayType.WORKDAY

    /** 模板的排期规则是否命中这一天 */
    fun matches(context: Context, mode: ScheduleMode, daysMask: Int, day: Long): Boolean {
        return when (mode) {
            ScheduleMode.EVERYDAY -> true
            ScheduleMode.WEEKDAY -> Dates.dayIndexOf(Dates.dateOf(day)) < 5
            ScheduleMode.WEEKEND -> Dates.dayIndexOf(Dates.dateOf(day)) >= 5
            ScheduleMode.LEGAL_WORKDAY -> dayType(context, day) == DayType.WORKDAY
            ScheduleMode.LEGAL_HOLIDAY -> isRestDay(context, day)
            ScheduleMode.CUSTOM -> Dates.isActive(daysMask, Dates.dayIndexOf(Dates.dateOf(day)))
        }
    }
}
