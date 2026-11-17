package com.daka.reminder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.daka.reminder.R
import com.daka.reminder.alarm.AlarmScheduler
import com.daka.reminder.data.ScheduleMode
import com.daka.reminder.data.TaskTemplate
import com.daka.reminder.data.TaskStore
import com.daka.reminder.data.TemplateKind
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 任务库：只存模板，不隶属任何日期、不记录完成情况。
 *  - 重复模板按星期规则自动展开到各天
 *  - 单日模板不指定日期，由用户在某一天主动挑选添加
 *
 * 日期列表里的修改不会回写到这里。
 */
class TaskLibraryActivity : AppCompatActivity() {

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val template: TaskTemplate) : Row()
    }

    private lateinit var adapter: LibraryAdapter
    private val rows = mutableListOf<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val rv = findViewById<RecyclerView>(R.id.rv_library)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = LibraryAdapter()
        rv.adapter = adapter

        findViewById<View>(R.id.btn_new).setOnClickListener {
            Dialogs.editTemplate(this, null) { refresh() }
        }
        findViewById<View>(R.id.btn_import).setOnClickListener {
            Dialogs.importTemplates(this) { refresh() }
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = TaskStore.templates(this)
        val legal = list.filter {
            it.kind == TemplateKind.REPEAT &&
                (it.scheduleMode == ScheduleMode.LEGAL_WORKDAY ||
                    it.scheduleMode == ScheduleMode.LEGAL_HOLIDAY)
        }.sortedBy { it.minuteOfDay }
        val normal = list.filter {
            it.kind == TemplateKind.REPEAT &&
                it.scheduleMode != ScheduleMode.LEGAL_WORKDAY &&
                it.scheduleMode != ScheduleMode.LEGAL_HOLIDAY
        }.sortedBy { it.minuteOfDay }
        val once = list.filter { it.kind == TemplateKind.ONCE }.sortedBy { it.minuteOfDay }

        rows.clear()
        rows.add(Row.Header("${getString(R.string.library_repeat_group)}（${normal.size}）"))
        normal.forEach { rows.add(Row.Item(it)) }
        rows.add(Row.Header("${getString(R.string.library_legal_group)}（${legal.size}）"))
        legal.forEach { rows.add(Row.Item(it)) }
        rows.add(Row.Header("${getString(R.string.library_once_group)}（${once.size}）"))
        once.forEach { rows.add(Row.Item(it)) }

        adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(template: TaskTemplate) {
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

    private inner class LibraryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Header) 0 else 1

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderVH(inflater.inflate(R.layout.item_library_header, parent, false))
            } else {
                ItemVH(inflater.inflate(R.layout.item_library_task, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).bind(row.title)
                is Row.Item -> (holder as ItemVH).bind(row.template)
            }
        }

        private inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            private val tv = view.findViewById<TextView>(R.id.tv_header)
            fun bind(title: String) {
                tv.text = title
            }
        }

        private inner class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvName = view.findViewById<TextView>(R.id.tv_name)
            private val tvMeta = view.findViewById<TextView>(R.id.tv_meta)
            private val tvAction = view.findViewById<TextView>(R.id.tv_action)

            fun bind(template: TaskTemplate) {
                tvName.text = template.name
                tvMeta.text = "${template.timeText} · ${template.ruleText}"
                tvAction.text = getString(R.string.library_action_edit)

                itemView.setOnClickListener {
                    Dialogs.editTemplate(this@TaskLibraryActivity, template) { refresh() }
                }
                itemView.setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@TaskLibraryActivity)
                        .setTitle(template.name)
                        .setItems(
                            arrayOf(
                                getString(R.string.action_edit),
                                getString(R.string.row_delete_task)
                            )
                        ) { _, which ->
                            when (which) {
                                0 -> Dialogs.editTemplate(
                                    this@TaskLibraryActivity, template
                                ) { refresh() }
                                1 -> confirmDelete(template)
                            }
                        }
                        .show()
                    true
                }
            }
        }
    }
}
