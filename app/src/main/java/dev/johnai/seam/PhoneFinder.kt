package dev.johnai.seam

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Find my phone. On ring, loops the default alarm (USAGE_ALARM, so it plays on silent) at max
 * alarm volume, vibrates, and posts a high-priority notification with a full-screen Found it
 * screen. Stops when that button is pressed, ring_stop arrives, or 60 seconds pass.
 *
 * The alarm stream is raised only after a restore alarm is scheduled, so a dead process still
 * puts the volume back. An interrupted start waits out that critical section.
 */
object PhoneFinder {
    const val CHANNEL_ID = "find_phone"
    const val NOTIFICATION_ID = 3
    const val ACTION_FOUND = "dev.johnai.seam.action.FOUND_IT"
    const val ACTION_TIMEOUT = "dev.johnai.seam.action.RING_TIMEOUT"

    private const val PREFS = "seam_find"
    private const val KEY_VOLUME = "alarm_volume"
    private const val KEY_DEADLINE = "deadline_wall"
    private const val RC_SCREEN = 10
    private const val RC_FOUND = 11
    private const val RC_TIMEOUT = 12

    private val lock = Any()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val stopListeners = mutableSetOf<() -> Unit>()
    private val alarmAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private var ringing = false
    private var timerToken = 0
    private var timeoutRunnable: Runnable? = null
    private var ringtone: Ringtone? = null
    private var activeVibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    fun isRinging(): Boolean = synchronized(lock) { ringing }

    fun addStopListener(listener: () -> Unit) {
        synchronized(lock) { stopListeners += listener }
    }

    fun removeStopListener(listener: () -> Unit) {
        synchronized(lock) { stopListeners -= listener }
    }

    /** Desktop frame. Unknown frames are ignored. */
    fun handleFrame(context: Context, frame: String) {
        when {
            Protocol.isRing(frame) -> start(context)
            Protocol.isRingStop(frame) -> stop(context)
        }
    }

