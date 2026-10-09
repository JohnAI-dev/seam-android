package dev.johnai.seam

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Forwards the phone's notifications to the paired computer. */
class SeamNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        // Make sure the link is running whenever we're allowed to read notifications.
        LinkHub.startService(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!shouldForward(sbn)) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        LinkHub.send(
            Protocol.notification(
                id = sbn.key,
                app = sbn.packageName,
                appName = appName(sbn),
                title = title.take(500),
                text = text.take(4000),
                time = sbn.postTime,
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
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
}
