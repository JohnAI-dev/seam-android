package dev.johnai.seam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.ByteArrayOutputStream

/**
 * Forwards the phone's notifications to the paired computer, and replies when the computer asks.
 * Before the first forwarded notification from a package on each connection, sends that app's icon
 * on that same session. A reconnect retries both; the notification is not queued onto a later connection.
 */
@SuppressLint("StaticFieldLeak")
class SeamNotificationListener : NotificationListenerService() {

    /** Last forwarded notifications and their reply action, if they had a RemoteInput. */
    private val replies = ReplyActionStore<Notification.Action>(maxSize = 200)

    override fun onListenerConnected() {
        instance = this
        // Make sure the link is running whenever we're allowed to read notifications.
        LinkHub.startService(this)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!shouldForward(sbn)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val action = replyAction(sbn.notification)
        replies.remember(sbn.key, action)
        forwardWithAppIcon(
            sbn.packageName,
            Protocol.notification(
                id = sbn.key,
                app = sbn.packageName,
                appName = appName(sbn),
                title = title.take(500),
                text = text.take(4000),
                time = sbn.postTime,
                replyable = action != null,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        replies.forget(sbn.key)
        if (sbn.packageName == packageName) return
        LinkHub.send(Protocol.notificationRemoved(sbn.key))
    }

    private fun shouldForward(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        return sbn.packageName != packageName &&
            !sbn.isOngoing &&
            (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
            n.category != Notification.CATEGORY_SERVICE &&
            n.category != Notification.CATEGORY_TRANSPORT &&
            n.category != Notification.CATEGORY_PROGRESS
    }

    /** The app's name, read from the notification itself so no extra permission is needed. */
    private fun appName(sbn: StatusBarNotification): String {
        @Suppress("DEPRECATION")
        val info = sbn.notification.extras.getParcelable<ApplicationInfo>("android.appInfo")
        return try {
            (info ?: packageManager.getApplicationInfo(sbn.packageName, 0))
                .loadLabel(packageManager).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
    }

    /** First action with a RemoteInput: an inline reply field, as messaging apps use. */
    private fun replyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        return actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
    }

    /**
     * Fill the stored action's RemoteInput with [text] and fire its actionIntent.
     * Always returns a reply_result frame.
     */
    private fun sendReply(id: String, text: String): String {
        val hit = replies.hit(id)
        if (!hit.known) return Protocol.replyResult(id, false, Protocol.REPLY_ERROR_GONE)
        val action = hit.action ?: return Protocol.replyResult(id, false, Protocol.REPLY_ERROR_CANNOT)
        val inputs = action.remoteInputs
        val pending = action.actionIntent
        if (inputs.isNullOrEmpty() || pending == null) {
            return Protocol.replyResult(id, false, Protocol.REPLY_ERROR_CANNOT)
        }
        return try {
            val fillIn = Intent()
            val results = Bundle()
            for (input in inputs) {
                results.putCharSequence(input.resultKey, text)
            }
            RemoteInput.addResultsToIntent(inputs, fillIn, results)
            pending.send(this, 0, fillIn)
            Protocol.replyResult(id, true)
        } catch (e: Exception) {
            if (e is PendingIntent.CanceledException) {
                replies.forgetIfSame(id, action)
                Protocol.replyResult(id, false, Protocol.REPLY_ERROR_GONE)
            } else {
                Protocol.replyResult(id, false, Protocol.REPLY_ERROR_CANNOT)
            }
        }
    }

    /**
     * Icon and notification are one session-scoped send. Both frames are written
     * with [LinkHub.sendOn] and the captured id, never [LinkHub.send] (that queue
     * is drained onto whatever connection is live later). If the link reconnects
     * while the icon is loaded, both are retried on the new id so the notification
     * is not forwarded there without a preceding app_icon.
     * A missing icon does not drop the notification, but only on the session that
     * stayed current for the attempt.
     */
    private fun forwardWithAppIcon(packageName: String, notification: String) {
        try {
            appIcons.forwardWithIcon(
                app = packageName,
                notification = notification,
                sessionNow = { LinkHub.currentSessionId() },
                loadPng = { appIconPng(packageName) },
                send = { sessionId, frame ->
                    // Captured id only. sendOn(the live id) would put this frame on the next connection.
                    if (!iconSendAllowed(sessionId, LinkHub.currentSessionId())) false
                    else LinkHub.sendOn(sessionId, frame)
                },
            )
        } catch (_: Exception) {
        }
    }

    /** Application icon as a 96×96 PNG of at most [Protocol.APP_ICON_MAX_BYTES]. */
    private fun appIconPng(packageName: String): ByteArray? {
        val drawable = try {
            val icon = packageManager.getApplicationIcon(packageName)
            try {
                icon.mutate()
            } catch (_: Exception) {
                icon
            }
        } catch (_: Exception) {
            return null
        }
        val side = Protocol.APP_ICON_SIDE
        val bitmap = try {
            Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            return null
        }
        try {
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, side, side)
            drawable.draw(canvas)
            return pngAtMost(bitmap, Protocol.APP_ICON_MAX_BYTES)
        } catch (_: Exception) {
            return null
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * PNG of [source], scaled down until it fits in [maxBytes] or the bitmap is 1×1.
     * PNG has no quality knob, so shrink means fewer pixels. Every side from
     * [iconSidesToTry] is encoded; a short fixed cap can give up while the PNG
     * is still over the limit. Does not recycle [source].
     */
    private fun pngAtMost(source: Bitmap, maxBytes: Int): ByteArray? {
        var current = source
        var owned: Bitmap? = null
        try {
            for (side in iconSidesToTry(maxOf(current.width, current.height))) {
                if (current.width != side || current.height != side) {
                    val scaled = Bitmap.createScaledBitmap(current, side, side, true)
                    if (scaled !== current) {
                        val previous = owned
                        if (previous != null && previous !== source && previous !== scaled && !previous.isRecycled) {
                            previous.recycle()
                        }
                        owned = if (scaled === source) null else scaled
                        current = scaled
                    }
                }
                val out = ByteArrayOutputStream()
                if (!current.compress(Bitmap.CompressFormat.PNG, 100, out)) continue
                val bytes = out.toByteArray()
                if (bytes.isNotEmpty() && bytes.size <= maxBytes) return bytes
            }
            return null
        } finally {
            val extra = owned
            if (extra != null && extra !== source && !extra.isRecycled) extra.recycle()
        }
    }

    companion object {
        /** Connected listener instance; dismiss requests are ignored while this is null. */
        @field:SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: SeamNotificationListener? = null

        /** Packages whose icons were sent on the current link session. */
        private val appIcons = AppIconLog()

        /** Cancel the notification the computer dismissed. No-op if the listener isn't connected. */
        fun dismiss(key: String) {
            instance?.cancelNotification(key)
        }

        /** Reply on behalf of the computer. Always returns a reply_result frame. */
        fun reply(id: String, text: String): String {
            val listener = instance
                ?: return Protocol.replyResult(id, false, Protocol.REPLY_ERROR_GONE)
            return try {
                listener.sendReply(id, text.take(Protocol.REPLY_MAX_CHARS))
            } catch (_: Exception) {
                Protocol.replyResult(id, false, Protocol.REPLY_ERROR_CANNOT)
            }
        }
    }
}
