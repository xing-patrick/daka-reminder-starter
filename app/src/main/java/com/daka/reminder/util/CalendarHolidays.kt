package com.daka.reminder.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId

/**
 * 从手机自带的日历里读法定节假日和调休。
 *
 * 各家的日历命名不统一，小米 / 华为 / OPPO / vivo 一般都有一个订阅型日历，
 * 名字大致是「节假日」「中国节假日」「假期」「法定节假日」「Holidays」。
 * 这里按关键词找，找到就读它的全天事件：
 *  - 标题里含「班」→ 调休补班（这天要上班）
 *  - 其余 → 法定放假
 *
 * 读不到不算错，只是返回空集合，上层会用手动维护的表兜底。
 */
object CalendarHolidays {

    data class Result(
        val holidays: Set<Long>,
        val workdays: Set<Long>,
        val sourceNames: List<String>,
        val eventCount: Int,
        val error: String? = null
    )

    private val CAL_NAME_KEYS = listOf(
        "节假日", "法定", "放假", "假期", "节日", "holiday", "中国节"
    )

    /** 标题里出现这些字，说明是调休补班而不是放假 */
    private val WORK_KEYS = listOf("班", "上班", "补班", "调休上班")

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val SPAN_DAYS = 400L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

    fun read(context: Context): Result {
        if (!hasPermission(context)) {
            return Result(emptySet(), emptySet(), emptyList(), 0, "没有日历权限")
        }

        val calendarIds = mutableMapOf<Long, String>()
        try {
            val proj = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME
            )
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI, proj, null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val display = c.getString(1).orEmpty()
                    val account = c.getString(2).orEmpty()
                    val hay = (display + " " + account).lowercase()
                    if (CAL_NAME_KEYS.any { hay.contains(it) }) {
                        calendarIds[id] = display.ifBlank { account }
                    }
                }
            }
        } catch (e: Exception) {
            return Result(emptySet(), emptySet(), emptyList(), 0, "读日历列表失败：${e.message}")
        }

        if (calendarIds.isEmpty()) {
            return Result(emptySet(), emptySet(), emptyList(), 0, "没找到节假日日历")
        }

        val today = Dates.todayEpochDay()
        val from = (today - SPAN_DAYS) * DAY_MS
        val to = (today + SPAN_DAYS) * DAY_MS

        val holidays = mutableSetOf<Long>()
        val workdays = mutableSetOf<Long>()
        var eventCount = 0

        try {
            val proj = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY
            )
            for ((calId, _) in calendarIds) {
                val sel = "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
                    "${CalendarContract.Events.DTSTART} >= ? AND " +
                    "${CalendarContract.Events.DTSTART} <= ?"
                val args = arrayOf(calId.toString(), from.toString(), to.toString())
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI, proj, sel, args, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val title = c.getString(0).orEmpty()
                        val start = c.getLong(1)
                        var end = safeEnd(c)
                        val allDay = c.getInt(3) == 1

                        val firstDay: Long
                        val lastDay: Long
                        if (allDay) {
                            // 全天事件的 dtstart/dtend 是 UTC 零点，直接整除即可
                            firstDay = floorDay(start)
                            lastDay = if (end > start) ceilDay(end) - 1 else firstDay
                        } else {
                            firstDay = localDay(start)
                            lastDay = localDay(end)
                        }
                        if (lastDay < firstDay) continue

                        eventCount++
                        val bucket = if (isWorkday(title)) workdays else holidays
                        // 一个假期最多铺 30 天，防止脏数据把整年都填满
                        val stop = minOf(lastDay, firstDay + 30)
                        for (d in firstDay..stop) bucket.add(d)
                    }
                }
            }
        } catch (e: Exception) {
            return Result(emptySet(), emptySet(), calendarIds.values.toList(), eventCount,
                "读节假日事件失败：${e.message}")
        }

        // 同一天既被标放假又被标补班时，补班优先
        holidays.removeAll(workdays)

        return Result(holidays, workdays, calendarIds.values.toList(), eventCount, null)
    }

    private fun isWorkday(title: String): Boolean =
        WORK_KEYS.any { title.contains(it) }

    private fun safeEnd(c: Cursor): Long {
        val end = c.getLong(2)
        val start = c.getLong(1)
        return if (end <= 0) start else end
    }

    private fun floorDay(utcMillis: Long): Long {
        return if (utcMillis >= 0) utcMillis / DAY_MS else (utcMillis - DAY_MS + 1) / DAY_MS
    }

    private fun ceilDay(utcMillis: Long): Long {
        return if (utcMillis >= 0) {
            (utcMillis + DAY_MS - 1) / DAY_MS
        } else {
            utcMillis / DAY_MS
        }
    }

    /** 非全天事件：按本地时区算落在哪天 */
    private fun localDay(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
}
