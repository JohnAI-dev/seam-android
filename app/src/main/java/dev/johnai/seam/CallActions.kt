package dev.johnai.seam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager

/**
 * Decline or silence a call when the computer sends call_action
 * (protocol/PROTOCOL.md, "Calls").
 *
 * Silence mutes [AudioManager.STREAM_RING] and unmutes when the call leaves ringing,
 * after [RingerGuard.TIMEOUT_MS], or when [release] runs from the link service onDestroy.
 * Decline uses [TelecomManager.endCall] and does nothing if no call is ringing.
 */
class CallActions(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var closed = false
    private var timeout: Runnable? = null
    private val guard = RingerGuard(
        clock = { SystemClock.elapsedRealtime() },
        mute = { adjust(AudioManager.ADJUST_MUTE) },
        unmute = { adjust(AudioManager.ADJUST_UNMUTE) },
    )

    fun handle(action: String) {
        when (action) {
            "decline" -> decline()
            "silence" -> silence()
        }
    }

    /** Line state from [CallMonitor]. Off the main thread is fine. */
    fun onLineState(state: CallLineState) {
        synchronized(lock) {
            if (closed) return
            guard.onLineState(state)
            if (!guard.isMuted()) disarm()
        }
    }

    /** Unmute if we muted. Idempotent. */
    fun release() {
        synchronized(lock) {
            closed = true
            disarm()
            guard.release()
        }
    }

    private fun silence() {
        synchronized(lock) {
            if (closed) return
            if (guard.silence()) arm()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun decline() {
        synchronized(lock) {
            if (closed) return
        }
        if (app.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            val telephony = app.getSystemService(TelephonyManager::class.java) ?: return
            if (telephony.getCallState() != TelephonyManager.CALL_STATE_RINGING) return
            // endCall is deprecated, but it is how a non-dialer app declines a call.
            // silenceRinger needs privileges this app does not have.
            app.getSystemService(TelecomManager::class.java)?.endCall()
        } catch (_: Exception) {
        }
    }

    private fun arm() {
        disarm()
        val wait = guard.remainingMs().coerceAtLeast(1L)
        val runnable = Runnable { onTimeout() }
        timeout = runnable
        main.postDelayed(runnable, wait)
    }

    private fun disarm() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    private fun onTimeout() {
        synchronized(lock) {
            timeout = null
            if (closed) return
            guard.onTick()
            if (guard.isMuted()) arm()
        }
    }

    private fun adjust(direction: Int) {
        val audio = app.getSystemService(AudioManager::class.java)
            ?: throw IllegalStateException("no audio")
        audio.adjustStreamVolume(AudioManager.STREAM_RING, direction, 0)
    }
}
