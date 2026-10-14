package dev.johnai.seam

import org.json.JSONObject
import java.util.Base64
import kotlin.math.abs

/**
 * Now-playing reports and media controls (protocol/PROTOCOL.md, "Media").
 * No Android types, so frame building, throttling, and media_control parsing run in JVM tests.
 *
 * The phone follows the first playing session, or the most recently updated one when nothing
 * is playing. [stopped] is sent when no session is left to follow (a paused session is still
 * a [frame] with `playing: false`). Reports are at most one per [INTERVAL_MS]; a burst keeps
 * the latest. A position that merely advances with playback is not a new report.
 */
object Media {
    /** media art is a JPEG of at most 64 KiB. */
    const val ART_MAX_BYTES = Protocol.MEDIA_ART_MAX_BYTES

    /** At most one media or media_stopped frame per second. */
    const val INTERVAL_MS = 1_000L

    /**
     * android.media.session.PlaybackState.STATE_PLAYING.
     * Buffering, paused, stopped, and seeking are not playing.
     */
    const val PLAYBACK_PLAYING = 3

    /** Safety cap so a media line stays under the 1 MiB frame limit. The spec does not set one. */
    const val TEXT_MAX_CHARS = 4_000

    /** How far a paused position may drift before it counts as a seek. */
    const val PAUSED_SLOP_MS = 500L

    /** How far a playing position may drift from speed × elapsed before it counts as a seek. */
    const val POSITION_TOLERANCE_MS = 1_500L

    /** AudioManager.ADJUST_RAISE. volume_up passes this to adjustStreamVolume. */
    const val VOLUME_UP = 1

    /** AudioManager.ADJUST_LOWER. volume_down passes this to adjustStreamVolume. */
    const val VOLUME_DOWN = -1

    val ACTIONS = setOf(
        "play", "pause", "toggle", "next", "previous", "volume_up", "volume_down",
    )

    /** True only for [PLAYBACK_PLAYING]. */
    fun isPlaying(playbackState: Int): Boolean = playbackState == PLAYBACK_PLAYING

    /**
     * True when [enabledSetting] (`Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`,
     * colon-separated flattened component names) lists [flatComponent] exactly.
     * MediaSessionManager is only allowed to use that listener component.
     */
    fun notificationListenerEnabled(enabledSetting: String?, flatComponent: String): Boolean {
        if (enabledSetting.isNullOrEmpty() || flatComponent.isEmpty()) return false
        return enabledSetting.split(':').any { it == flatComponent }
    }

    /**
     * Index of the session to follow, or null when [playing] is empty.
     * The first `true` wins. Otherwise the greatest [recentMs] that is &gt; 0, or index 0
     * when every timestamp is missing (the system's own priority order).
     */
    fun pickIndex(playing: List<Boolean>, recentMs: List<Long>): Int? {
        val n = minOf(playing.size, recentMs.size)
        if (n <= 0) return null
        for (i in 0 until n) {
            if (playing[i]) return i
        }
        var best = 0
        var bestRecent = recentMs[0]
        for (i in 1 until n) {
            if (recentMs[i] > bestRecent) {
                best = i
                bestRecent = recentMs[i]
            }
        }
        return if (bestRecent > 0L) best else 0
    }

    /**
     * Where playback is now. [updatedAtMs] and [nowMs] are elapsedRealtime milliseconds.
     * While [playing], position advances by [speed] × elapsed. Unknown or negative inputs
     * become 0. A positive [durationMs] caps the result.
     */
    fun extrapolatedPosition(
        positionMs: Long,
        updatedAtMs: Long,
        speed: Float,
        playing: Boolean,
        nowMs: Long,
        durationMs: Long = 0L,
    ): Long {
        var pos = positionMs
        if (playing && updatedAtMs > 0L && nowMs > updatedAtMs && speed.isFinite() && speed != 0f) {
            pos += ((nowMs - updatedAtMs) * speed.toDouble()).toLong()
        }
        if (pos < 0L) pos = 0L
        if (durationMs > 0L && pos > durationMs) pos = durationMs
        return pos
    }

