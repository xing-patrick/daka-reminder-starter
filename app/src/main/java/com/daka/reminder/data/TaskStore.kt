package com.daka.reminder.data

import android.content.Context
import com.daka.reminder.util.Dates
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 存储分两块：
 *  - templates：任务库模板，不隶属任何日期
 *  - days：按日期存的任务实例，每天一份独立列表
 *
 * 关键规则：
 *  1. 打开某天时，REPEAT 模板按星期规则"展开"成那天的实例（只补一次）
 *  2. 实例从模板复制出来后完全独立，改它不碰模板、不碰其他日期
 *  3. 移除实例用墓碑标记，防止下次打开又被模板补回来
 *  4. ONCE 模板不指定日期，只能由用户在某天主动挑选添加
 */
object TaskStore {

    /**
     * 一条任务最多能「稍后」几次。
     * 每次 10 分钟，3 次 = 往后推半小时；第 3 次延迟的提醒还没处理，
     * 就判这次打卡失败（不再提醒、列表置灰）。
     */
    const val MAX_SNOOZE = 3

    private const val PREF = "daka_store_v3"
    private const val V2_PREF = "daka_store_v2"
    private const val V1_PREF = "daka_store_v1"

    private const val KEY_TEMPLATES = "templates"
    private const val KEY_DAYS = "days"
    private const val KEY_SEEDED = "seeded"
    private const val KEY_MIGRATED = "migrated"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------- 任务库模板 ----------------