    /**
     * If this process died mid-ring, resume until the original deadline or put the alarm
     * stream back. The 60-second alarm is what restores the volume when nobody starts the
     * process; this only handles a start that happens first (including boot, which clears alarms).
     */
    fun onProcessStart(context: Context) {
        val app = context.applicationContext
        try {
            runOnMain { synchronized(lock) { onProcessStartLocked(app) } }
        } catch (_: Exception) {
            restoreUnlessRinging(app)
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        try {
            runOnMain { synchronized(lock) { startLocked(app) } }
        } catch (_: Exception) {
            // The main-thread section may have raised the stream and then failed.
            restoreUnlessRinging(app)
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        try {
            runOnMain {
                val listeners = synchronized(lock) { stopLocked(app) }
                dispatch(listeners)
            }
        } catch (_: Exception) {
            restoreUnlessRinging(app)
        }
    }

    private fun onProcessStartLocked(context: Context) {
        if (ringing) return
        val store = prefs(context)
        when (
            findPhoneRecovery(
                volumeSaved = store.contains(KEY_VOLUME),
                nowMs = System.currentTimeMillis(),
                deadlineMs = store.getLong(KEY_DEADLINE, 0L),
            )
        ) {
            FindPhoneRecovery.NONE -> Unit
            FindPhoneRecovery.RESTORE -> {
                quiet(context)
                if (restoreVolume(context)) cancelTimeoutAlarm(context)
            }
            FindPhoneRecovery.RESUME -> {
                val remaining = store.getLong(KEY_DEADLINE, 0L) - System.currentTimeMillis()
                resumeLocked(context, remaining)
            }
        }
    }

    private fun startLocked(context: Context) {
        try {
            if (ringing) {
                extendLocked(context)
                return
            }
            prepareVolume(
                context,
                Protocol.RING_DURATION_MS,
                persistOriginal = true,
                rewriteDeadline = true,
            )
            startEffects(context, Protocol.RING_DURATION_MS)
        } catch (_: Throwable) {
            if (!ringing) {
                quiet(context)
                if (restoreVolume(context)) cancelTimeoutAlarm(context)
            }
        }
    }

    private fun resumeLocked(context: Context, remainingMs: Long) {
        val remaining = remainingMs.coerceIn(1L, Protocol.RING_DURATION_MS)
        try {
            scheduleTimeoutAlarm(context, SystemClock.elapsedRealtime() + remaining)
            raiseVolume(context)
        } catch (_: Throwable) {
            quiet(context)
            if (restoreVolume(context)) cancelTimeoutAlarm(context)
            return
        }
        startEffects(context, remaining)
    }

    private fun extendLocked(context: Context) {
        val remaining = Protocol.RING_DURATION_MS
        prefs(context).edit().putLong(KEY_DEADLINE, System.currentTimeMillis() + remaining).commit()
        try {
            scheduleTimeoutAlarm(context, SystemClock.elapsedRealtime() + remaining)
        } catch (_: Exception) {
        }
        try {
            raiseVolume(context)
        } catch (_: Exception) {
        }
        try {
            startSound(context)
        } catch (_: Exception) {
        }
        try {
            armHandler(context, remaining)
        } catch (_: Exception) {
        }
        try {
            showNotification(context)
        } catch (_: Exception) {
        }
        try {
            acquireWakeLock(context, remaining)
        } catch (_: Exception) {
        }
    }

    /**
     * Save the current alarm volume and schedule the restore alarm before changing the stream.
     * Returns true only when the stream was raised and a restore alarm is pending.
     */
    private fun prepareVolume(
        context: Context,
        remaining: Long,
        persistOriginal: Boolean,
        rewriteDeadline: Boolean,
    ): Boolean {
        try {
            if (persistOriginal) rememberVolume(context)
            if (persistOriginal || rewriteDeadline) {
                val deadlineWall = System.currentTimeMillis() + remaining
                if (!prefs(context).edit().putLong(KEY_DEADLINE, deadlineWall).commit()) {
                    throw IllegalStateException("could not persist ring deadline")
                }
            }
            // Scheduled before setStreamVolume so process death still restores the stream.
            scheduleTimeoutAlarm(context, SystemClock.elapsedRealtime() + remaining)
            raiseVolume(context)
            if (!prefs(context).contains(KEY_VOLUME)) {
                cancelTimeoutAlarm(context)
                prefs(context).edit().remove(KEY_DEADLINE).commit()
                return false
            }
            return true
        } catch (_: Throwable) {
            if (restoreVolume(context)) cancelTimeoutAlarm(context)
            return false
        }
    }

    private fun startEffects(context: Context, remaining: Long) {
        try {
            ensureChannel(context)
        } catch (_: Exception) {
        }
        try {
            startSound(context)
        } catch (_: Exception) {
        }
        try {
            startVibration(context)
        } catch (_: Exception) {
        }
        try {
            acquireWakeLock(context, remaining)
        } catch (_: Exception) {
        }
        ringing = true
        try {
            armHandler(context, remaining)
        } catch (_: Exception) {
        }
        try {
            showNotification(context)
        } catch (_: Exception) {
        }
    }

    private fun stopLocked(context: Context): List<() -> Unit> {
        timerToken++
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        val listeners = if (ringing) stopListeners.toList() else emptyList()
        ringing = false
        try {
            quiet(context)
        } finally {
            if (restoreVolume(context)) cancelTimeoutAlarm(context)
        }
        return listeners
    }

    private fun restoreUnlessRinging(context: Context) {
        val work = {
            synchronized(lock) {
                if (!ringing && restoreVolume(context)) cancelTimeoutAlarm(context)
            }
        }
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) work() else runOnMain { work() }
        } catch (_: Exception) {
            try {
                work()
            } catch (_: Exception) {
            }
        }
    }

    private fun armHandler(context: Context, delayMs: Long) {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        val token = ++timerToken
        val app = context.applicationContext
        val runnable = Runnable { onTimeout(app, token) }
        timeoutRunnable = runnable
        // postDelayed follows uptime and pauses in deep sleep. The exact alarm is the
        // bound the volume cannot outlive; this stops the ring sooner while awake.
        handler.postDelayed(runnable, delayMs.coerceAtLeast(1L))
    }

    private fun onTimeout(context: Context, token: Int) {
        val listeners = synchronized(lock) {
            if (token != timerToken || !ringing) emptyList() else stopLocked(context)
        }
        dispatch(listeners)
    }

