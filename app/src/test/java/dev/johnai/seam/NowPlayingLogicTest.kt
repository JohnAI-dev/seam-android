package dev.johnai.seam

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure now-playing decisions: opt-in, welcome resend, controls, and malformed frames.
 * See protocol/PROTOCOL.md, "Media".
 */
class NowPlayingLogicTest {
    @Test
    fun sharingIsOffUntilTurnedOnAndNeedsTheListener() {
        assertFalse(MediaShare.enabled(null))
        assertFalse(MediaShare.enabled(false))
        assertTrue(MediaShare.enabled(true))
        assertFalse(MediaShare.shouldWatch(optedIn = false, listenerEnabled = true))
        assertFalse(MediaShare.shouldWatch(optedIn = true, listenerEnabled = false))
        assertTrue(MediaShare.shouldWatch(optedIn = true, listenerEnabled = true))
    }

    @Test
    fun notificationAccessAloneDoesNotPublish() {
        val logic = NowPlayingLogic()
        logic.setAccess(optedIn = false, listenerEnabled = true, linkUp = true, nowMs = 0)
        assertFalse(logic.shouldWatch())
        assertNull(logic.onSessions(snap(playing = true, title = "Hidden"), linkUp = true, nowMs = 0).send)
        assertNull(logic.followedSession())
        assertNull(logic.onControl("pause"))
        assertNull(logic.onControl("volume_up"))
        assertNull(logic.onControl("volume_down"))
    }

    @Test
    fun turningSharingOffClearsTheSessionAndTellsTheComputer() {
        val logic = watching()
        val sent = logic.onSessions(snap(playing = true, title = "River"), linkUp = true, nowMs = 0)
        assertEquals("River", JSONObject(sent.send!!).getString("title"))
        val stopped = logic.setAccess(optedIn = false, listenerEnabled = true, linkUp = true, nowMs = 2_000)
        assertEquals("media_stopped", JSONObject(stopped.send!!).getString("type"))
        assertNull(logic.followedSession())
        assertNull(logic.onControl("next"))
        assertNull(logic.onSessions(snap(title = "After"), linkUp = true, nowMs = 3_000).send)
    }

    @Test
    fun turningSharingOffWhileDownDoesNotQueueAFrame() {
        val logic = watching()
        logic.onSessions(snap(playing = true), linkUp = true, nowMs = 0)
        val outcome = logic.setAccess(optedIn = false, listenerEnabled = true, linkUp = false, nowMs = 2_000)
        assertNull(outcome.send)
        assertNull(logic.followedSession())
    }

    @Test
    fun welcomeResendsTheCurrentSession() {
        val logic = watching()
        val first = logic.onSessions(snap(playing = true, title = "River", positionMs = 1_000), linkUp = true, nowMs = 0)
        assertEquals("media", JSONObject(first.send!!).getString("type"))
        val welcome = logic.onWelcome(linkUp = true, nowMs = 30_000)
        assertNull(welcome.send)
        assertEquals("River", logic.followedSession()?.title)
        val again = logic.onSessions(logic.followedSession(), linkUp = true, nowMs = 30_000)
        val json = JSONObject(again.send!!)
        assertEquals("media", json.getString("type"))
        assertEquals("River", json.getString("title"))
        assertTrue(json.getBoolean("playing"))
        assertTrue(json.has("position_ms"))
        assertTrue(json.has("duration_ms"))
    }

    @Test
    fun welcomeWhileNotSharingSendsStopped() {
        val logic = NowPlayingLogic()
        logic.setAccess(optedIn = false, listenerEnabled = true, linkUp = false, nowMs = 0)
        val welcome = logic.onWelcome(linkUp = true, nowMs = 5_000)
        assertEquals("media_stopped", JSONObject(welcome.send!!).getString("type"))
        assertNull(logic.followedSession())
    }

    @Test
    fun disconnectKeepsTheLocalSessionUntilTheNextWelcomeResendsIt() {
        val logic = watching()
        val track = snap(playing = true, title = "River", positionMs = 4_000)
        logic.onSessions(track, linkUp = true, nowMs = 0)
        assertSame(track, logic.followedSession())
        val welcome = logic.onWelcome(linkUp = true, nowMs = 60_000)
        assertNull(welcome.send)
        assertSame(track, logic.followedSession())
        val resent = logic.onSessions(track, linkUp = true, nowMs = 60_000)
        assertEquals("River", JSONObject(resent.send!!).getString("title"))
    }

    @Test
    fun aControlDoesNotChangeTheStoredSession() {
        val logic = watching()
        val track = snap(playing = true, title = "River", positionMs = 8_000)
        logic.onSessions(track, linkUp = true, nowMs = 0)
        assertEquals(Media.Command.PAUSE, logic.onControl("pause"))
        assertEquals(Media.Command.NEXT, logic.onControl("next"))
        assertEquals(Media.Command.VOLUME_UP, logic.onControl("volume_up"))
        assertSame(track, logic.followedSession())
        assertTrue(logic.followedSession()!!.playing)
        assertEquals(8_000L, logic.followedSession()!!.positionMs)
        assertNull(logic.flush(1_000).send)
    }

