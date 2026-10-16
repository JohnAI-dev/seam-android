package dev.johnai.seam

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether Seam's notification listener is enabled. MediaSessionManager only accepts that
 * component — there is no separate media permission. MEDIA_CONTENT_CONTROL is a signature
 * permission this app cannot hold.
 */
object NotificationAccess {
    fun component(context: Context): ComponentName =
        ComponentName(context, SeamNotificationListener::class.java)

    fun granted(context: Context): Boolean {
        val enabled = try {
            // Hidden setting: not in the public SDK stubs. Same string MainActivity used.
            Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS)
        } catch (_: Exception) {
            null
        }
        return Media.notificationListenerEnabled(enabled, component(context).flattenToString())
    }
}

/**
 * Whether the person turned on "what's playing". Missing or false stays off: notification
 * access alone does not share media.
 */
object MediaShare {
    fun enabled(stored: Boolean?): Boolean = stored == true

    /** Watch sessions only when sharing is on and the notification listener is enabled. */
    fun shouldWatch(optedIn: Boolean, listenerEnabled: Boolean): Boolean =
        optedIn && listenerEnabled
}

/** `seam_media` / `share`. Default off. [setEnabled] uses commit so a service start sees it. */
class MediaSharing(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(): Boolean =
        MediaShare.enabled(if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null)

    fun setEnabled(on: Boolean): Boolean = prefs.edit().putBoolean(KEY, on).commit()

    private companion object {
        const val PREFS = "seam_media"
        const val KEY = "share"
    }
}

/**
 * What to tell the computer about the session, without Android types.
 *
 * Sharing is opt-in. A control does not change the stored session. After welcome the phone
 * sends the current session again, or `media_stopped` when it is not sharing. A malformed
 * control is ignored. The desktop drops the session on disconnect, so this object keeps the
 * last session and sends it again on the next welcome.
 */
class NowPlayingLogic(private val throttle: MediaThrottle = MediaThrottle()) {
    var optedIn: Boolean = false
        private set
    var listenerEnabled: Boolean = false
        private set
    private var session: Media.Snapshot? = null

    fun shouldWatch(): Boolean = MediaShare.shouldWatch(optedIn, listenerEnabled)

    /** Session last reported while watching. A control does not change this. */
    fun followedSession(): Media.Snapshot? = session

    /**
     * Apply the switch and the listener. Turning sharing off clears the session and, when
     * [linkUp], sends `media_stopped`. Turning it on waits for [onSessions].
     */
    fun setAccess(
        optedIn: Boolean,
        listenerEnabled: Boolean,
        linkUp: Boolean,
        nowMs: Long,
    ): MediaThrottle.Outcome {
        val wasWatching = shouldWatch()
        this.optedIn = optedIn
        this.listenerEnabled = listenerEnabled
        if (shouldWatch() || !wasWatching) return MediaThrottle.Outcome(null, null)
        session = null
        if (!linkUp) return MediaThrottle.Outcome(null, null)
        return throttle.onUpdate(null, nowMs)
    }

    /**
     * Right after welcome. Resets the once-per-second window so the current session is sent
     * again. When not watching, sends `media_stopped` if [linkUp]. When watching, sends
     * nothing: the caller must [onSessions] with the current session.
     */
    fun onWelcome(linkUp: Boolean, nowMs: Long): MediaThrottle.Outcome {
        throttle.reset()
        if (!linkUp || shouldWatch()) return MediaThrottle.Outcome(null, null)
        session = null
        return throttle.onUpdate(null, nowMs)
    }

    /**
     * [picked] null means nothing left to follow. Ignored unless [shouldWatch]. Not sent
     * unless [linkUp], so a frame is not queued onto the next connection.
     */
    fun onSessions(picked: Media.Snapshot?, linkUp: Boolean, nowMs: Long): MediaThrottle.Outcome {
        if (!shouldWatch()) return MediaThrottle.Outcome(null, null)
        session = picked
        if (!linkUp) return MediaThrottle.Outcome(null, null)
        return throttle.onUpdate(picked, nowMs)
    }

