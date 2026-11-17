package com.daka.reminder.util

import android.content.Context
import android.os.PowerManager

/**
 * 提醒期间持有 CPU 唤醒锁。
 *
 * 为什么需要它：
 * AlarmManager 触发广播时，系统只借给 App 约 10 秒唤醒时间。`onReceive` 一返回，
 * 这笔时间就收走了，屏幕又是熄的 → CPU 立刻进入睡眠。
 * 而弹卡片、启动 MediaPlayer、调 Vibrator 都排在 `Handler.post` 里，
 * 一旦 CPU 睡着，这些代码根本没机会执行 —— 表现就是「通知有，声音和震动都没有」。
 *
 * 拿到锁之后，post 能正常跑完；声音和震动一旦start，就由系统进程（AudioFlinger /
 * VibratorManagerService）接着执行，即使本进程后来被冻结也不影响。
 *
 * 带超时是为了兜底：万一某个分支忘了释放，最多 30 分钟自动放手，不会把电池吃穿。
 */
object KeepAwake {

    private const val TIMEOUT_MS = 30 * 60 * 1000L

    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        release()
        try {
            val pm = context.getSystemService(PowerManager::class.java) ?: return
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daka:reminder")
            wl.setReferenceCounted(false)
            wl.acquire(TIMEOUT_MS)
            lock = wl
        } catch (_: Exception) {
            // 没有权限或系统异常时降级：至少不再崩，原行为不变
            lock = null
        }
    }

    @Synchronized
    fun release() {
        try {
            val wl = lock
            if (wl != null && wl.isHeld) wl.release()
        } catch (_: Exception) {
        }
        lock = null
    }
}
