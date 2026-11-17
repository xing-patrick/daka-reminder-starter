package com.daka.reminder.data

import java.time.LocalDate

/**
 * 手动粘贴导入节假日 / 调休。
 *
 * 支持的写法（一行一条）：
 *   10月1日 休
 *   2026-10-01 放假
 *   10/1-10/7 休          <- 区间，整段都标成放假
 *   10月11日 班           <- 调休补班
 *   2026/9/27 补班
 *   10.1~10.7 放假
 *
 * 不写「班 / 补班 / 上班」就默认按放假处理。
 * 不写年份时用今年；如果这个日子已经过去半年以上，自动算到明年。
 */
object HolidayParser {

    data class Result(
        val holidays: Set<Long>,
        val workdays: Set<Long>,
        val failed: List<String>
    )

    /** (年)? 月 分隔符 日 */
    private val RE_DATE = Regex(
        """(?:(\d{4})\s*[-/.]\s*)?(\d{1,2})\s*(?:月|[-/.])\s*(\d{1,2})\s*日?"""
    )
    private val RANGE_HINT = Regex("""[~～—－–至到]""")

    fun parse(text: String): Result {
        val holidays = mutableSetOf<Long>()
        val workdays = mutableSetOf<Long>()
        val failed = mutableListOf<String>()
        val today = LocalDate.now()
        val todayEpoch = today.toEpochDay()

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach

            val matches = RE_DATE.findAll(line).toList()
            if (matches.isEmpty()) {
                failed.add(line)
                return@forEach
            }

            val days = matches.mapNotNull { m ->
                val year = m.groupValues[1].toIntOrNull()
                val month = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val day = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
                if (month !in 1..12 || day !in 1..31) return@mapNotNull null
                try {
                    val y = year ?: pickYear(month, day, today, todayEpoch)
                    LocalDate.of(y, month, day).toEpochDay()
                } catch (e: Exception) {
                    null
                }
            }
            if (days.isEmpty()) {
                failed.add(line)
                return@forEach
            }

            val isWork = line.contains("班") || line.contains("补班") ||
                line.contains("上班") || line.contains("调休上")
            val bucket = if (isWork) workdays else holidays

            if (days.size >= 2 && (RANGE_HINT.containsMatchIn(line) || line.contains("-"))) {
                val a = days.minOrNull() ?: return@forEach
                val b = days.maxOrNull() ?: return@forEach
                // 最多铺 40 天，避免脏数据把整年填满
                if (b - a <= 40) {
                    for (d in a..b) bucket.add(d)
                } else {
                    failed.add(line)
                    return@forEach
                }
            } else {
                days.forEach { bucket.add(it) }
            }
        }

        // 同一天既放假又补班时，补班优先
        holidays.removeAll(workdays)
        return Result(holidays, workdays, failed)
    }

    private fun pickYear(month: Int, day: Int, today: LocalDate, todayEpoch: Long): Int {
        return try {
            val thisYear = LocalDate.of(today.year, month, day).toEpochDay()
            if (thisYear < todayEpoch - 180) today.year + 1 else today.year
        } catch (e: Exception) {
            today.year
        }
    }
}
