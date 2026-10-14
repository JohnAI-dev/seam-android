package dev.johnai.seam

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Media frames, throttling, and media_control. See protocol/PROTOCOL.md, "Media". */
class MediaTest {
    @Test
    fun mediaFrameHasTheFieldsTheDesktopExpects() {
        val snapshot = snap(
            app = "com.spotify.music",
            appName = "Spotify",
            title = "River",
            artist = "Ibeyi",
            album = "Ash",
            playing = true,
            positionMs = 12_500,
            durationMs = 212_000,
        )
        val frame = Media.frame(snapshot)
        assertEquals(frame, Protocol.media(snapshot))
        assertFalse(frame.contains("\n"))
        val json = JSONObject(frame)
        assertEquals(
            setOf("type", "app", "app_name", "title", "artist", "album", "playing", "position_ms", "duration_ms"),
            keys(json),
        )
        assertEquals("media", json.getString("type"))
        assertEquals("com.spotify.music", json.getString("app"))
        assertEquals("Spotify", json.getString("app_name"))
        assertEquals("River", json.getString("title"))
        assertEquals("Ibeyi", json.getString("artist"))
        assertEquals("Ash", json.getString("album"))
        assertTrue(json.getBoolean("playing"))
        assertEquals(12_500L, json.getLong("position_ms"))
        assertEquals(212_000L, json.getLong("duration_ms"))
        assertTrue(frame.contains("\"playing\":true"))
        assertNull(Protocol.parseMediaControl(frame))
        assertFalse(Protocol.isRing(frame))
        assertNull(Protocol.dismissId(frame))
        assertNull(Protocol.parseDndSet(frame))
    }

    @Test
    fun pausedSessionIsMediaNotStopped() {
        val json = JSONObject(Media.frame(snap(playing = false, positionMs = 40)))
        assertEquals("media", json.getString("type"))
        assertFalse(json.getBoolean("playing"))
        assertTrue(Media.frame(snap(playing = false)).contains("\"playing\":false"))
    }

    @Test
    fun textFieldsAreCappedAndNewlinesStayInsideTheJsonString() {
        val long = "x".repeat(Media.TEXT_MAX_CHARS + 50)
        val frame = Media.frame(snap(title = "a\nb", artist = long, app = long))
        assertFalse(frame.contains("\n"))
        val json = JSONObject(frame)
        assertEquals("a\nb", json.getString("title"))
        assertEquals(Media.TEXT_MAX_CHARS, json.getString("artist").length)
        assertEquals(Media.TEXT_MAX_CHARS, json.getString("app").length)
    }

    @Test
    fun negativePositionAndDurationAreSentAsZero() {
        val json = JSONObject(Media.frame(snap(positionMs = -20, durationMs = -1)))
        assertEquals(0L, json.getLong("position_ms"))
        assertEquals(0L, json.getLong("duration_ms"))
    }

    @Test
    fun positionAboveIntMaxStaysAJsonNumber() {
        val json = JSONObject(Media.frame(snap(positionMs = 4_000_000_000L, durationMs = 5_000_000_000L)))
        assertEquals(4_000_000_000L, json.getLong("position_ms"))
        assertEquals(5_000_000_000L, json.getLong("duration_ms"))
    }