    @Test
    fun toggleUsesThePlayingFlagItIsGiven() {
        val logic = watching()
        logic.onSessions(snap(playing = true), linkUp = true, nowMs = 0)
        assertEquals(Media.Command.PAUSE, logic.onControl("toggle"))
        assertEquals(Media.Command.PLAY, logic.onControl("toggle", playing = false))
        assertTrue(logic.followedSession()!!.playing)
    }

    @Test
    fun malformedAndUnknownControlsAreIgnored() {
        val logic = watching()
        val track = snap(playing = true, title = "River")
        logic.onSessions(track, linkUp = true, nowMs = 0)
        assertNull(logic.onControlFrame("""{"type":"media_control"}"""))
        assertNull(logic.onControlFrame("""{"type":"media_control","action":null}"""))
        assertNull(logic.onControlFrame("""{"type":"media_control","action":"seek"}"""))
        assertNull(logic.onControlFrame("""{"type":"media_control","action":1}"""))
        assertNull(logic.onControlFrame("not json"))
        assertNull(logic.onControlFrame("""{"type":"media","title":"no playing"}"""))
        assertNull(logic.onControl(null))
        assertSame(track, logic.followedSession())
    }

    @Test
    fun pausedSessionIsKeptAndStoppedIsOnlyForNothingPlaying() {
        val logic = watching()
        val paused = logic.onSessions(snap(playing = false, positionMs = 40, durationMs = 100), linkUp = true, nowMs = 0)
        val json = JSONObject(paused.send!!)
        assertEquals("media", json.getString("type"))
        assertFalse(json.getBoolean("playing"))
        assertEquals(40L, json.getLong("position_ms"))
        val stopped = logic.onSessions(null, linkUp = true, nowMs = 2_000)
        assertEquals("media_stopped", JSONObject(stopped.send!!).getString("type"))
        assertNull(logic.followedSession())
    }

    @Test
    fun badArtIsDroppedAndTheSessionIsKept() {
        val logic = watching()
        val track = snap(playing = true, title = "River", artJpeg = byteArrayOf(1, 2, 3))
        val sent = logic.onSessions(track, linkUp = true, nowMs = 0)
        val json = JSONObject(sent.send!!)
        assertFalse(json.has("art"))
        assertTrue(json.getBoolean("playing"))
        assertEquals("River", json.getString("title"))
        assertSame(track, logic.followedSession())
    }

    @Test
    fun jpegArtIsStandardBase64() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val logic = watching()
        val sent = logic.onSessions(snap(playing = true, artJpeg = jpeg), linkUp = true, nowMs = 0)
        val art = JSONObject(sent.send!!).getString("art")
        assertFalse(art.contains("\n"))
        assertEquals(jpeg.toList(), java.util.Base64.getDecoder().decode(art).toList())
    }

    @Test
    fun omittedClockFieldsAreSentAsZeroAndPlayingIsRequired() {
        val logic = watching()
        val sent = logic.onSessions(
            snap(playing = true, positionMs = -5, durationMs = -1, title = ""),
            linkUp = true,
            nowMs = 0,
        )
        val json = JSONObject(sent.send!!)
        assertTrue(json.has("playing"))
        assertTrue(json.getBoolean("playing"))
        assertEquals(0L, json.getLong("position_ms"))
        assertEquals(0L, json.getLong("duration_ms"))
    }

    @Test
    fun naturalProgressIsNotResentBecauseTheDesktopExtrapolates() {
        val logic = watching()
        logic.onSessions(snap(playing = true, positionMs = 1_000, speed = 1f), linkUp = true, nowMs = 1_000)
        val later = logic.onSessions(
            snap(playing = true, positionMs = 1_400, speed = 1f),
            linkUp = true,
            nowMs = 1_400,
        )
        assertNull(later.send)
        assertNull(later.flushAtMs)
    }

    @Test
    fun sessionUpdatesWhileDisconnectedAreNotQueued() {
        val logic = watching()
        val outcome = logic.onSessions(snap(playing = true, title = "River"), linkUp = false, nowMs = 0)
        assertNull(outcome.send)
        assertEquals("River", logic.followedSession()?.title)
        val welcome = logic.onWelcome(linkUp = true, nowMs = 10_000)
        assertNull(welcome.send)
        val sent = logic.onSessions(logic.followedSession(), linkUp = true, nowMs = 10_000)
        assertEquals("River", JSONObject(sent.send!!).getString("title"))
    }

    private fun watching(): NowPlayingLogic {
        val logic = NowPlayingLogic()
        logic.setAccess(optedIn = true, listenerEnabled = true, linkUp = true, nowMs = 0)
        assertTrue(logic.shouldWatch())
        return logic
    }

    private fun snap(
        title: String = "Song",
        playing: Boolean = false,
        positionMs: Long = 0,
        durationMs: Long = 180_000,
        speed: Float = 1f,
        artJpeg: ByteArray? = null,
    ) = Media.Snapshot(
        app = "com.example.player",
        appName = "Player",
        title = title,
        artist = "Artist",
        album = "Album",
        playing = playing,
        positionMs = positionMs,
        durationMs = durationMs,
        speed = speed,
        artJpeg = artJpeg,
    )
}
