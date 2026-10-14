package dev.johnai.seam

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
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
 * Reports the active media session and applies media_control (protocol/PROTOCOL.md, "Media").
 *
 * Watching calls
 * `MediaSessionManager.getActiveSessions(ComponentName(this, SeamNotificationListener::class.java))`
 * and [MediaSessionManager.addOnActiveSessionsChangedListener]. Both throw [SecurityException]
 * unless the notification listener is enabled, so they are not called until
 * [NotificationAccess.granted] is true, and a [SecurityException] drops the watch instead of
 * crashing the link. The listener's [SeamNotificationListener.onListenerConnected] starts the
 * watch; [SeamNotificationListener.onListenerDisconnected] stops it.
 *
 * The first playing session is followed, or the most recently updated session when none is
 * playing. No session left sends `media_stopped`. Metadata and playback changes are throttled
 * to one frame a second. Art is a JPEG of at most 64 KiB. The current state is sent on connect.
 *
 * Volume actions use [AudioManager.adjustStreamVolume] on [AudioManager.STREAM_MUSIC] and do
 * not need a session. Transport actions no-op when there is nothing to follow. `toggle`
 * pauses only while the followed session is playing.
 */
@SuppressLint("MissingPermission")
class NowPlaying(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val work: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "seam-media").apply { isDaemon = true }
    }
    private val throttle = MediaThrottle()
    private val publishGen = AtomicInteger()

    private var manager: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null
    private var registered = false
    private var followed: MediaController? = null
    private var followedPlaying = false
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
            followedPlaying = Media.isPlaying(state?.state ?: -1)
            requestPublish()
        }

        override fun onSessionDestroyed() {
            followed = null
            followedPlaying = false
            refreshSessions()
        }
    }

    private val flushRunnable = Runnable {
        if (closed || LinkHub.status.state != LinkClient.State.CONNECTED) return@Runnable
        val now = SystemClock.elapsedRealtime()
        val outcome = throttle.flush(now)
        outcome.send?.let { LinkHub.send(it) }
        arm(outcome.flushAtMs, now)
    }

    /** Register the session listener when notification access is on. Idempotent. */
    fun start() {
        onMain {
            if (closed) return@onMain
            if (!NotificationAccess.granted(app)) {
                loseAccess()
                return@onMain
            }
            val component = ComponentName(app, SeamNotificationListener::class.java)
            val msm = app.getSystemService(MediaSessionManager::class.java) ?: return@onMain
            try {
                if (!registered) {
                    msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
                    registered = true
                    manager = msm
                    listenerComponent = component
                }
                onSessions(msm.getActiveSessions(component))
            } catch (_: SecurityException) {
                loseAccess()
            } catch (_: Exception) {
            }
        }
    }

    /** A new link: the computer needs the current session even if it has not changed. */
    fun onConnected() {
        onMain {
            if (closed) return@onMain
            throttle.reset()
            main.removeCallbacks(flushRunnable)
            publishGen.incrementAndGet()
            if (registered) refreshSessions() else report(null)
        }
    }

    /** Notification access was removed. Stop watching and tell the computer playback ended. */
    fun onAccessLost() {
        onMain {
            if (closed) return@onMain
            loseAccess()
        }
    }

    /** A media_control action from the computer. Unknown actions never arrive here. */
    fun onControl(action: String) {
        onMain {
            if (closed) return@onMain
            val playing = try {
                Media.isPlaying(followed?.playbackState?.state ?: -1)
            } catch (_: Exception) {
                followedPlaying
            }
            val command = Media.command(action, playing) ?: return@onMain
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
            val wasWatching = registered || followed != null
            unregister()
            clearFollowed()
            try {
                work.shutdownNow()
            } catch (_: Exception) {
            }
            if (wasWatching) report(null)
        }
    }

    private fun loseAccess() {
        val wasWatching = registered || followed != null
        publishGen.incrementAndGet()
        unregister()
        clearFollowed()
        cachedArt = null
        if (wasWatching || LinkHub.status.state == LinkClient.State.CONNECTED) report(null)
    }

    private fun refreshSessions() {
        val msm = manager ?: return
        val component = listenerComponent ?: return
        if (!registered) return
        try {
            onSessions(msm.getActiveSessions(component))
        } catch (_: SecurityException) {
            loseAccess()
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
        followedPlaying = false
        if (controller == null) return
        try {
            controller.registerCallback(sessionCallback, main)
            followedPlaying = Media.isPlaying(controller.playbackState?.state ?: -1)
        } catch (_: Exception) {
            followed = null
        }
    }

    private fun clearFollowed() {
        val current = followed
        followed = null
        followedPlaying = false
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
        val pkg = controller.packageName.orEmpty().take(Media.TEXT_MAX_CHARS)
        val metadata = controller.metadata
        val state = controller.playbackState
        val playing = Media.isPlaying(state?.state ?: -1)
        followedPlaying = playing
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
        if (LinkHub.status.state != LinkClient.State.CONNECTED) return
        val now = SystemClock.elapsedRealtime()
        val outcome = throttle.onUpdate(snapshot, now)
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
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                .take(Media.TEXT_MAX_CHARS)
        } catch (_: Exception) {
            packageName
        }
    }

    private fun text(metadata: MediaMetadata?, key: String): String =
        metadata?.getString(key)?.take(Media.TEXT_MAX_CHARS).orEmpty()

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