    private fun dispatch(listeners: List<() -> Unit>) {
        for (listener in listeners) {
            try {
                listener()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Run [block] on the main thread and wait until it finishes. An interrupt must not
     * abandon the wait: the block may already have raised the alarm stream, and only
     * its own cleanup puts the volume back.
     */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val error = AtomicReference<Throwable>(null)
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                latch.countDown()
            }
        }
        if (!posted) {
            block()
            return
        }
        var interrupted = false
        try {
            while (latch.count > 0) {
                try {
                    latch.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        val thrown = error.get() ?: return
        if (thrown is Exception) throw thrown
        throw RuntimeException(thrown)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun rememberVolume(context: Context) {
        val store = prefs(context)
        if (store.contains(KEY_VOLUME)) return
        val audio = context.getSystemService(AudioManager::class.java)
            ?: throw IllegalStateException("no audio")
        val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        if (!store.edit().putInt(KEY_VOLUME, current).commit()) {
            throw IllegalStateException("could not save alarm volume")
        }
    }

    private fun raiseVolume(context: Context) {
        val store = prefs(context)
        if (!store.contains(KEY_VOLUME)) return
        val audio = context.getSystemService(AudioManager::class.java)
            ?: throw IllegalStateException("no audio")
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
    }

    private fun restoreVolume(context: Context): Boolean {
        val store = prefs(context)
        if (!store.contains(KEY_VOLUME)) return true
        val saved = store.getInt(KEY_VOLUME, -1)
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        if (saved < 0) {
            return store.edit().remove(KEY_VOLUME).remove(KEY_DEADLINE).commit()
        }
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, saved.coerceIn(0, max), 0)
        } catch (_: Exception) {
            return false
        }
        return store.edit().remove(KEY_VOLUME).remove(KEY_DEADLINE).commit()
    }

    @SuppressLint("MissingPermission")
    private fun scheduleTimeoutAlarm(context: Context, elapsedDeadline: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
            ?: throw IllegalStateException("no alarm manager")
        val pi = timeoutPendingIntent(context)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDeadline, pi)
                return
            }
        } catch (_: SecurityException) {
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDeadline, pi)
        } catch (e: Exception) {
            throw IllegalStateException("could not schedule volume restore", e)
        }
    }

    private fun cancelTimeoutAlarm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        try {
            am.cancel(timeoutPendingIntent(context))
        } catch (_: Exception) {
        }
    }

    private fun timeoutPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, FindPhoneTimeoutReceiver::class.java).setAction(ACTION_TIMEOUT)
        return PendingIntent.getBroadcast(
            context,
            RC_TIMEOUT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.find_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.description = context.getString(R.string.find_channel_description)
        channel.setBypassDnd(true)
        channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun startSound(context: Context) {
        try {
            if (ringtone?.isPlaying == true) return
        } catch (_: Exception) {
        }
        stopSound()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        val tone = try {
            RingtoneManager.getRingtone(context, uri)
        } catch (_: Exception) {
            null
        } ?: return
        try {
            tone.audioAttributes = alarmAttributes
        } catch (_: Exception) {
        }
        // Ringtone.setLooping / isLooping is API 28. Never call it below that.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                tone.isLooping = true
                tone.volume = 1f
            } catch (_: Exception) {
            }
        }
        try {
            requestFocus(context)
            tone.play()
            ringtone = tone
        } catch (_: Exception) {
            try {
                tone.stop()
            } catch (_: Exception) {
            }
            abandonFocus(context)
        }
    }

    private fun stopSound() {
        val tone = ringtone
        ringtone = null
        if (tone == null) return
        try {
            tone.stop()
        } catch (_: Exception) {
        }
    }

    private fun requestFocus(context: Context) {
        abandonFocus(context)
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(alarmAttributes)
                .build()
            audio.requestAudioFocus(req)
            focusRequest = req
        } catch (_: Exception) {
        }
    }

    private fun abandonFocus(context: Context) {
        val req = focusRequest ?: return
        focusRequest = null
        try {
            context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun startVibration(context: Context) {
        if (activeVibrator != null) return
        val v = context.getSystemService(Vibrator::class.java) ?: return
        try {
            if (!v.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, alarmAttributes)
            }
            activeVibrator = v
        } catch (_: Exception) {
        }
    }

    private fun stopVibration() {
        try {
            activeVibrator?.cancel()
        } catch (_: Exception) {
        }
        activeVibrator = null
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun acquireWakeLock(context: Context, remaining: Long) {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        try {
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dev.johnai.seam:find")
            lock.setReferenceCounted(false)
            lock.acquire(remaining.coerceAtLeast(1L) + 15_000L)
            wakeLock = lock
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        val held = wakeLock
        wakeLock = null
        if (held == null) return
        try {
            if (held.isHeld) held.release()
        } catch (_: Exception) {
        }
    }

    private fun quiet(context: Context) {
        stopSound()
        stopVibration()
        releaseWakeLock()
        abandonFocus(context)
        cancelNotification(context)
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(context: Context) {
        if (!canPostNotifications(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)
        val screenPi = PendingIntent.getActivity(
            context,
            RC_SCREEN,
            Intent(context, FoundActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val foundPi = PendingIntent.getBroadcast(
            context,
            RC_FOUND,
            Intent(context, FoundItReceiver::class.java).setAction(ACTION_FOUND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_stat_seam),
            context.getString(R.string.found_it),
            foundPi,
        ).build()
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seam)
            .setContentTitle(context.getString(R.string.find_title))
            .setContentText(context.getString(R.string.find_text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(screenPi)
            .addAction(action)
        if (fullScreenAllowed(context)) builder.setFullScreenIntent(screenPi, true)
        nm.notify(NOTIFICATION_ID, builder.build())
    }

    private fun cancelNotification(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun fullScreenAllowed(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        } else {
            true
        }
    }
}

/** Fires even if the process was killed, so the alarm stream cannot stay at max. */
class FindPhoneTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        PhoneFinder.stop(context)
    }
}
