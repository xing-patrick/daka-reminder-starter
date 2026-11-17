package com.daka.reminder.util

import android.content.Context

/** App 级开关，和打卡数据分开存 */
object AppSettings {

    private const val PREF = "daka_settings"
    private const val KEY_PERSISTENT = "persistent_alarm"
    private const val KEY_WATCH_BOOST = "watch_boost"
    private const val KEY_SEND_NOTIFICATION = "send_notification"
    private const val KEY_WATCH_REPEAT = "watch_repeat"
    private const val KEY_RINGTONE = "ringtone_uri"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 闹钟式持续提醒：不点「已完成 / 稍后」就一直响。默认开 */
    fun persistentAlarm(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERSISTENT, true)

    fun setPersistentAlarm(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_PERSISTENT, on).apply()
    }

    /** 提高通知优先级，让华为手表/手环更容易收到 */
    fun watchBoost(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WATCH_BOOST, false)

    fun setWatchBoost(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_WATCH_BOOST, on).apply()
    }

    /**
     * 是否发系统通知。手表/手环只能收到通知，所以想推手表就必须开着。
     * 不用手表时可以关掉，避免通知栏留一条重复记录；声音和震动不受影响。
     */
    fun sendNotification(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SEND_NOTIFICATION, true)

    fun setSendNotification(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEND_NOTIFICATION, on).apply()
    }

    /**
     * 延长手表震动：提醒一直没处理时，每 20 秒重发一次通知。
     *
     * 手表单条通知能震多久由手表固件和「华为运动健康」决定，App 改不了，
     * 唯一能让它多震几轮的办法就是重复发通知 —— 手表每收到一条就震一次。
     * 只在「闹钟式持续提醒」开启、且这条任务要声音或震动时才生效。默认开。
     */
    fun watchRepeat(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WATCH_REPEAT, true)

    fun setWatchRepeat(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_WATCH_REPEAT, on).apply()
    }

    /** 铃声设成这个值表示用户明确选了「静音」，到点不出声 */
    const val RINGTONE_SILENT = "@silent"

    /**
     * 打卡提醒的铃声，全局一个，所有开着「声音」的任务共用。
     *
     * 空串 = 系统默认通知音；[RINGTONE_SILENT] = 不出声；其余是铃声的 URI 字符串。
     * 和系统通知音分开的好处：微信/QQ 一阵响的时候，能听出哪一声是打卡。
     */
    fun ringtoneUri(context: Context): String =
        prefs(context).getString(KEY_RINGTONE, "").orEmpty()

    fun setRingtoneUri(context: Context, uri: String) {
        prefs(context).edit().putString(KEY_RINGTONE, uri).apply()
    }
}
