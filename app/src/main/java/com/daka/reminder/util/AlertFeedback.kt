package com.daka.reminder.util

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 提醒时的声音 + 震动。
 *
 * 两种模式：
 *  - 持续：声音循环播放 + 震动循环，直到外部调用 stop()
 *  - 一次：响一轮就结束
 *
 * 声音和震动都可以由调用方按任务单独关掉（[start] 的 soundOn / vibrateOn）。
 * 声音走通知音量（USAGE_NOTIFICATION），手机静音、仅振动或勿扰时都不会响，
 * 尊重用户当时的铃声/勿扰状态；震动不受这个限制，仍按任务设置执行。
 *
 * 为什么不再静默吞异常：
 * 振动调用会因为「没有马达 / 系统限制后台振动 / ROM 权限」失败，
 * 以前 catch 之后什么都不做，结果就是「没震动但查不出原因」。
 * 现在把失败原因记到 [lastStatus]，菜单「预览提醒效果」会直接显示出来。
 */
object AlertFeedback {

    private const val TAG = "DakaAlert"

    /** 震动节奏：震 400、停 250、震 400、停 250、震 400 */
    private val PATTERN = longArrayOf(0, 400, 250, 400, 250, 400)

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    /**
     * 最近一次 [start] 的实际结果，例如「声音✓ · 震动✓」或「声音✓ · 震动失败：xxx」。
     * 「预览提醒效果」会把它 toast 出来，用来排查「为什么不响 / 不震」。
     */
    @Volatile
    var lastStatus: String = ""
        private set

    /**
     * @param loop      是否循环播放，直到 stop()
     * @param soundOn   这条任务要不要出声
     * @param vibrateOn 这条任务要不要震动
     */
    @Synchronized
    fun start(
        context: Context,
        loop: Boolean,
        soundOn: Boolean = true,
        vibrateOn: Boolean = true
    ) {
        release()
        val app = context.applicationContext
        val marks = mutableListOf<String>()

        if (soundOn) {
            when {
                AppSettings.ringtoneUri(app) == AppSettings.RINGTONE_SILENT -> marks.add("铃声设为静音")
                !canRing(app) -> marks.add("静音/勿扰不出声")
                playSound(app, loop) -> marks.add("声音✓")
                else -> marks.add("声音失败")
            }
        } else {
            marks.add("声音关")
        }

        if (vibrateOn) {
            val err = vibrate(app, loop)
            marks.add(if (err == null) "震动✓" else "震动失败：$err")
        } else {
            marks.add("震动关")
        }

        lastStatus = marks.joinToString(" · ")
        Log.i(TAG, "start(loop=$loop): $lastStatus")
    }

    /**
     * 什么时候可以出声：
     *  - 手机不能是静音或仅振动
     *  - 不能处于勿扰模式（只有「全部允许」才出声）
     *
     * 震动不走这个判断，仍然按任务设置执行。
     */
    private fun canRing(context: Context): Boolean {
        return try {
            val am = context.getSystemService(AudioManager::class.java) ?: return true
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false

            val nm = context.getSystemService(NotificationManager::class.java)
            val filter = nm?.currentInterruptionFilter
                ?: NotificationManager.INTERRUPTION_FILTER_ALL
            filter == NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (_: Exception) {
            true
        }
    }

    /** 响一次就停（非持续提醒模式） */
    fun fire(context: Context) = start(context, false)

    @Synchronized
    fun stop() = release()

    @Synchronized
    fun isRinging(): Boolean = player != null || vibrator != null

    /** 只收播放器，不动震动。声音播完时用这个，别把震动一起掐了 */
    private fun releasePlayer() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    /** 声音和震动全停 */
    private fun release() {
        releasePlayer()
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
    }

    private fun playSound(context: Context, loop: Boolean): Boolean {
        val custom = AppSettings.ringtoneUri(context)

        // 用户明确选了「静音」：不播声音，震动仍由 vibrateOn 决定
        if (custom == AppSettings.RINGTONE_SILENT) return false

        val candidates = mutableListOf<Uri>()
        if (custom.isNotBlank()) candidates.add(Uri.parse(custom))
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.let { candidates.add(it) }
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { candidates.add(it) }

        // 自定义铃声读不到（文件被删、权限被收回）时自动退回系统默认，不留静默失败
        for (uri in candidates) {
            if (startPlayer(context, uri, loop)) return true
        }
        return false
    }

    private fun startPlayer(context: Context, uri: Uri, loop: Boolean): Boolean {
        var created: MediaPlayer? = null
        return try {
            val mp = MediaPlayer()
            created = mp
            mp.setDataSource(context, uri)
            // 息屏播放：让播放器自己持一个 CPU 锁，屏幕灭了也不会断。
            // 这是「持续提醒」能响到底的关键，和 ReminderPresenter 里那把锁各管一段。
            try {
                mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            } catch (_: Exception) {
            }
            // 用通知音量：静音 / 勿扰时系统会自然抑制，不会像闹钟那样穿透
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.isLooping = loop
            // 只释放播放器：以前这里调 release()，短提示音播完会把震动一起 cancel 掉，
            // 表现就是「有声音、没震动」
            if (!loop) mp.setOnCompletionListener { releasePlayer() }
            mp.prepare()
            mp.start()
            player = mp
            true
        } catch (_: Exception) {
            try {
                created?.release()
            } catch (_: Exception) {
            }
            false
        }
    }

    /** 返回 null 表示震动已启动，否则返回失败原因 */
    private fun vibrate(context: Context, loop: Boolean): String? {
        val v = resolveVibrator(context) ?: return "取不到振动器"
        if (!v.hasVibrator()) return "设备没有振动马达"

        var firstError: String? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // repeat = 0 表示从头循环，直到 cancel()
                val effect = VibrationEffect.createWaveform(PATTERN, if (loop) 0 else -1)
                startVibration(v, effect)
                vibrator = v
                return null
            } catch (e: Exception) {
                firstError = e.message ?: e.javaClass.simpleName
            }
        }

        // 退回老接口：个别 ROM 只放行旧的 vibrate(long[], int)
        try {
            @Suppress("DEPRECATION")
            v.vibrate(PATTERN, if (loop) 0 else -1)
            vibrator = v
            return null
        } catch (e: Exception) {
            return firstError ?: e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * 真正发起振动。
     *
     * Android 13（SDK 33）起，三星 One UI、小米 HyperOS 等系统会**丢弃后台进程里
     * 没声明用途的振动请求** —— 老写法 `vibrate(effect)` 的默认 usage 是 USAGE_UNKNOWN，
     * 于是「声音正常响、完全没有触感」。这里显式声明成闹钟用途，系统才会放行。
     */
    private fun startVibration(v: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attrs = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()
            v.vibrate(effect, attrs)
        } else {
            v.vibrate(effect)
        }
    }

    /** Android 12 起振动器要从 VibratorManager 取，取不到再退回老接口 */
    private fun resolveVibrator(context: Context): Vibrator? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val fromManager = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            if (fromManager != null) return fromManager
        }
        @Suppress("DEPRECATION")
        return context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
}
