package dev.johnai.seam

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * The one place that owns the connection to the computer. The service keeps it running,
 * the notification listener sends through it, and the screen shows its state.
 */
object LinkHub {
    data class Status(val state: LinkClient.State?, val detail: String?)

    @Volatile var status = Status(null, null)
        private set

    private var client: LinkClient? = null
    private var files: FileInbox? = null
    private val listeners = mutableSetOf<(Status) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun start(context: Context) {
        if (client != null) return
        val store = PairingStore(context)
        val pairing = store.pairing ?: return
        val appContext = context.applicationContext
        val discovery = SeamDiscovery(appContext)
        files?.abandon()
        val inbox = FileInbox(appContext)
        files = inbox
        client = LinkClient(
            pairing,
            store.deviceId,
            store.deviceName,
            onState = { state, detail ->
                if (state != LinkClient.State.CONNECTED) inbox.abandon()
                update(Status(state, detail))
                if (state == LinkClient.State.CONNECTED) onConnected?.invoke()
            },
            onDismiss = { id -> SeamNotificationListener.dismiss(id) },
            onClipboard = { text -> putOnClipboard(appContext, text) },
            onReply = { id, text -> SeamNotificationListener.reply(id, text) },
            discover = { fp -> discovery.find(fp) },
            onPairingUpdated = { updated ->
                synchronized(this@LinkHub) {
                    val current = store.pairing
                    if (client != null && current != null && current.keyB64 == updated.keyB64 &&
                        current.fingerprint.equals(updated.fingerprint, ignoreCase = true)
                    ) {
                        store.pairing = updated
                    }
                }
            },
            onRing = {
                try {
                    PhoneFinder.start(appContext)
                } catch (_: Exception) {
                }
            },
            onRingStop = {
                try {
                    PhoneFinder.stop(appContext)
                } catch (_: Exception) {
                }
            },
            onCallAction = { action -> performCallAction(action) },
            onFile = { line ->
                val outgoing = try {
                    onOutgoingFile?.invoke(line) == true
                } catch (_: Exception) {
                    false
                }
                if (outgoing) null else inbox.handle(line)
            },
        ).also { it.start() }
    }

    @Synchronized
    fun stop() {
        client?.stop()
        client = null
        files?.abandon()
        files = null
        update(Status(null, null))
    }

    /** Called right after (re)connecting, e.g. to send the current battery level. */
    @Volatile var onConnected: (() -> Unit)? = null

    /**
     * The running link service registers this so a call-permission grant can start
     * reporting without a process restart. Null while the service is down.
     */
    @Volatile var onStartCalls: (() -> Unit)? = null

    /** Start call reporting if the link service is already running. */
    fun startCallMonitor() {
        try {
            onStartCalls?.invoke()
        } catch (_: Exception) {
        }
    }

    /**
     * The running link service registers this to decline or silence a call when the
     * computer sends call_action. Null while the service is down.
     */
    @Volatile var onCallAction: ((String) -> Unit)? = null

    /**
     * Link service: true if [line] is a reply to an upload this phone started
     * (file_accept, file_reject, file_result, file_cancel). Incoming offers stay false.
     */
    @Volatile var onOutgoingFile: ((String) -> Boolean)? = null

    /** Notification Cancel: stop the upload with this transfer id (sends file_cancel). */
    @Volatile var onCancelOutgoingFile: ((String) -> Unit)? = null

    /** Decline or silence if the link service is running. */
    fun performCallAction(action: String) {
        try {
            onCallAction?.invoke(action)
        } catch (_: Exception) {
        }
    }

    fun cancelOutgoingFile(transfer: String) {
        try {
            onCancelOutgoingFile?.invoke(transfer)
        } catch (_: Exception) {
        }
    }

    fun send(line: String) {
        client?.send(line)
    }

    /** Id of the live session, or null when the link is down. Capture this for [sendOn]. */
    fun currentSessionId(): Long? = client?.currentSessionId()

    /**
     * Write [frame] only on the session that was current when the upload offered the file.
     * Returns false and writes nothing if that session has ended or been replaced.
     */
    fun sendOn(sessionId: Long, frame: String): Boolean =
        client?.sendOn(sessionId, frame) == true

    /** Queue clipboard text if connected. Returns the computer name, or null if not connected. */
    fun sendClipboard(context: Context, text: String): String? {
        val s = status
        if (s.state != LinkClient.State.CONNECTED) return null
        send(Protocol.clipboard(text))
        return s.detail?.takeIf { it.isNotBlank() }
            ?: PairingStore(context).pairing?.name.orEmpty()
    }

    /**
     * Put computer clipboard text on this phone, on the main thread.
     * Incoming clips are not echoed: the phone only sends clipboard text when the person asks.
     */
    private fun putOnClipboard(context: Context, text: String) {
        val clipped = text.take(Protocol.CLIPBOARD_MAX_CHARS)
        main.post {
            try {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), clipped))
            } catch (_: Exception) {
            }
        }
    }

    fun addListener(l: (Status) -> Unit) {
        synchronized(listeners) { listeners += l }
        l(status)
    }

    fun removeListener(l: (Status) -> Unit) {
        synchronized(listeners) { listeners -= l }
    }

    private fun update(s: Status) {
        status = s
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { it(s) } }
    }

    /** Start the background service that keeps the link alive. */
    fun startService(context: Context) {
        if (PairingStore(context).pairing == null) return
        try {
            context.startForegroundService(Intent(context, LinkService::class.java))
        } catch (e: IllegalStateException) {
            // Android may refuse to start it from the background; the screen or the next
            // boot starts it instead.
        }
    }

    fun stopService(context: Context) {
        context.stopService(Intent(context, LinkService::class.java))
        stop()
    }
}
