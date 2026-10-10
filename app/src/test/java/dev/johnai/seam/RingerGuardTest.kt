package dev.johnai.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Silence must unmute on ended or timeout, and must not mute twice. */
class RingerGuardTest {
    @Test
    fun unmuteOnEnded() {
        var unmutes = 0
        val guard = RingerGuard(clock = { 0L }, mute = {}, unmute = { unmutes++ })
        guard.onEnded()
        assertEquals(0, unmutes)

        assertTrue(guard.silence())
        guard.onLineState(CallLineState.RINGING)
        assertTrue(guard.isMuted())
        assertEquals(0, unmutes)

        guard.onEnded()
        assertEquals(1, unmutes)
        assertFalse(guard.isMuted())
        guard.onEnded()
        guard.onLineState(CallLineState.IDLE)
        guard.onTick()
        guard.release()
        assertEquals(1, unmutes)

        val missed = RingerGuard(clock = { 0L }, mute = {}, unmute = { unmutes++ })
        assertTrue(missed.silence())
        missed.onLineState(CallLineState.IDLE)
        assertFalse(missed.isMuted())

        val answered = RingerGuard(clock = { 0L }, mute = {}, unmute = { unmutes++ })
        assertTrue(answered.silence())
        answered.onLineState(CallLineState.OFFHOOK)
        assertFalse(answered.isMuted())
        assertEquals(3, unmutes)
    }

    @Test
    fun unmuteOnTimeout() {
        assertEquals(60_000L, RingerGuard.TIMEOUT_MS)
        var now = 1_000L
        var unmutes = 0
        val guard = RingerGuard(clock = { now }, mute = {}, unmute = { unmutes++ })
        assertTrue(guard.silence())
        now = 1_000L + RingerGuard.TIMEOUT_MS - 1
        guard.onTick()
        guard.onLineState(CallLineState.RINGING)
        assertEquals(0, unmutes)
        assertTrue(guard.isMuted())
        now = 1_000L + RingerGuard.TIMEOUT_MS
        guard.onTick()
        assertEquals(1, unmutes)
        assertFalse(guard.isMuted())
        guard.onTick()
        guard.onEnded()
        assertEquals(1, unmutes)
    }

    @Test
    fun noDoubleMute() {
        var now = 0L
        var mutes = 0
        var unmutes = 0
        val guard = RingerGuard(
            clock = { now },
            mute = { mutes++ },
            unmute = { unmutes++ },
        )
        assertTrue(guard.silence())
        now = 5_000L
        assertFalse(guard.silence())
        assertEquals(1, mutes)
        assertEquals(0, unmutes)
        assertTrue(guard.isMuted())
        // The ignored silence must not push the original deadline out.
        now = RingerGuard.TIMEOUT_MS
        guard.onTick()
        assertEquals(1, unmutes)
        assertFalse(guard.isMuted())
        // A later call can be silenced again, still only once.
        assertTrue(guard.silence())
        assertFalse(guard.silence())
        assertEquals(2, mutes)
    }

    @Test
    fun muteFailureIsIgnored() {
        var unmutes = 0
        val guard = RingerGuard(
            clock = { 0L },
            mute = { throw IllegalStateException("no audio") },
            unmute = { unmutes++ },
        )
        assertFalse(guard.silence())
        assertFalse(guard.isMuted())
        guard.onEnded()
        guard.onTick()
        guard.release()
        assertEquals(0, unmutes)
    }
}