    /**
     * Command for a known action, or null when sharing is off or [action] is unknown.
     * [playing] defaults to the stored session. Does not change [followedSession].
     */
    fun onControl(action: String?, playing: Boolean? = null): Media.Command? {
        if (!shouldWatch() || action == null) return null
        return Media.command(action, playing ?: (session?.playing == true))
    }

    /** [onControl] for one frame. Malformed and unknown actions are null and leave the session. */
    fun onControlFrame(frame: String): Media.Command? = onControl(Media.parseControl(frame))

    fun flush(nowMs: Long): MediaThrottle.Outcome = throttle.flush(nowMs)

    /** Service is going away. Does not change the saved opt-in. */
    fun shutdown(linkUp: Boolean, nowMs: Long): MediaThrottle.Outcome {
        val had = shouldWatch() || session != null
        optedIn = false
        listenerEnabled = false
        session = null
        if (!had || !linkUp) return MediaThrottle.Outcome(null, null)
        return throttle.onUpdate(null, nowMs)
    }
}

/**
 * Reports the active media session and applies media_control (protocol/PROTOCOL.md, "Media").
 *
 * Sharing is a separate switch ([MediaSharing]), off until the person turns it on.
 * Notification access is also required: watching calls
 * `MediaSessionManager.getActiveSessions(ComponentName(this, SeamNotificationListener::class.java))`
 * and [MediaSessionManager.addOnActiveSessionsChangedListener]. Both throw [SecurityException]
 * unless that listener is enabled, so they are not called until [MediaShare.shouldWatch] is
 * true, and a [SecurityException] drops the watch instead of crashing the link.
 *
 * The first playing session is followed, or the most recently updated session when none is
 * playing. No session left sends `media_stopped`. A paused session is still `media`. Metadata
 * and playback changes are throttled to one frame a second; the desktop moves the shown
 * position on its own clock. Art is a JPEG of at most 64 KiB starting with FF D8. The current
 * state is sent again right after welcome. Disconnect does not clear the local session.
 *
 * Volume actions use [AudioManager.adjustStreamVolume] on [AudioManager.STREAM_MUSIC] and do
 * not need a session. Transport actions no-op when there is nothing to follow. `toggle`
 * pauses only while the followed session is playing. A control does not change the stored
 * session; the next session callback does. Unknown and malformed controls are ignored.
 */
