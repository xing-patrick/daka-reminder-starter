package com.daka.reminder.ui

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.daka.reminder.R
import com.daka.reminder.alarm.AlarmScheduler
import com.daka.reminder.data.HolidayParser
import com.daka.reminder.data.HolidayStore
import com.daka.reminder.data.TaskStore
import com.daka.reminder.util.CalendarHolidays
import com.daka.reminder.util.Dates
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 法定节假日 / 调休管理。
 *
 * 两条数据来源：从手机日历同步，或手动粘贴导入。
 * 改完之后未来日期的实例会按新规则重新展开、重新排闹钟。
 */
class HolidayActivity : AppCompatActivity() {

    /** true = 调休补班，false = 法定放假 */
    private val rows = mutableListOf<Pair<Long, Boolean>>()
    private lateinit var adapter: HolidayAdapter
    private lateinit var tvSummary: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var rv: RecyclerView

    private val calendarPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) doSync() else {
            Toast.makeText(this, R.string.holiday_perm_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_holiday)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        tvSummary = findViewById(R.id.tv_holiday_summary)
        tvEmpty = findViewById(R.id.tv_holiday_empty)
        rv = findViewById(R.id.rv_holiday)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = HolidayAdapter()
        rv.adapter = adapter

        findViewById<View>(R.id.btn_sync).setOnClickListener {
            if (CalendarHolidays.hasPermission(this)) {
                doSync()
            } else {
                calendarPermission.launch(Manifest.permission.READ_CALENDAR)
            }
        }
        findViewById<View>(R.id.btn_import).setOnClickListener { showImportDialog() }
        findViewById<View>(R.id.btn_clear).setOnClickListener { confirmClear() }

        refresh()
    }

    private fun doSync() {
        val result = CalendarHolidays.read(this)
        if (result.holidays.isEmpty() && result.workdays.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.holiday_sync)
                .setMessage(
                    (result.error ?: "手机日历里没有读到节假日事件") +
                        "\n\n可以点「手动导入」自己粘一份，格式示例：\n" +
                        "10月1日 休\n10月1日-10月7日 休\n10月11日 班"
                )
                .setPositiveButton(R.string.action_close, null)
                .show()
            return
        }
        val added = HolidayStore.merge(this, result.holidays, result.workdays)
        afterChange(
            "已从 ${result.sourceNames.joinToString("、")} 读到 ${result.eventCount} 条事件，" +
                "新增 $added 天"
        )
    }

    private fun showImportDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_holiday_import, null)
        val et = view.findViewById<EditText>(R.id.et_holiday_import)
        val preview = view.findViewById<TextView>(R.id.tv_holiday_preview)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.holiday_import)
            .setView(view)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val parsed = HolidayParser.parse(et.text?.toString().orEmpty())
                if (parsed.holidays.isEmpty() && parsed.workdays.isEmpty()) {
                    Toast.makeText(this, R.string.import_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val added = HolidayStore.merge(this, parsed.holidays, parsed.workdays)
                val msg = buildString {
                    append("导入 $added 天")
                    if (parsed.failed.isNotEmpty()) append("，${parsed.failed.size} 行无法识别")
                }
                afterChange(msg)
            }
            .create()

        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        et.doAfterTextChanged { text ->
            val raw = text?.toString().orEmpty()
            if (raw.isBlank()) {
                preview.visibility = View.GONE
                return@doAfterTextChanged
            }
            val parsed = HolidayParser.parse(raw)
            preview.visibility = View.VISIBLE
            preview.text = buildString {
                append("放假 ${parsed.holidays.size} 天，补班 ${parsed.workdays.size} 天")
                if (parsed.failed.isNotEmpty()) append("，${parsed.failed.size} 行无法识别")
            }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.holiday_clear)
            .setMessage("清空后，「法定上班日 / 休息日」规则会退回按普通星期判断（工作日 = 周一到周五，休息日 = 周末）。已生成的打卡记录不会删。")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                HolidayStore.clear(this)
                afterChange("已清空")
            }
            .show()
    }

    private fun afterChange(message: String) {
        // 节假日表变了，未来各天的实例要按新规则重新展开
        val today = Dates.todayEpochDay()
        for (offset in 0..8) TaskStore.ensureDay(this, today + offset)
        AlarmScheduler.scheduleAll(this)
        refresh()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun refresh() {
        val holidays = HolidayStore.holidays(this).toList()
        val workdays = HolidayStore.workdays(this).toList()

        rows.clear()
        holidays.forEach { rows.add(it to false) }
        workdays.forEach { rows.add(it to true) }
        rows.sortBy { it.first }

        tvSummary.text = "共 ${rows.size} 天 · 放假 ${holidays.size} 天 / 补班 ${workdays.size} 天"
        tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    private inner class HolidayAdapter : RecyclerView.Adapter<HolidayAdapter.VH>() {

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_holiday, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(rows[position])

        private inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvDate = view.findViewById<TextView>(R.id.tv_holiday_date)
            private val tvType = view.findViewById<TextView>(R.id.tv_holiday_type)
            private val tvRemove = view.findViewById<TextView>(R.id.tv_holiday_remove)

            fun bind(row: Pair<Long, Boolean>) {
                val (day, isWork) = row
                val date = Dates.dateOf(day)
                tvDate.text = "${date.year}年${Dates.dateHeader(day)}"
                tvType.text = getString(
                    if (isWork) R.string.holiday_type_workday else R.string.holiday_type_holiday
                )
                tvRemove.setOnClickListener {
                    HolidayStore.remove(this@HolidayActivity, day)
                    afterChange("已移除 ${date.year}年${Dates.dateHeader(day)}")
                }
            }
        }
    }
}
