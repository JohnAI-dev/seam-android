@file:Suppress("DEPRECATION")

package dev.johnai.seam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Forwards phone call state to the paired computer (protocol/PROTOCOL.md, "Calls").
 *
 * [LinkService] registers this synchronously on the main looper and unregisters it in
 * onDestroy. PhoneStateListener is deprecated, but it works on every API this app supports
 * and it is what passes the phone number when READ_CALL_LOG is granted. The call log is
 * never queried. Without READ_PHONE_STATE, [start] does nothing so the rest of the link
 * keeps working. A contact name is looked up off the main thread only when READ_CONTACTS
 * is granted; otherwise the name is empty.
 *
 * listen() posts onCallStateChanged to the main Handler; it does not run inside listen(),
 * and that callback is the one that can carry the number. getCallState() has none. Those
 * two observations are one snapshot: [CallStartupGate] keeps both, in either order, and
 * only then is one frame sent. A blank number is not sent when the other event has one.
 */
@SuppressLint("MissingPermission")
class CallMonitor {
    private val session = CallSession()
    private val main = Handler(Looper.getMainLooper())

    /**
     * Each observed line state, including repeats that do not produce a call frame,
     * so a silenced ringer can unmute when the call leaves ringing. May run off the main thread.
     */
    @Volatile var onLineState: ((CallLineState) -> Unit)? = null

    private var gate = CallStartupGate()
    private var listener: PhoneStateListener? = null
    private var telephony: TelephonyManager? = null
    private var appContext: Context? = null
    private var executor: ExecutorService? = null
    private var flushToken = 0
    private var sawCallLog = false

    @Volatile private var registered = false

    /** Call on the main looper. Reads the current state immediately after registering. */
    fun start(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val app = context.applicationContext
            main.post { start(app) }
            return
        }
        val app = context.applicationContext
        if (registered) {
            relistenIfCallLogNewlyGranted(app)
            return
        }
        if (app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val tm = app.getSystemService(TelephonyManager::class.java) ?: return
        try {
            if (executor == null || executor?.isShutdown == true) {
                executor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "seam-calls").apply { isDaemon = true }
                }
            }
            appContext = app
            telephony = tm
            sawCallLog = hasCallLog(app)
            gate = CallStartupGate()
            val ear = ensureListener()
            // Before listen(), so a callback delivered inside listen() is recorded, not dropped.
            registered = true
            tm.listen(ear, PhoneStateListener.LISTEN_CALL_STATE)
            // Do not enqueue this yet. It has no number. listen() posts the listener callback
            // on this looper, and that callback can carry the number. Hold both until the next
            // turn so a blank snapshot is not sent ahead of the numbered one.
            val initial = lineState(tm.getCallState())
            if (initial != null) gate.observe(initial, "")
            val token = ++flushToken
            main.post {
                if (token != flushToken || !registered) return@post
                emitAll(gate.finish())
            }
        } catch (_: Exception) {
            registered = false
            flushToken++
            gate.finish()
            try {
                listener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        registered = false
        flushToken++
        sawCallLog = false
        gate.finish()
        val tm = telephony
        telephony = null
        val ear = listener
        if (tm != null && ear != null) {
            try {
                tm.listen(ear, PhoneStateListener.LISTEN_NONE)
            } catch (_: Exception) {
            }
        }
        val exec = executor
        executor = null
        exec?.shutdownNow()
    }

    private fun ensureListener(): PhoneStateListener {
        listener?.let { return it }
        // No-arg constructor, created on the main looper. The Looper overload is a hidden
        // system API, and PhoneStateListener(Executor) is API 31+. listen() then posts
        // callbacks here, including the number when READ_CALL_LOG is granted.
        val created = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                // Observe immediately. A post here would land after start()'s flush and
                // the blank read would already have been sent.
                deliver(state, phoneNumber)
            }
        }
        listener = created
        return created
    }

    private fun deliver(state: Int, phoneNumber: String?) {
        if (!registered) return
        val line = lineState(state) ?: return
        emitAll(gate.observe(line, phoneNumber?.trim().orEmpty()))
    }

    private fun emitAll(snaps: List<CallSnapshot>) {
        for (snap in snaps) enqueue(snap)
    }

    private fun enqueue(snap: CallSnapshot) {
        if (!registered) return
        val exec = executor ?: return
        try {
            exec.execute {
                try {
                    handle(snap)
                } catch (_: Exception) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun handle(snap: CallSnapshot) {
        notifyLine(snap.state)
        val update = session.onState(snap.state, snap.number, lookupName(snap.number)) ?: return
        LinkHub.send(Protocol.call(update.state, update.number, update.name))
    }

    private fun notifyLine(state: CallLineState) {
        val listener = onLineState ?: return
        if (!registered) return
        try {
            listener.invoke(state)
        } catch (_: Exception) {
        }
    }

    /**
     * listen() only starts passing the number once READ_CALL_LOG is granted. A service that
     * registered before that grant must listen again; onStartCommand is not redelivered
     * just because the permission dialog closed.
     */
    private fun relistenIfCallLogNewlyGranted(app: Context) {
        if (sawCallLog || !hasCallLog(app)) return
        sawCallLog = true
        val tm = telephony ?: return
        try {
            tm.listen(ensureListener(), PhoneStateListener.LISTEN_CALL_STATE)
        } catch (_: Exception) {
        }
    }

    private fun hasCallLog(app: Context): Boolean =
        app.checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /** Empty when the number is missing, READ_CONTACTS is not granted, or this is the main thread. */
    private fun lookupName(number: String): String {
        if (number.isEmpty()) return ""
        if (Looper.myLooper() == Looper.getMainLooper()) return ""
        val app = appContext ?: return ""
        if (app.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ""
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number),
            )
            app.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return ""
                val column = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (column < 0) return ""
                cursor.getString(column)?.trim().orEmpty()
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun lineState(state: Int): CallLineState? = when (state) {
        TelephonyManager.CALL_STATE_IDLE -> CallLineState.IDLE
        TelephonyManager.CALL_STATE_RINGING -> CallLineState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> CallLineState.OFFHOOK
        else -> null
    }
}