@SuppressLint("MissingPermission")
class NowPlaying(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val work: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "seam-media").apply { isDaemon = true }
    }
    private val logic = NowPlayingLogic()
    private val publishGen = AtomicInteger()

    private var manager: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null
    private var registered = false
    private var followed: MediaController? = null
    private var cachedArt: CachedArt? = null
    private var closed = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onSessions(controllers)
    }

    private val sessionCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            requestPublish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            requestPublish()
        }

        override fun onSessionDestroyed() {
            followed = null
            refreshSessions()
        }
    }

    private val flushRunnable = Runnable {
        if (closed || !connected()) return@Runnable
        val now = SystemClock.elapsedRealtime()
        dispatch(logic.flush(now), now)
    }

    /**
     * Match the share switch and notification access. Idempotent. Does not call
     * MediaSessionManager unless both are on.
     */
    fun start() {
        onMain {
            if (closed) return@onMain
            val now = SystemClock.elapsedRealtime()
            val outcome = logic.setAccess(
                optedIn = MediaSharing(app).enabled(),
                listenerEnabled = NotificationAccess.granted(app),
                linkUp = connected(),
                nowMs = now,
            )
            if (!logic.shouldWatch()) {
                publishGen.incrementAndGet()
                dropWatch()
                dispatch(outcome, now)
                return@onMain
            }
            dispatch(outcome, now)
            attach()
        }
    }

    /** A new link: send the current session again, or media_stopped when not sharing. */
    fun onConnected() {
        onMain {
            if (closed) return@onMain
            main.removeCallbacks(flushRunnable)
            publishGen.incrementAndGet()
            val now = SystemClock.elapsedRealtime()
            dispatch(logic.onWelcome(linkUp = true, nowMs = now), now)
            if (!logic.shouldWatch()) return@onMain
            if (registered) refreshSessions() else attach()
        }
    }

    /** Notification access was removed. Stop watching and tell the computer playback ended. */
    fun onAccessLost() {
        onMain {
            if (closed) return@onMain
            val now = SystemClock.elapsedRealtime()
            val outcome = logic.setAccess(
                optedIn = logic.optedIn,
                listenerEnabled = false,
                linkUp = connected(),
                nowMs = now,
            )
            publishGen.incrementAndGet()
            dropWatch()
            dispatch(outcome, now)
        }
    }

    /**
     * A media_control action from the computer. Unknown actions and sharing-off do nothing.
     * The stored session is left for the next session callback.
     */
    fun onControl(action: String) {
        onMain {
            if (closed) return@onMain
            val playing = try {
                Media.isPlaying(followed?.playbackState?.state ?: -1)
            } catch (_: Exception) {
                logic.followedSession()?.playing == true
            }
            val command = logic.onControl(action, playing) ?: return@onMain
            try {
                apply(command)
            } catch (_: Exception) {
            }
        }
    }

    /** Unregister and drop queued encodes. Idempotent. */
    fun stop() {
        onMain {
            if (closed) return@onMain
            closed = true
            publishGen.incrementAndGet()
            main.removeCallbacks(flushRunnable)
            val now = SystemClock.elapsedRealtime()
            val outcome = logic.shutdown(connected(), now)
            dropWatch()
            try {
                work.shutdownNow()
            } catch (_: Exception) {
            }
            dispatch(outcome, now)
        }
    }

    private fun attach() {
        val component = ComponentName(app, SeamNotificationListener::class.java)
        val msm = app.getSystemService(MediaSessionManager::class.java) ?: return
        try {
            if (!registered) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
                registered = true
                manager = msm
                listenerComponent = component
            }
            onSessions(msm.getActiveSessions(component))
        } catch (_: SecurityException) {
            val now = SystemClock.elapsedRealtime()
            val outcome = logic.setAccess(
                optedIn = logic.optedIn,
                listenerEnabled = false,
                linkUp = connected(),
                nowMs = now,
            )
            publishGen.incrementAndGet()
            dropWatch()
            dispatch(outcome, now)
        } catch (_: Exception) {
        }
    }

    private fun dropWatch() {
        unregister()
        clearFollowed()
        cachedArt = null
    }

    private fun connected(): Boolean = LinkHub.status.state == LinkClient.State.CONNECTED

    private fun refreshSessions() {
        val msm = manager ?: return
        val component = listenerComponent ?: return
        if (!registered) return
        try {
            onSessions(msm.getActiveSessions(component))
        } catch (_: SecurityException) {
            onAccessLost()
        } catch (_: Exception) {
        }
    }

    private fun onSessions(controllers: List<MediaController>?) {
        val list = controllers.orEmpty()
        val playing = ArrayList<Boolean>(list.size)
        val recent = ArrayList<Long>(list.size)
        for (controller in list) {
            val state = try {
                controller.playbackState
            } catch (_: Exception) {
                null
            }
            playing.add(Media.isPlaying(state?.state ?: -1))
            recent.add(state?.lastPositionUpdateTime ?: 0L)
        }
        val index = Media.pickIndex(playing, recent)
        follow(if (index == null) null else list[index])
        requestPublish()
    }

    private fun follow(controller: MediaController?) {
        val current = followed
        if (current === controller) return
        // A session list hands out a new wrapper even when the session is unchanged.
        if (current != null) {
            try {
                current.unregisterCallback(sessionCallback)
            } catch (_: Exception) {
            }
        }
        followed = controller
        if (controller == null) return
        try {
            controller.registerCallback(sessionCallback, main)
        } catch (_: Exception) {
            followed = null
        }
    }

    private fun clearFollowed() {
        val current = followed
        followed = null
        if (current == null) return
        try {
            current.unregisterCallback(sessionCallback)
        } catch (_: Exception) {
        }
    }

    private fun unregister() {
        if (!registered) return
        val msm = manager
        registered = false
        manager = null
        listenerComponent = null
        if (msm == null) return
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
    }

    private fun requestPublish() {
        if (closed) return
        val gen = publishGen.incrementAndGet()
        val controller = followed
        if (controller == null) {
            report(null)
            return
        }
        val reading = try {
            read(controller)
        } catch (_: Exception) {
            return
        }
        val cached = cachedArt
        if (reading.artToken != 0 && cached != null && reading.artToken == cached.token) {
            report(reading.snapshot(cached.jpeg))
            return
        }
        val raw = reading.rawArt
        if (raw == null) {
            report(reading.snapshot(null))
            return
        }
        val copy = copyForEncode(raw)
        if (copy == null) {
            report(reading.snapshot(null))
            return
        }
        if (work.isShutdown) {
            recycle(copy)
            report(reading.snapshot(null))
            return
        }
        try {
            work.execute {
                val jpeg = try {
                    jpegAtMost(copy, Media.ART_MAX_BYTES)
                } catch (_: Exception) {
                    null
                } finally {
                    recycle(copy)
                }
                main.post {
                    if (publishGen.get() != gen) return@post
                    if (jpeg != null && reading.artToken != 0) {
                        cachedArt = CachedArt(reading.artToken, jpeg)
                    }
                    report(reading.snapshot(jpeg))
                }
            }
        } catch (_: RejectedExecutionException) {
            recycle(copy)
            if (publishGen.get() == gen) report(reading.snapshot(null))
        }
    }

    private fun read(controller: MediaController): Reading {
        val pkg = Media.clipText(controller.packageName.orEmpty())
        val metadata = controller.metadata
        val state = controller.playbackState
        val playing = Media.isPlaying(state?.state ?: -1)
        val speed = state?.playbackSpeed?.takeIf { it.isFinite() } ?: 1f
        val duration = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)
        val position = if (state == null) {
            0L
        } else {
            Media.extrapolatedPosition(
                positionMs = state.position,
                updatedAtMs = state.lastPositionUpdateTime,
                speed = speed,
                playing = playing,
                nowMs = SystemClock.elapsedRealtime(),
                durationMs = duration,
            )
        }
        val art = artwork(metadata)
        return Reading(
            app = pkg,
            appName = appLabel(pkg),
            title = text(metadata, MediaMetadata.METADATA_KEY_TITLE),
            artist = text(metadata, MediaMetadata.METADATA_KEY_ARTIST),
            album = text(metadata, MediaMetadata.METADATA_KEY_ALBUM),
            playing = playing,
            positionMs = position,
            durationMs = duration,
            speed = speed,
            artToken = if (art == null) 0 else System.identityHashCode(art),
            rawArt = art,
        )
    }

    private fun report(snapshot: Media.Snapshot?) {
        val now = SystemClock.elapsedRealtime()
        dispatch(logic.onSessions(snapshot, connected(), now), now)
    }

    private fun dispatch(outcome: MediaThrottle.Outcome, now: Long) {
        outcome.send?.let { LinkHub.send(it) }
        arm(outcome.flushAtMs, now)
    }

    private fun arm(flushAtMs: Long?, now: Long) {
        main.removeCallbacks(flushRunnable)
        if (flushAtMs == null || closed) return
        main.postDelayed(flushRunnable, (flushAtMs - now).coerceAtLeast(0L))
    }

    private fun apply(command: Media.Command) {
        val transport = try {
            followed?.transportControls
        } catch (_: Exception) {
            null
        }
        when (command) {
            Media.Command.PLAY -> transport?.play()
            Media.Command.PAUSE -> transport?.pause()
            Media.Command.NEXT -> transport?.skipToNext()
            Media.Command.PREVIOUS -> transport?.skipToPrevious()
            Media.Command.VOLUME_UP -> volume(Media.volumeAdjustment("volume_up"))
            Media.Command.VOLUME_DOWN -> volume(Media.volumeAdjustment("volume_down"))
        }
    }

    /** [direction] is [Media.VOLUME_UP] or [Media.VOLUME_DOWN], and must match AudioManager. */
    private fun volume(direction: Int?) {
        val adjust = when (direction) {
            AudioManager.ADJUST_RAISE, AudioManager.ADJUST_LOWER -> direction
            else -> return
        }
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
    }

    private fun appLabel(packageName: String): String {
        if (packageName.isEmpty()) return ""
        return try {
            val pm = app.packageManager
            Media.clipText(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString())
        } catch (_: Exception) {
            packageName
        }
    }

    private fun text(metadata: MediaMetadata?, key: String): String =
        Media.clipText(metadata?.getString(key).orEmpty())

    private fun artwork(metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null
        return try {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        } catch (_: Exception) {
            null
        }
    }

    /** A software bitmap this class owns, long side at most 720 px, or null. */
    private fun copyForEncode(source: Bitmap): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        return try {
            val software = source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            val longSide = maxOf(software.width, software.height)
            if (longSide <= ART_SIDE) return software
            val scale = ART_SIDE.toDouble() / longSide
            val scaled = Bitmap.createScaledBitmap(
                software,
                maxOf(1, (software.width * scale).toInt()),
                maxOf(1, (software.height * scale).toInt()),
                true,
            )
            if (scaled !== software && !software.isRecycled) software.recycle()
            scaled
        } catch (_: Exception) {
            null
        }
    }

    /** JPEG of at most [maxBytes], lowering quality and then pixel size. Does not recycle [source]. */
    private fun jpegAtMost(source: Bitmap, maxBytes: Int): ByteArray? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        var current = source
        var owned: Bitmap? = null
        try {
            var guard = 0
            while (guard++ < 12) {
                var quality = 85
                while (quality >= 20) {
                    val out = ByteArrayOutputStream()
                    if (!current.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                    val bytes = out.toByteArray()
                    if (bytes.size in 1..maxBytes && Media.acceptableArt(bytes) != null) return bytes
                    quality -= 15
                }
                val longSide = maxOf(current.width, current.height)
                if (longSide <= 16) return null
                val w = maxOf(1, current.width * 3 / 4)
                val h = maxOf(1, current.height * 3 / 4)
                if (w == current.width && h == current.height) return null
                val scaled = Bitmap.createScaledBitmap(current, w, h, true)
                val previous = owned
                if (previous != null && previous !== source && previous !== scaled && !previous.isRecycled) {
                    previous.recycle()
                }
                owned = if (scaled === source) null else scaled
                current = scaled
            }
            return null
        } finally {
            val extra = owned
            if (extra != null && extra !== source && !extra.isRecycled) extra.recycle()
        }
    }

    private fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            try {
                bitmap.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    private class Reading(
        val app: String,
        val appName: String,
        val title: String,
        val artist: String,
        val album: String,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val speed: Float,
        val artToken: Int,
        val rawArt: Bitmap?,
    ) {
        fun snapshot(artJpeg: ByteArray?): Media.Snapshot = Media.Snapshot(
            app = app,
            appName = appName,
            title = title,
            artist = artist,
            album = album,
            playing = playing,
            positionMs = positionMs,
            durationMs = durationMs,
            speed = speed,
            artJpeg = artJpeg,
            artToken = if (artJpeg == null) 0 else artToken,
        )
    }

    private class CachedArt(val token: Int, val jpeg: ByteArray)
}

/** `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`, hidden from the public SDK. */
private const val ENABLED_LISTENERS = "enabled_notification_listeners"

private const val ART_SIDE = 720