    /**
     * True when [positionMs] at [atMs] is where playback from [fromPositionMs] at [fromMs]
     * would be. A seek, a play/pause change, or a clock step backwards is false.
     */
    fun positionFollowsPlayback(
        fromPositionMs: Long,
        fromMs: Long,
        speed: Float,
        playing: Boolean,
        positionMs: Long,
        atMs: Long,
        playingNow: Boolean,
    ): Boolean {
        if (playing != playingNow) return false
        val delta = positionMs - fromPositionMs
        if (!playingNow) return abs(delta) <= PAUSED_SLOP_MS
        if (atMs < fromMs) return false
        val elapsed = atMs - fromMs
        val rate = if (speed.isFinite()) speed.toDouble() else 1.0
        val expected = (elapsed * rate).toLong()
        val tolerance = maxOf(POSITION_TOLERANCE_MS, elapsed / 5)
        return abs(delta - expected) <= tolerance
    }

    /** `media` frame. Art that is missing, not a JPEG, or larger than [ART_MAX_BYTES] is omitted. */
    fun frame(snapshot: Snapshot): String {
        val o = JSONObject()
            .put("type", "media")
            .put("app", snapshot.app.take(TEXT_MAX_CHARS))
            .put("app_name", snapshot.appName.take(TEXT_MAX_CHARS))
            .put("title", snapshot.title.take(TEXT_MAX_CHARS))
            .put("artist", snapshot.artist.take(TEXT_MAX_CHARS))
            .put("album", snapshot.album.take(TEXT_MAX_CHARS))
            .put("playing", snapshot.playing)
            .put("position_ms", snapshot.positionMs.coerceAtLeast(0L))
            .put("duration_ms", snapshot.durationMs.coerceAtLeast(0L))
        val art = acceptableArt(snapshot.artJpeg)
        if (art != null) o.put("art", Base64.getEncoder().encodeToString(art))
        return o.toString()
    }

    /** Nothing left to follow. A paused session is not this frame. */
    fun stopped(): String = JSONObject().put("type", "media_stopped").toString()

    /**
     * Action from a media_control frame, or null if it is not one or the action is unknown.
     * Unknown actions are ignored (protocol/PROTOCOL.md, "Media").
     */
    fun parseControl(frame: String): String? {
        val msg = Protocol.parse(frame) ?: return null
        if (msg.opt("type") != "media_control") return null
        if (msg.isNull("action")) return null
        val action = msg.opt("action") as? String ?: return null
        return action.takeIf { it in ACTIONS }
    }

    /**
     * What to do with a parsed action. `toggle` pauses when [playing], and plays otherwise.
     * Null when [action] is not a known media_control action.
     */
    fun command(action: String, playing: Boolean): Command? = when (action) {
        "play" -> Command.PLAY
        "pause" -> Command.PAUSE
        "toggle" -> if (playing) Command.PAUSE else Command.PLAY
        "next" -> Command.NEXT
        "previous" -> Command.PREVIOUS
        "volume_up" -> Command.VOLUME_UP
        "volume_down" -> Command.VOLUME_DOWN
        else -> null
    }

    /** Direction for AudioManager.adjustStreamVolume, or null when [action] is not a volume action. */
    fun volumeAdjustment(action: String): Int? = when (action) {
        "volume_up" -> VOLUME_UP
        "volume_down" -> VOLUME_DOWN
        else -> null
    }

    /** JPEG bytes small enough to put in `art`, or null. */
    fun acceptableArt(bytes: ByteArray?): ByteArray? {
        if (bytes == null || bytes.size < 2 || bytes.size > ART_MAX_BYTES) return null
        if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        return bytes
    }

    enum class Command { PLAY, PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN }

    /**
     * One session's now-playing values. [speed] and [artToken] are not sent: speed tells a
     * seek from playback, and [artToken] is the artwork's identity so a re-encode is not a change.
     * 0 means no artwork.
     */
    data class Snapshot(
        val app: String,
        val appName: String,
        val title: String,
        val artist: String,
        val album: String,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val speed: Float = 1f,
        val artJpeg: ByteArray? = null,
        val artToken: Int = 0,
    ) {
        /** Same track, play state, duration, and artwork. Position and speed are compared elsewhere. */
        fun sameTrack(other: Snapshot): Boolean =
            app == other.app &&
                appName == other.appName &&
                title == other.title &&
                artist == other.artist &&
                album == other.album &&
                playing == other.playing &&
                durationMs == other.durationMs &&
                artToken == other.artToken
    }
}

