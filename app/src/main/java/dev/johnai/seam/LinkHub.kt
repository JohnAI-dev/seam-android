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
    private val listeners = mutableSetOf<(Status) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun start(context: Context) {
        if (client != null) return
        val store = PairingStore(context)
        val pairing = store.pairing ?: return
        val appContext = context.applicationContext
        val discovery = SeamDiscovery(appContext)
        client = LinkClient(
            pairing,
            store.deviceId,
            store.deviceName,
            onState = { state, detail ->
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
        ).also { it.start() }
    }

    @Synchronized
    fun stop() {
        client?.stop()
        client = null
        update(Status(null, null))
    }

    /** Called right after (re)connecting, e.g. to send the current battery level. */
    @Volatile var onConnected: (() -> Unit)? = null

    fun send(line: String) {
        client?.send(line)
    }

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
