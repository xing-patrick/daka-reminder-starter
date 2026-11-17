package com.daka.reminder.data

import com.daka.reminder.util.Dates
import java.time.LocalDate

/**
 * 批量导入解析器。
 *
 * 一行一条，格式「名称 时间 规则」。规则用关键词明确指定，不再靠猜星期：
 *
 *   早起 07:00 每天              -> 重复 · 每天
 *   喝水 09:30 工作日            -> 重复 · 工作日（周一到周五）
 *   游泳 15:00 周末              -> 重复 · 周末
 *   阅读 21:00 一三五            -> 重复 · 自定义星期
 *   跑步 20:00 1-5              -> 重复 · 周一到周五
 *   收邮件 09:00 法定上班日       -> 重复 · 跳过法定节假日，调休补班也提醒
 *   大扫除 10:00 休息日           -> 重复 · 周末 + 法定假都提醒，调休补班不提醒
 *   买菜 17:00 法定节假日         -> 同上（旧写法继续认，语义已统一为「休息日」）
 *   买牛奶 18:00 单日            -> 单日模板（不指定日期）
 *   牙医 10:00 11月8日           -> 单日模板（日期只用于判定，不绑定）
 *   早睡 23:00                  -> 不写规则默认每天
 *   冥想                        -> 不写时间默认 08:00、每天
 *
 * 单日模板在库里不绑定日期，需要用户在某一天点「从库添加」才会出现。
 * 规则看不懂的行会被列为「无法识别」，不会悄悄变成每天。
 *
 * 以 # 或 // 开头的行视为注释跳过。
 */
object ImportParser {

    data class Result(
        val items: List<TaskTemplate>,
        val failed: List<String>
    )

    private val TIME_COLON = Regex("""(\d{1,2})\s*[:：]\s*(\d{1,2})""")
    private val TIME_CN = Regex("""(\d{1,2})\s*[点时]\s*(?:(\d{1,2})\s*分?)?""")

