package com.daka.reminder.util

import java.time.LocalDate
import java.time.LocalTime

/**
 * 全项目统一约定：dayIndex 0 = 周一 ... 6 = 周日，对应 daysMask 的第 0..6 位。
 */
object Dates {

    private val CN_DAYS = arrayOf("一", "二", "三", "四", "五", "六", "日")
    private val CN_WEEK = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    const val ALL_DAYS = 0x7F   // 1111111
    const val WEEKDAYS = 0x1F   // 周一到周五
    const val WEEKEND = 0x60    // 周六周日
    const val NOT_SCHEDULED = -1L

    fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    fun dateOf(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    fun dayIndexOf(date: LocalDate): Int = date.dayOfWeek.value - 1

    fun isActive(mask: Int, dayIndex: Int): Boolean = (mask shr dayIndex) and 1 == 1

    /** 当前本地时间的分钟数，用来判断「今天这一条到点没有」 */
    fun nowMinuteOfDay(): Int = LocalTime.now().hour * 60 + LocalTime.now().minute

    /** 把 bitmask 翻译成「每天 / 工作日 / 周一三五」这类人话 */
    fun weekdayText(mask: Int): String {
        val m = mask and ALL_DAYS
        if (m == 0) return "不重复"
        if (m == ALL_DAYS) return "每天"
        if (m == WEEKDAYS) return "工作日"
        if (m == WEEKEND) return "周末"
        val sb = StringBuilder("周")
        for (i in 0..6) {
            if (isActive(m, i)) sb.append(CN_DAYS[i])
        }
        return sb.toString()
    }

    /** 11月4日；未安排时返回「未安排」 */
    fun dateText(epochDay: Long): String {
        if (epochDay == NOT_SCHEDULED) return "未安排"
        val d = dateOf(epochDay)
        return "${d.monthValue}月${d.dayOfMonth}日"
    }

    /** 11月4日 周二 */
    fun dateHeader(epochDay: Long): String {
        val d = dateOf(epochDay)
        return "${d.monthValue}月${d.dayOfMonth}日 ${CN_WEEK[dayIndexOf(d)]}"
    }

    /** 今天 / 明天 / 昨天 / 11月4日 */
    fun relativeLabel(epochDay: Long): String {
        return when (epochDay - todayEpochDay()) {
            0L -> "今天"
            1L -> "明天"
            -1L -> "昨天"
            2L -> "后天"
            -2L -> "前天"
            else -> dateText(epochDay)
        }
    }

}
