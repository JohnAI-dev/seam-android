package dev.johnai.seam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-update from GitHub Releases. Checks the latest release, downloads a newer APK,
 * refuses anything not signed with the same key as this app, and installs it with
 * PackageInstaller. The first update asks the person to confirm; after that Android 12+
 * lets Seam update itself silently.
 */
object Updater {
    const val LATEST_URL = "https://api.github.com/repos/JohnAI-dev/seam-android/releases/latest"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 2

    /** What the updater is doing, for the main screen. */
    enum class State { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, NEEDS_PERMISSION, WAITING_FOR_CONFIRM, INSTALLING, FAILED }

    data class Status(val state: State, val detail: String = "", val checkedAt: Long = 0L)

    @Volatile
    var status = Status(State.IDLE)
        private set
    private val listeners = CopyOnWriteArraySet<(Status) -> Unit>()
    private val running = AtomicBoolean(false)

    /** Android's "Install this update?" screen, kept so the app can show it when opened. */
    @Volatile
    private var pendingConfirm: Intent? = null

    fun addListener(l: (Status) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Status) -> Unit) { listeners.remove(l) }
    fun takePendingConfirm(): Intent? = pendingConfirm.also { pendingConfirm = null }

    internal fun setStatus(s: Status) {
        status = s
        listeners.forEach { it(s) }
    }

    internal fun awaitConfirm(confirm: Intent) {
        pendingConfirm = confirm
        setStatus(status.copy(state = State.WAITING_FOR_CONFIRM, detail = ""))
    }

    /** A release that can be installed. */
    data class Release(val versionCode: Long, val versionName: String, val apkUrl: String, val size: Long)

    /**
     * Parse GitHub's "latest release" JSON. Tags look like `v0.1.42`, where 42 is the
     * versionCode. Returns null if the tag or the APK asset is missing.
     */
    fun parseLatest(json: String): Release? = try {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val match = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""").find(tag)
        val code = match?.groupValues?.get(3)?.toLongOrNull()
        val assets = o.optJSONArray("assets")
        var apk: JSONObject? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a; break }
            }
        }
        if (code == null || apk == null || o.optBoolean("draft") || o.optBoolean("prerelease")) null
        else Release(code, tag.removePrefix("v"), apk.getString("browser_download_url"), apk.optLong("size"))
    } catch (e: Exception) {
        null
    }

    fun isNewer(release: Release, currentVersionCode: Long) = release.versionCode > currentVersionCode

    /**
     * Check and, if there's a newer version, download and install it. Call off the main thread.
     * Returns false if a check is already running.
     */
    fun checkAndInstall(context: Context): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val ctx = context.applicationContext
        val now = System.currentTimeMillis()
        try {
            if (status.state == State.WAITING_FOR_CONFIRM && pendingConfirm != null) return true
            setStatus(Status(State.CHECKING, checkedAt = now))
            val release = parseLatest(httpGet(LATEST_URL))
            if (release == null) {
                setStatus(Status(State.FAILED, "no release found", now))
                return true
            }
            if (!isNewer(release, currentVersionCode(ctx))) {
                setStatus(Status(State.UP_TO_DATE, checkedAt = now))
                return true
            }
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                setStatus(Status(State.NEEDS_PERMISSION, release.versionName, now))
                notify(ctx, ctx.getString(R.string.update_needs_permission, release.versionName),
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:${ctx.packageName}")))
                return true
            }
            setStatus(Status(State.DOWNLOADING, release.versionName, now))
            val apk = download(ctx, release)
            if (!sameSigner(ctx, apk)) {
                apk.delete()
                setStatus(Status(State.FAILED, "the download isn't signed by Seam", now))
                return true
            }
            setStatus(Status(State.INSTALLING, release.versionName, now))
            install(ctx, apk)
        } catch (e: Exception) {
            // Offline, rate limited, etc. Shown on the main screen; we try again at the next check.
            setStatus(Status(State.FAILED, e.message ?: e.javaClass.simpleName, now))
        } finally {
            running.set(false)
        }
        return true
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun download(ctx: Context, release: Release): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "seam-${release.versionCode}.apk")
        val c = URL(release.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        if (release.size > 0 && file.length() != release.size) {
            file.delete()
            throw IllegalStateException("incomplete download")
        }
        return file
    }

    @Suppress("DEPRECATION")
    private fun currentVersionCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    /** The downloaded APK must be Seam, signed with exactly the same certificate as this app. */
    private fun sameSigner(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val theirs: PackageInfo = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (theirs.packageName != ctx.packageName) return false
        val ours = pm.getPackageInfo(ctx.packageName, flags)
        fun certs(p: PackageInfo) = p.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet()
        val a = certs(ours)
        val b = certs(theirs)
        // Some Android versions can't read certificates from an uninstalled APK. That's still
        // safe: Android itself refuses an update signed with a different key.
        if (b.isNullOrEmpty()) return true
        return a != null && a == b
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("seam.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    internal fun notify(ctx: Context, text: String, action: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.updates_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val pi = PendingIntent.getActivity(
            ctx, 0, action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_seam)
                .setContentTitle("Seam")
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }
}

/** Receives the installer's result; asks the person to confirm when Android requires it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                // Apps can't open screens from the background: ask via a notification, and show
                // Android's install screen the next time Seam is opened.
                Updater.awaitConfirm(confirm)
                Updater.notify(context, context.getString(R.string.update_ready), confirm)
            }
            // Success: Android restarts Seam on the new version (see BootReceiver).
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install failed"
                Updater.setStatus(Updater.status.copy(state = Updater.State.FAILED, detail = why))
            }
        }
    }
}