    @Test
    fun artIsStandardBase64AndOmittedWhenUnusable() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte())
        val withArt = JSONObject(Media.frame(snap(artJpeg = jpeg)))
        assertEquals(jpeg.toList(), Base64.getDecoder().decode(withArt.getString("art")).toList())
        assertFalse(withArt.getString("art").contains("\n"))

        val exact = ByteArray(Media.ART_MAX_BYTES)
        exact[0] = 0xFF.toByte()
        exact[1] = 0xD8.toByte()
        assertTrue(JSONObject(Media.frame(snap(artJpeg = exact))).has("art"))

        val tooBig = ByteArray(Media.ART_MAX_BYTES + 1)
        tooBig[0] = 0xFF.toByte()
        tooBig[1] = 0xD8.toByte()
        assertFalse(JSONObject(Media.frame(snap(artJpeg = tooBig))).has("art"))
        assertFalse(JSONObject(Media.frame(snap(artJpeg = byteArrayOf(1, 2, 3, 4)))).has("art"))
        assertFalse(JSONObject(Media.frame(snap(artJpeg = byteArrayOf()))).has("art"))
        assertFalse(JSONObject(Media.frame(snap(artJpeg = null))).has("art"))
        assertEquals(64 * 1024, Media.ART_MAX_BYTES)
        assertEquals(Protocol.MEDIA_ART_MAX_BYTES, Media.ART_MAX_BYTES)
    }

    @Test
    fun stoppedFrameIsOnlyTheType() {
        val frame = Media.stopped()
        assertFalse(frame.contains("\n"))
        val json = JSONObject(frame)
        assertEquals(setOf("type"), keys(json))
        assertEquals("media_stopped", json.getString("type"))
        assertEquals(frame, Protocol.mediaStopped())
        assertNull(Protocol.parseMediaControl(frame))
    }

    @Test
    fun parseControlAcceptsOnlyTheSevenActions() {
        for (action in Media.ACTIONS) {
            assertEquals(action, Media.parseControl("""{"type":"media_control","action":"$action"}"""))
            assertEquals(action, Protocol.parseMediaControl("""{"type":"media_control","action":"$action","extra":1}"""))
        }
        assertEquals(
            "toggle",
            Protocol.parseMediaControl(""" { "type" : "media_control" , "action" : "toggle" } """),
        )
        assertNull(Media.parseControl("""{"type":"media_control","action":"stop"}"""))
        assertNull(Media.parseControl("""{"type":"media_control","action":"Play"}"""))
        assertNull(Media.parseControl("""{"type":"media_control","action":"play "}"""))
        assertNull(Media.parseControl("""{"type":"media_control","action":""}"""))
        assertNull(Media.parseControl("""{"type":"media_control"}"""))
        assertNull(Media.parseControl("""{"type":"media_control","action":null}"""))
        assertNull(Media.parseControl("""{"type":"media_control","action":1}"""))
        assertNull(Media.parseControl("""{"type":"media_control","action":true}"""))
        assertNull(Media.parseControl("""{"type":"media","action":"play"}"""))
        assertNull(Media.parseControl("""{"type":"dnd_set","on":true}"""))
        assertNull(Media.parseControl("""{"type":"ping"}"""))
        assertNull(Media.parseControl("not json"))
        assertNull(Media.parseControl(""))
        assertNull(Media.parseControl("{"))
        assertNull(Media.parseControl("[]"))
    }

    @Test
    fun toggleFollowsThePlayingFlagAndVolumeDirectionsMatchAudioManager() {
        assertEquals(Media.Command.PAUSE, Media.command("toggle", playing = true))
        assertEquals(Media.Command.PLAY, Media.command("toggle", playing = false))
        assertEquals(Media.Command.PLAY, Media.command("play", playing = true))
        assertEquals(Media.Command.PAUSE, Media.command("pause", playing = false))
        assertEquals(Media.Command.NEXT, Media.command("next", playing = false))
        assertEquals(Media.Command.PREVIOUS, Media.command("previous", playing = true))
        assertEquals(Media.Command.VOLUME_UP, Media.command("volume_up", playing = false))
        assertEquals(Media.Command.VOLUME_DOWN, Media.command("volume_down", playing = true))
        assertNull(Media.command("stop", playing = true))
        assertNull(Media.command("toggle ", playing = true))
        assertNull(Media.command("", playing = false))

        assertEquals(1, Media.VOLUME_UP)
        assertEquals(-1, Media.VOLUME_DOWN)
        assertEquals(Media.VOLUME_UP, Media.volumeAdjustment("volume_up"))
        assertEquals(Media.VOLUME_DOWN, Media.volumeAdjustment("volume_down"))
        assertNull(Media.volumeAdjustment("play"))
        assertNull(Media.volumeAdjustment("toggle"))
    }

    @Test
    fun playingStateIsOnlyTheAndroidPlayingConstant() {
        assertEquals(3, Media.PLAYBACK_PLAYING)
        assertTrue(Media.isPlaying(Media.PLAYBACK_PLAYING))
        assertFalse(Media.isPlaying(0))
        assertFalse(Media.isPlaying(1))
        assertFalse(Media.isPlaying(2))
        assertFalse(Media.isPlaying(4))
        assertFalse(Media.isPlaying(6))
        assertFalse(Media.isPlaying(-1))
    }

    @Test
    fun pickPrefersTheFirstPlayingSessionThenTheMostRecent() {
        assertNull(Media.pickIndex(emptyList(), emptyList()))
        assertEquals(0, Media.pickIndex(listOf(true), listOf(0L)))
        assertEquals(1, Media.pickIndex(listOf(false, true, true), listOf(50L, 1L, 99L)))
        assertEquals(2, Media.pickIndex(listOf(false, false, false), listOf(5L, 10L, 50L)))
        assertEquals(0, Media.pickIndex(listOf(false, false), listOf(0L, 0L)))
        assertEquals(0, Media.pickIndex(listOf(false, false), listOf(-1L, 0L)))
        assertEquals(1, Media.pickIndex(listOf(false, false), listOf(0L, 1L)))
        assertEquals(0, Media.pickIndex(listOf(true, false), listOf(1L, 9_000L)))
    }

    @Test
    fun positionExtrapolationUsesElapsedTimeAndSpeed() {
        assertEquals(1_000L, Media.extrapolatedPosition(0, 500, 1f, true, 1_500, 0))
        assertEquals(2_000L, Media.extrapolatedPosition(0, 500, 2f, true, 1_500, 0))
        assertEquals(400L, Media.extrapolatedPosition(400, 500, 1f, false, 5_000, 0))
        assertEquals(0L, Media.extrapolatedPosition(-50, 0, 1f, false, 10, 0))
        // An update time of 0 is unset, so the position is not advanced, but a known duration still caps it.
        assertEquals(900L, Media.extrapolatedPosition(900, 0, 1f, true, 5_000, 1_000))
        assertEquals(1_000L, Media.extrapolatedPosition(1_500, 0, 1f, false, 10, 1_000))
        assertEquals(1_000L, Media.extrapolatedPosition(900, 100, 1f, true, 5_000, 1_000))
        assertEquals(10L, Media.extrapolatedPosition(10, 100, 1f, true, 50, 0))
        assertEquals(10L, Media.extrapolatedPosition(10, 0, 1f, true, 500, 0))
    }

    @Test
    fun positionFollowsPlaybackUnlessItSeeks() {
        assertTrue(Media.positionFollowsPlayback(0, 0, 1f, true, 1_000, 1_000, true))
        assertTrue(Media.positionFollowsPlayback(0, 0, 1f, true, 2_400, 1_000, true))
        assertFalse(Media.positionFollowsPlayback(0, 0, 1f, true, 20_000, 1_000, true))
        assertTrue(Media.positionFollowsPlayback(100, 0, 1f, false, 100, 5_000, false))
        assertTrue(Media.positionFollowsPlayback(100, 0, 1f, false, 500, 5_000, false))
        assertFalse(Media.positionFollowsPlayback(100, 0, 1f, false, 2_000, 5_000, false))
        assertFalse(Media.positionFollowsPlayback(0, 0, 1f, true, 0, 0, false))
        assertFalse(Media.positionFollowsPlayback(0, 1_000, 1f, true, 0, 500, true))
        assertTrue(Media.positionFollowsPlayback(0, 0, 2f, true, 2_000, 1_000, true))
    }

    @Test
    fun throttleSendsImmediatelyThenHoldsTheLatestForASecond() {
        val throttle = MediaThrottle()
        val first = throttle.onUpdate(snap(title = "A", positionMs = 0), 10_000)
        assertEquals("A", JSONObject(first.send!!).getString("title"))
        assertNull(first.flushAtMs)

        val held = throttle.onUpdate(snap(title = "B", positionMs = 100), 10_400)
        assertNull(held.send)
        assertEquals(11_000L, held.flushAtMs)

        val replaced = throttle.onUpdate(snap(title = "C", positionMs = 200), 10_700)
        assertNull(replaced.send)
        assertEquals(11_000L, replaced.flushAtMs)

        val early = throttle.flush(10_999)
        assertNull(early.send)
        assertEquals(11_000L, early.flushAtMs)

        val sent = throttle.flush(11_000)
        assertEquals("C", JSONObject(sent.send!!).getString("title"))
        assertNull(sent.flushAtMs)

        val again = throttle.flush(12_000)
        assertNull(again.send)
        assertNull(again.flushAtMs)
    }

    @Test
    fun naturalProgressIsNotAReportAndASeekIs() {
        val throttle = MediaThrottle()
        assertNotNullSend(throttle.onUpdate(snap(playing = true, positionMs = 0, speed = 1f), 0))

        val tick = throttle.onUpdate(snap(playing = true, positionMs = 400, speed = 1f), 400)
        assertNull(tick.send)
        assertNull(tick.flushAtMs)

        val seek = throttle.onUpdate(snap(playing = true, positionMs = 20_000, speed = 1f), 500)
        assertNull(seek.send)
        assertEquals(1_000L, seek.flushAtMs)
        val flushed = throttle.flush(1_000)
        assertEquals(20_500L, JSONObject(flushed.send!!).getLong("position_ms"))
        assertTrue(JSONObject(flushed.send!!).getBoolean("playing"))
    }

    @Test
    fun flushAdvancesHeldPositionWhilePlaying() {
        val throttle = MediaThrottle()
        throttle.onUpdate(snap(playing = true, title = "Old", positionMs = 0, speed = 1f), 0)
        val held = throttle.onUpdate(
            snap(playing = true, title = "Next", positionMs = 0, speed = 1f),
            100,
        )
        assertNull(held.send)
        val sent = throttle.flush(1_000)
        val json = JSONObject(sent.send!!)
        assertEquals("Next", json.getString("title"))
        assertEquals(900L, json.getLong("position_ms"))
    }

    @Test
    fun stoppedIsSentWhenTheSessionGoesAwayAndNotRepeated() {
        val throttle = MediaThrottle()
        throttle.onUpdate(snap(title = "A"), 0)
        val tooSoon = throttle.onUpdate(null, 200)
        assertNull(tooSoon.send)
        assertEquals(1_000L, tooSoon.flushAtMs)
        val stopped = throttle.flush(1_000)
        val json = JSONObject(stopped.send!!)
        assertEquals("media_stopped", json.getString("type"))
        assertEquals(1, json.length())

        val again = throttle.onUpdate(null, 2_500)
        assertNull(again.send)
        assertNull(again.flushAtMs)
    }

    @Test
    fun reconnectSendsTheCurrentStateAgain() {
        val throttle = MediaThrottle()
        val state = snap(title = "Same", playing = false, positionMs = 10)
        throttle.onUpdate(state, 0)
        val quiet = throttle.onUpdate(state.copy(), 5_000)
        assertNull(quiet.send)

        throttle.reset()
        val again = throttle.onUpdate(state, 5_100)
        assertEquals("Same", JSONObject(again.send!!).getString("title"))
        val stopped = throttle.onUpdate(null, 6_100)
        assertEquals("media_stopped", JSONObject(stopped.send!!).getString("type"))
    }

    @Test
    fun revertingInsideTheWindowDropsTheHeldFrame() {
        val throttle = MediaThrottle()
        val original = snap(title = "A", playing = false, positionMs = 0)
        throttle.onUpdate(original, 0)
        assertNull(throttle.onUpdate(snap(title = "B"), 100).send)
        val reverted = throttle.onUpdate(original.copy(positionMs = 100), 200)
        assertNull(reverted.send)
        assertNull(reverted.flushAtMs)
        assertNull(throttle.flush(1_000).send)
    }

    @Test
    fun artworkIdentityIsAChangeAndAReencodeIsNot() {
        val throttle = MediaThrottle()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)
        throttle.onUpdate(snap(artToken = 0), 0)
        val art = throttle.onUpdate(snap(artJpeg = jpeg, artToken = 7), 1_000)
        assertEquals(jpeg.toList(), Base64.getDecoder().decode(JSONObject(art.send!!).getString("art")).toList())
        val samePixels = throttle.onUpdate(snap(artJpeg = jpeg.copyOf(), artToken = 7, positionMs = 50), 3_000)
        assertNull(samePixels.send)
        val newArt = throttle.onUpdate(snap(artJpeg = jpeg, artToken = 8), 4_000)
        assertNotNull(newArt.send)
    }

    @Test
    fun aSpeedChangeIsReported() {
        val throttle = MediaThrottle()
        throttle.onUpdate(snap(playing = true, positionMs = 0, speed = 1f), 0)
        val faster = throttle.onUpdate(snap(playing = true, positionMs = 2_000, speed = 2f), 1_000)
        assertNotNull(faster.send)
        assertEquals(2_000L, JSONObject(faster.send!!).getLong("position_ms"))
    }

    @Test
    fun notificationListenerSettingMustListOurComponent() {
        val me = "dev.johnai.seam/dev.johnai.seam.SeamNotificationListener"
        assertFalse(Media.notificationListenerEnabled(null, me))
        assertFalse(Media.notificationListenerEnabled("", me))
        assertFalse(Media.notificationListenerEnabled(me, ""))
        assertTrue(Media.notificationListenerEnabled(me, me))
        assertTrue(Media.notificationListenerEnabled("com.other/.Listener:$me", me))
        assertTrue(Media.notificationListenerEnabled("$me:com.other/.Listener", me))
        assertFalse(Media.notificationListenerEnabled("dev.johnai.seam/.SeamNotificationListener", me))
        assertFalse(Media.notificationListenerEnabled("$me ", me))
        assertFalse(Media.notificationListenerEnabled("com.other/.Listener", me))
    }

    private fun assertNotNullSend(outcome: MediaThrottle.Outcome) {
        assertTrue(outcome.send != null)
    }

    private fun keys(json: JSONObject): Set<String> {
        val out = HashSet<String>()
        val it = json.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    private fun snap(
        app: String = "com.example.player",
        appName: String = "Player",
        title: String = "Song",
        artist: String = "Artist",
        album: String = "Album",
        playing: Boolean = false,
        positionMs: Long = 0,
        durationMs: Long = 180_000,
        speed: Float = 1f,
        artJpeg: ByteArray? = null,
        artToken: Int = 0,
    ) = Media.Snapshot(
        app, appName, title, artist, album, playing, positionMs, durationMs, speed, artJpeg, artToken,
    )
}