    private val RE_DATE_CN = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*日?""")
    private val RE_DATE_SLASH = Regex("""(\d{1,2})\s*/\s*(\d{1,2})""")

    private val CN_HEADS = mapOf(
        '一' to 0, '二' to 1, '三' to 2, '四' to 3,
        '五' to 4, '六' to 5, '日' to 6, '天' to 6
    )

    private val RE_EVERY = Regex("每天|每日|天天|每一天|日日|every\\s*day|daily", RegexOption.IGNORE_CASE)
    private val RE_RANGE = Regex(
        """[周星期礼拜]{0,2}\s*([一二三四五六日天])\s*(?:至|到|-|~|—|－)\s*[周星期礼拜]{0,2}\s*([一二三四五六日天])"""
    )
    private val RE_RANGE_NUM = Regex("""([1-7])\s*(?:至|到|-|~|—)\s*([1-7])""")
    private val RE_PREFIXED = Regex("""(?:周|星期|礼拜)\s*([一二三四五六日天])""")
    private val RE_NUMBER = Regex("""(?<![\d:：])([1-7])(?![\d:：])""")

    private val SEPARATORS = charArrayOf(
        '，', ',', '、', '-', '–', ':', '：', '|', '/', '\\', '\t', '。', ';', '；'
    )

    /** 明确写「只做一次」的词，优先于任何星期判定 */
    private val K_ONCE = listOf(
        "单日", "单次", "仅一次", "只做一次", "不重复", "单天", "单次日"
    )
    /** 休息日（周末 + 法定假 − 调休补班）。旧的「节假日」「放假」写法继续认 */
    private val K_LEGAL_HOLIDAY = listOf(
        "休息日", "休息", "法定节假日", "法定假日", "法定假期", "法定节假", "法定假",
        "节假日", "休假日", "假期", "放假"
    )
    /** 法定上班日（含调休补班） */
    private val K_LEGAL_WORKDAY = listOf(
        "法定工作日", "法定上班日", "法定上班", "法定班",
        "调休", "补班", "按法定"
    )
    private val K_WEEKDAY = listOf("工作日", "平日", "上班日")
    private val K_WEEKEND = listOf("周末", "双休")

    private data class Rule(
        val kind: TemplateKind,
        val mode: ScheduleMode,
        val mask: Int
    )

    fun parse(text: String): Result {
        val ok = mutableListOf<TaskTemplate>()
        val bad = mutableListOf<String>()

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#") || line.startsWith("//")) return@forEach

            val item = parseLine(line)
            if (item == null) bad.add(line) else ok.add(item)
        }
        return Result(ok, bad)
    }

    private fun parseLine(line: String): TaskTemplate? {
        val colonMatch = TIME_COLON.find(line)
        val cnMatch = TIME_CN.find(line)

        val match = when {
            colonMatch != null && cnMatch != null ->
                if (colonMatch.range.first <= cnMatch.range.first) colonMatch else cnMatch
            colonMatch != null -> colonMatch
            cnMatch != null -> cnMatch
            else -> null
        }

        var name: String
        var minuteOfDay: Int
        var ruleText: String

        if (match != null) {
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues.getOrNull(2)
                ?.takeIf { it.isNotBlank() }
                ?.toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59) return null

            name = line.substring(0, match.range.first)
            ruleText = line.substring(match.range.last + 1)
            minuteOfDay = hour * 60 + minute
        } else {
            name = line
            ruleText = ""
            minuteOfDay = 8 * 60
        }

        name = name.trim().trim(*SEPARATORS).trim()
        if (name.isBlank()) return null
        if (name.length > 40) name = name.take(40)

        val rule = parseRule(ruleText) ?: return null

        return TaskTemplate(
            name = name,
            minuteOfDay = minuteOfDay,
            kind = rule.kind,
            scheduleMode = rule.mode,
            daysMask = rule.mask
        )
    }

    /**
     * 关键词优先、明确匹配。返回 null 表示这行规则看不懂，
     * 交给上层标成「无法识别」，绝不静默退化成「每天」。
     */
    private fun parseRule(raw: String): Rule? {
        val t = raw.trim().trim(',', '，', '、', '|').trim()
        if (t.isEmpty()) {
            return Rule(TemplateKind.REPEAT, ScheduleMode.EVERYDAY, Dates.ALL_DAYS)
        }

        // 1) 单日 / 单次
        if (K_ONCE.any { t.contains(it) }) {
            return Rule(TemplateKind.ONCE, ScheduleMode.EVERYDAY, Dates.ALL_DAYS)
        }

        // 2) 法定节假日（放在「工作日」之前，避免「法定工作日」被截胡）
        if (K_LEGAL_HOLIDAY.any { t.contains(it) }) {
            return Rule(TemplateKind.REPEAT, ScheduleMode.LEGAL_HOLIDAY, 0)
        }

        // 3) 法定上班日 / 调休补班
        if (K_LEGAL_WORKDAY.any { t.contains(it) }) {
            return Rule(TemplateKind.REPEAT, ScheduleMode.LEGAL_WORKDAY, Dates.WEEKDAYS)
        }

        // 4) 写了具体日期（X月X日 / X/X）也当单日模板。日期本身不绑定，只用来判定类型
        if (hasDateToken(t)) {
            return Rule(TemplateKind.ONCE, ScheduleMode.EVERYDAY, Dates.ALL_DAYS)
        }

        // 5) 每天
        if (RE_EVERY.containsMatchIn(t)) {
            return Rule(TemplateKind.REPEAT, ScheduleMode.EVERYDAY, Dates.ALL_DAYS)
        }

        // 6) 工作日 / 周末
        if (K_WEEKDAY.any { t.contains(it) }) {
            return Rule(TemplateKind.REPEAT, ScheduleMode.WEEKDAY, Dates.WEEKDAYS)
        }
        if (K_WEEKEND.any { t.contains(it) }) {
            return Rule(TemplateKind.REPEAT, ScheduleMode.WEEKEND, Dates.WEEKEND)
        }

        // 7) 自定义星期
        val mask = parseDays(t)
        if (mask != 0) {
            return Rule(TemplateKind.REPEAT, ScheduleMode.CUSTOM, mask)
        }

        // 8) 看不懂：宁可标失败，也不悄悄变成每天
        return null
    }

    private fun hasDateToken(t: String): Boolean =
        RE_DATE_CN.containsMatchIn(t) || RE_DATE_SLASH.containsMatchIn(t)

    /** 只用来判断这一行是不是「单日模板」：识别「11月8日」/「12/20」，返回值本身不用 */
    fun parseDate(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty()) return null

        val m = RE_DATE_CN.find(t) ?: RE_DATE_SLASH.find(t) ?: return null
        val month = m.groupValues[1].toIntOrNull() ?: return null
        val day = m.groupValues[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null

        return try {
            val today = LocalDate.now()
            var date = LocalDate.of(today.year, month, day)
            if (date.toEpochDay() < today.toEpochDay()) {
                date = LocalDate.of(today.year + 1, month, day)
            }
            date.toEpochDay()
        } catch (e: Exception) {
            null
        }
    }

    /** 解析出星期 bitmask；识别不了返回 0 */
    fun parseDays(raw: String): Int {
        val t = raw.trim()
        if (t.isEmpty()) return Dates.ALL_DAYS
        if (RE_EVERY.containsMatchIn(t)) return Dates.ALL_DAYS
        if (K_WEEKDAY.any { t.contains(it) }) return Dates.WEEKDAYS
        if (K_WEEKEND.any { t.contains(it) }) return Dates.WEEKEND

        var mask = 0

        RE_RANGE.findAll(t).forEach { m ->
            val a = CN_HEADS[m.groupValues[1].first()]
            val b = CN_HEADS[m.groupValues[2].first()]
            if (a != null && b != null) {
                if (a <= b) {
                    for (i in a..b) mask = mask or (1 shl i)
                } else {
                    mask = mask or (1 shl a) or (1 shl b)
                }
            }
        }

        RE_RANGE_NUM.findAll(t).forEach { m ->
            val a = m.groupValues[1].toInt() - 1
            val b = m.groupValues[2].toInt() - 1
            if (a in 0..6 && b in 0..6) {
                if (a <= b) {
                    for (i in a..b) mask = mask or (1 shl i)
                } else {
                    mask = mask or (1 shl a) or (1 shl b)
                }
            }
        }

        RE_PREFIXED.findAll(t).forEach { m ->
            CN_HEADS[m.groupValues[1].first()]?.let { mask = mask or (1 shl it) }
        }

        RE_NUMBER.findAll(t).forEach { m ->
            val idx = m.groupValues[1].toInt() - 1
            if (idx in 0..6) mask = mask or (1 shl idx)
        }

        // 裸汉字连写：一三五 / 二四六。
        // 必须整串都是星期汉字才认，否则「单日」「节假日」里的「日」会被误当成周日。
        val compact = t.replace(Regex("""(?:周|星期|礼拜)"""), "")
            .filter { !it.isWhitespace() && it !in ",、|/\\，；;" }
        if (compact.isNotEmpty() && compact.all { CN_HEADS.containsKey(it) }) {
            compact.forEach { ch -> CN_HEADS[ch]?.let { mask = mask or (1 shl it) } }
        }

        return mask and Dates.ALL_DAYS
    }
}
