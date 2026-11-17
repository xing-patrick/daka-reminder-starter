package com.daka.reminder.ui

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.daka.reminder.R
import com.daka.reminder.data.DayTask
import com.daka.reminder.data.TaskStore
import com.daka.reminder.util.Dates

/**
 * 左右滑动的日期页。以今天为中点，前后各约一年。
 *
 * 每一页的清单是那一天的「任务实例」，彼此独立。
 * 打卡、编辑、移除都只作用于当前这一天。
 */
class DayPagerAdapter(
    private val onToggle: (String, Long) -> Unit,
    private val onLongPress: (String, Long) -> Unit
) : RecyclerView.Adapter<DayPagerAdapter.DayVH>() {

    private val today = Dates.todayEpochDay()

    fun epochDayAt(position: Int): Long = today + position - OFFSET

    fun positionOf(epochDay: Long): Int =
        ((epochDay - today) + OFFSET).toInt().coerceIn(0, COUNT - 1)

    override fun getItemCount(): Int = COUNT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayVH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.page_day, parent, false)
        return DayVH(view)
    }

    override fun onBindViewHolder(holder: DayVH, position: Int) {
        holder.bind(epochDayAt(position))
    }

    inner class DayVH(view: View) : RecyclerView.ViewHolder(view) {

        private val rv = view.findViewById<RecyclerView>(R.id.rv_day)
        private val empty = view.findViewById<TextView>(R.id.tv_page_empty)

        init {
            rv.layoutManager = LinearLayoutManager(view.context)
            rv.isNestedScrollingEnabled = false
        }

        fun bind(day: Long) {
            val entries = TaskStore.entriesFor(itemView.context, day)
            empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
            rv.adapter = DayListAdapter(
                entries = entries,
                editable = day == Dates.todayEpochDay(),
                onToggle = { id -> onToggle(id, day) },
                onLongPress = { id -> onLongPress(id, day) }
            )
        }
    }

    companion object {
        const val OFFSET = 365
        const val COUNT = 731      // 今天前后各 365 天
    }
}

/** 某一天的实例列表 */
private class DayListAdapter(
    private val entries: List<DayTask>,
    private val editable: Boolean,
    private val onToggle: (String) -> Unit,
    private val onLongPress: (String) -> Unit
) : RecyclerView.Adapter<DayListAdapter.VH>() {

    override fun getItemCount(): Int = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_checkin, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(entries[position])
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {

        private val tvMark = view.findViewById<TextView>(R.id.tv_mark)
        private val tvName = view.findViewById<TextView>(R.id.tv_name)
        private val tvMeta = view.findViewById<TextView>(R.id.tv_meta)
        private val tvTime = view.findViewById<TextView>(R.id.tv_time)

        fun bind(entry: DayTask) {
            // 延迟次数用尽仍未完成 → 这条算失败：置灰、标记 ×，不再有提醒
            val failedStyle = entry.failed && !entry.done

            tvMark.text = when {
                failedStyle -> "×"
                entry.done -> "✓"
                else -> ""
            }
            tvMark.setBackgroundResource(
                when {
                    failedStyle -> R.drawable.bg_circle_failed
                    entry.done -> R.drawable.bg_circle_done
                    else -> R.drawable.bg_circle_empty
                }
            )
            tvMark.setTextColor(
                itemView.context.getColor(
                    if (failedStyle) R.color.c_text_dim else R.color.c_on_primary
                )
            )

            tvName.text = entry.name
            // 来源 + 提醒方式 + 状态，静音、失败、改过时间都能在列表里看出来
            val source = if (entry.templateId.isBlank()) "临时任务" else "任务库"
            val alertTag = when {
                !entry.sound -> " · 静音"
                !entry.vibrate -> " · 无震动"
                else -> ""
            }
            val stateTag = when {
                failedStyle -> " · 打卡失败"
                entry.timeShifted -> " · 原定 ${entry.plannedTimeText}"
                else -> ""
            }
            tvMeta.text = source + alertTag + stateTag

            // 打过卡就显示实际打卡时间，提前 / 延后都按真实时刻
            tvTime.text = entry.timeText
            tvTime.setTextColor(
                itemView.context.getColor(
                    if (failedStyle) R.color.c_text_dim else R.color.c_text
                )
            )

            val alpha = when {
                failedStyle -> 0.42f
                entry.done -> 0.5f
                else -> 1f
            }
            tvName.alpha = alpha
            tvMeta.alpha = alpha
            tvTime.alpha = alpha

            tvName.paintFlags = if (entry.done) {
                tvName.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                tvName.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            itemView.setOnClickListener {
                if (editable) {
                    onToggle(entry.id)
                } else {
                    Toast.makeText(itemView.context, R.string.row_readonly, Toast.LENGTH_SHORT).show()
                }
            }
            itemView.setOnLongClickListener {
                onLongPress(entry.id)
                true
            }
        }
    }
}