    fun templates(context: Context): MutableList<TaskTemplate> {
        val raw = prefs(context).getString(KEY_TEMPLATES, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { TaskTemplate.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveTemplates(context: Context, list: List<TaskTemplate>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_TEMPLATES, arr.toString()).apply()
    }

    fun upsertTemplate(context: Context, template: TaskTemplate) {
        val list = templates(context)
        val idx = list.indexOfFirst { it.id == template.id }
        if (idx >= 0) list[idx] = template else list.add(template)
        saveTemplates(context, list)
    }

    /** 只删模板，所有日期的实例都保留（保留给不需要传导的调用方） */
    fun deleteTemplate(context: Context, id: String) {
        saveTemplates(context, templates(context).filterNot { it.id == id })
    }

    /**
     * 删除模板，并和「改模板」一样把变化传导到未发生的实例。
     *
     * 跟着一起删掉的（与 syncTemplate 的条件完全一致）：
     *  - 日期 >= 今天，且今天这一条还没到原来的提醒时间
     *  - templateId 命中、没被手动改过、没打卡、没被移除
     *
     * 保留：过去日期、今天已过点、已打卡、手动改过、已移除。
     * 模板删掉之后 entriesFor 找不到模板，剩下的实例既不会被补回也不会被清掉。
     *
     * 返回被一起删掉的实例条数。
     */
    fun deleteTemplateWithSync(context: Context, id: String): Int {
        if (findTemplate(context, id) == null) return 0

        val today = Dates.todayEpochDay()
        val nowMin = Dates.nowMinuteOfDay()
        val root = daysRoot(context)

        val keys = mutableListOf<String>()
        val iter = root.keys()
        while (iter.hasNext()) keys.add(iter.next())

        var removed = 0
        for (key in keys) {
            val day = key.toLongOrNull() ?: continue
            if (day < today) continue
            val arr = root.optJSONArray(key) ?: continue

            val kept = JSONArray()
            var touched = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val notHappened = day > today ||
                    (day == today && o.optInt("min", 480) > nowMin)
                val drop = o.optString("tpl") == id &&
                    !o.optBoolean("removed") &&
                    !o.optBoolean("done") &&
                    !o.optBoolean("fail") &&
                    !o.optBoolean("custom") &&
                    notHappened
                if (drop) {
                    touched = true
                    removed++
                } else {
                    kept.put(o)
                }
            }
            if (touched) root.put(key, kept)
        }

        if (removed > 0) saveDaysRoot(context, root)
        deleteTemplate(context, id)
        return removed
    }

    fun findTemplate(context: Context, id: String): TaskTemplate? =
        templates(context).firstOrNull { it.id == id }

    // ---------------- 按日期存实例 ----------------

    private fun daysRoot(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_DAYS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveDaysRoot(context: Context, root: JSONObject) {
        prefs(context).edit().putString(KEY_DAYS, root.toString()).apply()
    }

    /** 原始实例列表（含墓碑），不触发自动展开 */
    private fun rawTasksFor(context: Context, day: Long): MutableList<DayTask> {
        val arr = daysRoot(context).optJSONArray(day.toString()) ?: return mutableListOf()
        return MutableList(arr.length()) { DayTask.fromJson(arr.getJSONObject(it)) }
    }

    private fun saveTasksFor(context: Context, day: Long, list: List<DayTask>) {
        val root = daysRoot(context)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        root.put(day.toString(), arr)
        saveDaysRoot(context, root)
    }

    fun allInstanceIds(context: Context): List<String> {
        val root = daysRoot(context)
        val out = mutableListOf<String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val arr = root.optJSONArray(keys.next()) ?: continue
            for (i in 0 until arr.length()) {
                out.add(arr.optJSONObject(i)?.optString("id") ?: continue)
            }
        }
        return out
    }

    fun findInstance(context: Context, day: Long, id: String): DayTask? =
        rawTasksFor(context, day).firstOrNull { it.id == id && !it.removed }

    /**
     * 取某天的清单：先把命中的重复模板补成实例，再过滤墓碑。
     * 已存在同 templateId 的实例（包括墓碑）就不再补，所以删除后不会被重新加回来。
     */
    fun entriesFor(context: Context, day: Long): List<DayTask> {
        val tasks = rawTasksFor(context, day)
        val allTemplates = templates(context)
        val tplById = allTemplates.associateBy { it.id }
        var changed = false

        // 规则或节假日表变了之后，把「自动展开、还没发生、也没被手动改过」、
        // 但现在不再命中规则的实例撤掉。过去日期一律不动，保留历史记录。
        if (day >= Dates.todayEpochDay()) {
            val kept = tasks.filter { t ->
                // 手动放进来的、改过的、打过卡的、已判失败的、已移除的，一律保留
                if (!t.auto || t.removed || t.templateId.isBlank() ||
                    t.done || t.failed || t.customized
                ) return@filter true
                val tpl = tplById[t.templateId] ?: return@filter true
                if (tpl.kind != TemplateKind.REPEAT) return@filter true
                HolidayStore.matches(context, tpl.scheduleMode, tpl.daysMask, day)
            }
            if (kept.size != tasks.size) {
                tasks.clear()
                tasks.addAll(kept)
                changed = true
            }
        }

        val seenTemplateIds = tasks.map { it.templateId }.toMutableSet()

        for (t in allTemplates) {
            if (t.kind != TemplateKind.REPEAT) continue
            if (day < t.createdEpochDay) continue
            if (!HolidayStore.matches(context, t.scheduleMode, t.daysMask, day)) continue
            if (seenTemplateIds.contains(t.id)) continue

            tasks.add(
                DayTask(
                    templateId = t.id,
                    name = t.name,
                    minuteOfDay = t.minuteOfDay,
                    auto = true,
                    sound = t.sound,
                    vibrate = t.vibrate
                )
            )
            seenTemplateIds.add(t.id)
            changed = true
        }

        if (changed) saveTasksFor(context, day, tasks)
        return tasks.filterNot { it.removed }.sortedBy { it.minuteOfDay }
    }

    /** 排期前调用，确保未来几天的实例已经生成 */
    fun ensureDay(context: Context, day: Long) {
        entriesFor(context, day)
    }

    fun progress(context: Context, day: Long): Pair<Int, Int> {
        val list = entriesFor(context, day)
        return list.count { it.done } to list.size
    }

    // ---------------- 实例操作（全部只作用于这一天） ----------------

    /** 从任务库挑一个模板，添加到指定日期 */
    fun addFromTemplate(context: Context, day: Long, templateId: String): Boolean {
        val t = findTemplate(context, templateId) ?: return false
        val tasks = rawTasksFor(context, day)
        tasks.add(
            DayTask(
                templateId = t.id,
                name = t.name,
                minuteOfDay = t.minuteOfDay,
                sound = t.sound,
                vibrate = t.vibrate
            )
        )
        saveTasksFor(context, day, tasks)
        return true
    }

    /** 直接在这天新建一个临时任务，不进任务库 */
    fun addStandalone(
        context: Context,
        day: Long,
        name: String,
        minuteOfDay: Int,
        sound: Boolean = true,
        vibrate: Boolean = true
    ): DayTask {
        val task = DayTask(
            name = name,
            minuteOfDay = minuteOfDay,
            sound = sound,
            vibrate = vibrate
        )
        val tasks = rawTasksFor(context, day)
        tasks.add(task)
        saveTasksFor(context, day, tasks)
        return task
    }

    /** 编辑实例：只改这一条，并打上「已定制」，之后模板再改也跳过它 */
    fun updateInstance(
        context: Context,
        day: Long,
        id: String,
        name: String,
        minuteOfDay: Int,
        sound: Boolean = true,
        vibrate: Boolean = true
    ) {
        val tasks = rawTasksFor(context, day)
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return
        tasks[idx] = tasks[idx].copy(
            name = name,
            minuteOfDay = minuteOfDay,
            sound = sound,
            vibrate = vibrate,
            customized = true
        )
        saveTasksFor(context, day, tasks)
    }

    /**
     * 改了任务库模板之后调用：把新名称/新时间传导到还没发生的实例。
     *
     * 只处理满足全部条件的实例：
     *  - 日期 >= 今天（过去的一律不动）
     *  - 今天这一条必须「还没到原来的提醒时间」
     *  - templateId 命中、没被手动改过、没打卡、没被移除
     *
     * 返回被更新的条数。模板类型变化（REPEAT ↔ ONCE）不做增删，避免误删数据。
     */
    fun syncTemplate(context: Context, tpl: TaskTemplate): Int {
        val today = Dates.todayEpochDay()
        val nowMin = Dates.nowMinuteOfDay()
        val root = daysRoot(context)

        val keys = mutableListOf<String>()
        val it = root.keys()
        while (it.hasNext()) keys.add(it.next())

        var count = 0
        for (key in keys) {
            val day = key.toLongOrNull() ?: continue
            if (day < today) continue
            val arr = root.optJSONArray(key) ?: continue
            var touched = false

            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("tpl") != tpl.id) continue
                if (o.optBoolean("removed")) continue
                if (o.optBoolean("done")) continue
                if (o.optBoolean("fail")) continue
                if (o.optBoolean("custom")) continue

                val oldMinute = o.optInt("min", 480)
                val notHappened = day > today || (day == today && oldMinute > nowMin)
                if (!notHappened) continue

                o.put("name", tpl.name)
                o.put("min", tpl.minuteOfDay)
                o.put("s", tpl.sound)
                o.put("v", tpl.vibrate)
                touched = true
                count++
            }
            if (touched) root.put(key, arr)
        }
        if (count > 0) saveDaysRoot(context, root)
        return count
    }

    fun toggleDone(context: Context, day: Long, id: String): Boolean {
        val instance = findInstance(context, day, id) ?: return false
        val now = !instance.done
        setDone(context, day, id, now)
        return now
    }

    /**
     * 打卡 / 取消打卡。
     *
     * 打卡成功时把**实际时刻**记进 doneAt，列表和卡片的时间就跟着变成这个时间：
     * 提前打卡、延后打卡、从悬浮卡片打卡、在 App 里点一下，
     * 全部走这一个入口，所以几种方式记下来的时间完全一致。
     *
     * 打卡成功同时把 snoozeCount 归零、清掉 failed —— 已经完成了，
     * 之前延迟几次都无所谓，后续也不会再排提醒。
     *
     * 对已经打过卡的那条**再点一次不会改变已记录的时间**：列表双击、
     * 重复的闹钟广播都可能把同一 action 送进来第二次，如果刷新 doneAt，
     * 第一次记录的真实时间就丢了。
     */
    fun setDone(context: Context, day: Long, id: String, done: Boolean) {
        val tasks = rawTasksFor(context, day)
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return
        val old = tasks[idx]
        tasks[idx] = when {
            // 取消打卡：回到原定时间
            !done -> old.copy(done = false, doneAt = -1, snoozeCount = 0)
            // 已经完成过的：原样保留，不刷新时间
            old.done -> old
            // 新打卡：记下此刻
            else -> old.copy(
                done = true,
                doneAt = Dates.nowMinuteOfDay(),
                snoozeCount = 0,
                failed = false
            )
        }
        saveTasksFor(context, day, tasks)
    }

    /**
     * 「稍后」次数 +1，返回累计次数。
     * 调用方拿返回值和 MAX_SNOOZE 比，决定还能不能继续延迟。
     */
    fun bumpSnooze(context: Context, day: Long, id: String): Int {
        val tasks = rawTasksFor(context, day)
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return 0
        val next = tasks[idx].snoozeCount + 1
        tasks[idx] = tasks[idx].copy(snoozeCount = next)
        saveTasksFor(context, day, tasks)
        return next
    }

    /**
     * 延迟次数用尽仍未完成 → 判定打卡失败。
     * 失败后：不再排任何提醒、列表置灰。仍然可以手动点一下补上。
     */
    fun markFailed(context: Context, day: Long, id: String): Boolean {
        val tasks = rawTasksFor(context, day)
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return false
        if (tasks[idx].done) return false
        tasks[idx] = tasks[idx].copy(failed = true, snoozeCount = MAX_SNOOZE)
        saveTasksFor(context, day, tasks)
        return true
    }

    /** 从这天移除，留墓碑防止重复模板自动补回来 */
    fun removeInstance(context: Context, day: Long, id: String) {
        val tasks = rawTasksFor(context, day)
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return
        val old = tasks[idx]
        tasks[idx] = old.copy(removed = true, done = false, doneAt = -1, snoozeCount = 0, failed = false)
        saveTasksFor(context, day, tasks)
    }

    // ---------------- 初始化 / 迁移 ----------------

    fun seedIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_SEEDED, false)) return
        if (templates(context).isEmpty()) {
            val today = Dates.todayEpochDay()
            saveTemplates(
                context,
                listOf(
                    TaskTemplate(name = "早起", minuteOfDay = 7 * 60, kind = TemplateKind.REPEAT,
                        scheduleMode = ScheduleMode.WEEKDAY, daysMask = Dates.WEEKDAYS,
                        createdEpochDay = today),
                    TaskTemplate(name = "睡前阅读", minuteOfDay = 22 * 60 + 30,
                        kind = TemplateKind.REPEAT, scheduleMode = ScheduleMode.EVERYDAY,
                        daysMask = Dates.ALL_DAYS, createdEpochDay = today),
                    TaskTemplate(name = "买牛奶", minuteOfDay = 18 * 60, kind = TemplateKind.ONCE,
                        createdEpochDay = today)
                )
            )
        }
        p.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    private fun splitKey(k: String): Pair<String, Long>? {
        val pos = k.lastIndexOf(':')
        if (pos <= 0) return null
        val day = k.substring(pos + 1).toLongOrNull() ?: return null
        return k.substring(0, pos) to day
    }

    /**
     * 从 v2 迁移：模板照搬（ONCE 丢掉原来的日期，改成不指定日期），
     * 完成/跳过记录转成对应日期的实例；ONCE 原本安排在未来的日子也补成实例。
     */
    fun migrateIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        p.edit().putBoolean(KEY_MIGRATED, true).apply()

        val old = context.getSharedPreferences(V2_PREF, Context.MODE_PRIVATE)
        val raw = old.getString("tasks", null) ?: run {
            migrateV1(context)
            return
        }
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return
        }
        if (arr.length() == 0) return

        val list = mutableListOf<TaskTemplate>()
        val info = mutableMapOf<String, Pair<String, Int>>()   // id -> (name, minute)
        val byDay = mutableMapOf<Long, MutableList<DayTask>>()
        val today = Dates.todayEpochDay()

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "")
            if (name.isBlank()) continue
            val id = o.optString("id").ifBlank { UUID.randomUUID().toString() }
            val min = o.optInt("min", 480)
            val kind = if (o.optString("kind", "REPEAT") == "ONCE") {
                TemplateKind.ONCE
            } else {
                TemplateKind.REPEAT
            }
            val mask = o.optInt("days", Dates.ALL_DAYS)
            list.add(
                TaskTemplate(id = id, name = name, minuteOfDay = min, kind = kind,
                    scheduleMode = TaskTemplate.modeFromMask(mask), daysMask = mask,
                    createdEpochDay = today)
            )
            info[id] = name to min

            if (kind == TemplateKind.ONCE) {
                val sched = o.optLong("sched", -1L)
                if (sched >= today) {
                    byDay.getOrPut(sched) { mutableListOf() }
                        .add(DayTask(templateId = id, name = name, minuteOfDay = min))
                }
            }
        }

        // 完成记录
        for (k in old.getStringSet("done", null) ?: emptySet()) {
            val (id, day) = splitKey(k) ?: continue
            val (name, min) = info[id] ?: continue
            val bucket = byDay.getOrPut(day) { mutableListOf() }
            if (bucket.none { it.templateId == id }) {
                bucket.add(DayTask(templateId = id, name = name, minuteOfDay = min, done = true))
            } else {
                val idx = bucket.indexOfFirst { it.templateId == id }
                bucket[idx] = bucket[idx].copy(done = true)
            }
        }

        // 跳过记录 -> 墓碑
        for (k in old.getStringSet("skip", null) ?: emptySet()) {
            val (id, day) = splitKey(k) ?: continue
            val (name, min) = info[id] ?: continue
            val bucket = byDay.getOrPut(day) { mutableListOf() }
            if (bucket.none { it.templateId == id }) {
                bucket.add(DayTask(templateId = id, name = name, minuteOfDay = min, removed = true))
            } else {
                val idx = bucket.indexOfFirst { it.templateId == id }
                bucket[idx] = bucket[idx].copy(removed = true, done = false)
            }
        }

        if (list.isNotEmpty()) saveTemplates(context, list)
        for ((day, tasks) in byDay) saveTasksFor(context, day, tasks)
    }

    /** 更老的 v1：只有重复任务，一律转成 REPEAT 模板 */
    private fun migrateV1(context: Context) {
        val old = context.getSharedPreferences(V1_PREF, Context.MODE_PRIVATE)
        val raw = old.getString("items", null) ?: return
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return
        }
        if (arr.length() == 0) return

        val list = mutableListOf<TaskTemplate>()
        val today = Dates.todayEpochDay()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "")
            if (name.isBlank()) continue
            val mask = o.optInt("days", Dates.ALL_DAYS)
            list.add(
                TaskTemplate(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = name,
                    minuteOfDay = o.optInt("min", 480),
                    kind = TemplateKind.REPEAT,
                    scheduleMode = TaskTemplate.modeFromMask(mask),
                    daysMask = mask,
                    createdEpochDay = today
                )
            )
        }
        if (list.isNotEmpty()) saveTemplates(context, list)
    }

    fun clearAll(context: Context) {
        prefs(context).edit()
            .remove(KEY_TEMPLATES)
            .remove(KEY_DAYS)
            .apply()
    }
}
