package dev.johnai.seam

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shows "Open link from <computer>?" and opens the URL only when that notification is tapped.
 * Never starts a browser by itself. See protocol/PROTOCOL.md, "Opening links".
 */
object OpenLinks {
    const val CHANNEL_ID = "links"
    private val nextId = AtomicInteger(9000)

    @SuppressLint("MissingPermission")
    fun show(context: Context, url: String, computerName: String) {
        val safe = LinkUrls.openableHttpUrl(url) ?: return
        // Punycode, brackets, and an explicit port: the prompt must match ACTION_VIEW.
        val hostText = LinkUrls.displayHost(safe) ?: return
        val app = context.applicationContext
        if (!canNotify(app)) return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            app.getString(R.string.links_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = app.getString(R.string.links_channel_description)
        nm.createNotificationChannel(channel)
        val name = computerName.filter { !it.isISOControl() }.trim().take(100).ifEmpty {
            app.getString(R.string.open_link_computer)
        }
        val id = nextId.getAndIncrement()
        val pending = PendingIntent.getActivity(
            app,
            id,
            Intent(app, OpenLinkActivity::class.java)
                .setAction(OpenLinkActivity.ACTION_OPEN)
                .putExtra(OpenLinkActivity.EXTRA_URL, safe)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            id,
            Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_seam)
                .setContentTitle(app.getString(R.string.open_link_title, name))
                .setContentText(hostText)
                .setStyle(Notification.BigTextStyle().bigText(hostText))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build(),
        )
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Notification tap: re-check the URL, then open it with ACTION_VIEW.
 * Not exported. A PendingIntent must be explicit on API 34+, so the browser intent
 * is started here instead of from the notification itself. Nothing opens unless the
 * person taps the notification.
 */
class OpenLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (intent?.action == ACTION_OPEN) open(intent)
        } finally {
            finish()
        }
    }

    @SuppressLint("UnsafeImplicitIntentLaunch")
    private fun open(intent: Intent?) {
        val raw = intent?.getStringExtra(EXTRA_URL) ?: return
        val safe = LinkUrls.openableHttpUrl(raw) ?: return
        val expectedHost = LinkUrls.hostOf(safe) ?: return
        val uri = Uri.parse(safe)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return
        if (scheme != "http" && scheme != "https") return
        val host = uri.host ?: return
        // Locale.ROOT: default-locale lowercase turns I into ı and the tap does nothing.
        if (!LinkUrls.hostsEqual(host, expectedHost)) return
        if (uri.port != LinkUrls.portOf(safe)) return
        val view = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(view)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_OPEN = "dev.johnai.seam.action.OPEN_LINK"
        const val EXTRA_URL = "dev.johnai.seam.extra.OPEN_URL"
    }
}
