package dev.johnai.seam

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
        client = LinkClient(pairing, store.deviceId, store.deviceName) { state, detail ->
            update(Status(state, detail))
            if (state == LinkClient.State.CONNECTED) onConnected?.invoke()
        }.also { it.start() }
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