/**
 * At most one media report per [intervalMs]. The first report (and the first after [reset])
 * is sent immediately. A later change inside the window is kept and sent when the window ends,
 * with position advanced to that moment while it was playing. Position that follows playback
 * is not a change. [reset] is for a new connection, which must hear the current state again.
 */
class MediaThrottle(private val intervalMs: Long = Media.INTERVAL_MS) {
    data class Outcome(val send: String?, val flushAtMs: Long?)

    private var sentAtMs: Long = Long.MIN_VALUE
    private var sent: Media.Snapshot? = null
    private var sentStopped = false
    private var hasSent = false
    private var pendingSnapshot: Media.Snapshot? = null
    private var pendingStopped = false
    private var pending = false
    private var pendingAtMs = 0L

    fun reset() {
        sentAtMs = Long.MIN_VALUE
        sent = null
        sentStopped = false
        hasSent = false
        clearPending()
    }

    /** [snapshot] null means nothing is playing any more. [nowMs] is a monotonic clock. */
    fun onUpdate(snapshot: Media.Snapshot?, nowMs: Long): Outcome {
        if (!changed(snapshot, nowMs)) {
            clearPending()
            return Outcome(null, null)
        }
        if (!hasSent || nowMs - sentAtMs >= intervalMs) {
            clearPending()
            return emit(snapshot, nowMs, nowMs)
        }
        pending = true
        pendingStopped = snapshot == null
        pendingSnapshot = snapshot
        pendingAtMs = nowMs
        return Outcome(null, sentAtMs + intervalMs)
    }

    /** Send the held update once [nowMs] has reached the time [onUpdate] returned. */
    fun flush(nowMs: Long): Outcome {
        if (!pending) return Outcome(null, null)
        val due = sentAtMs + intervalMs
        if (hasSent && nowMs < due) return Outcome(null, due)
        val snapshot = pendingSnapshot
        val stopped = pendingStopped
        val capturedAt = pendingAtMs
        clearPending()
        if (stopped) return emit(null, nowMs, nowMs)
        return emit(snapshot, capturedAt, nowMs)
    }

    private fun emit(snapshot: Media.Snapshot?, capturedAtMs: Long, nowMs: Long): Outcome {
        if (snapshot == null) {
            markSent(null, nowMs)
            return Outcome(Media.stopped(), null)
        }
        val advanced = advance(snapshot, capturedAtMs, nowMs)
        markSent(advanced, nowMs)
        return Outcome(Media.frame(advanced), null)
    }

    private fun advance(snapshot: Media.Snapshot, fromMs: Long, nowMs: Long): Media.Snapshot {
        if (!snapshot.playing || nowMs <= fromMs) return snapshot
        val pos = Media.extrapolatedPosition(
            positionMs = snapshot.positionMs,
            updatedAtMs = fromMs,
            speed = snapshot.speed,
            playing = true,
            nowMs = nowMs,
            durationMs = snapshot.durationMs.coerceAtLeast(0L),
        )
        return if (pos == snapshot.positionMs) snapshot else snapshot.copy(positionMs = pos)
    }

    private fun changed(snapshot: Media.Snapshot?, nowMs: Long): Boolean {
        if (!hasSent) return true
        if (snapshot == null) return !sentStopped
        if (sentStopped || sent == null) return true
        val prev = sent ?: return true
        if (!prev.sameTrack(snapshot)) return true
        if (abs(prev.speed - snapshot.speed) > 0.01f) return true
        return !Media.positionFollowsPlayback(
            fromPositionMs = prev.positionMs,
            fromMs = sentAtMs,
            speed = prev.speed,
            playing = prev.playing,
            positionMs = snapshot.positionMs,
            atMs = nowMs,
            playingNow = snapshot.playing,
        )
    }

    private fun markSent(snapshot: Media.Snapshot?, nowMs: Long) {
        hasSent = true
        sentAtMs = nowMs
        sentStopped = snapshot == null
        sent = snapshot
    }

    private fun clearPending() {
        pending = false
        pendingStopped = false
        pendingSnapshot = null
        pendingAtMs = 0L
    }
}
