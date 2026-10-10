package dev.johnai.seam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Forwards the phone's notifications to the paired computer, and replies when the computer asks. */
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
        LinkHub.send(
            Protocol.notification(
                id = sbn.key,
                app = sbn.packageName,
                appName = appName(sbn),
                title = title.take(500),
                text = text.take(4000),
                time = sbn.postTime,
                replyable = action != null,
            )
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

    companion object {
        /** Connected listener instance; dismiss requests are ignored while this is null. */
        @field:SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: SeamNotificationListener? = null

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
