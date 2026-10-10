package dev.johnai.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Call frames sent to the computer. See protocol/PROTOCOL.md, "Calls". */
class CallSessionTest {
    @Test
    fun ringingThenActiveThenEnded() {
        val session = CallSession()
        assertEquals(
            CallUpdate("ringing", "555", "Ada"),
            session.onState(CallLineState.RINGING, "555", "Ada"),
        )
        assertNull(session.onState(CallLineState.RINGING, "555", "Ada"))
        assertEquals(
            CallUpdate("active", "555", "Ada"),
            session.onState(CallLineState.OFFHOOK),
        )
        assertNull(session.onState(CallLineState.OFFHOOK))
        assertEquals(
            CallUpdate("ended", "555", "Ada"),
            session.onState(CallLineState.IDLE),
        )
        assertNull(session.onState(CallLineState.IDLE))
    }

    @Test
    fun missedCall() {
        val session = CallSession()
        assertEquals(
            CallUpdate("ringing", "555", "Ada"),
            session.onState(CallLineState.RINGING, "555", "Ada"),
        )
        assertEquals(
            CallUpdate("ended", "555", "Ada"),
            session.onState(CallLineState.IDLE),
        )
        assertNull(session.onState(CallLineState.IDLE))
    }

    @Test
    fun endedThenRingingWithBlankNumberDropsOldCaller() {
        val session = CallSession()
        session.onState(CallLineState.RINGING, "+15551212", "Ada Lovelace")
        assertEquals(
            CallUpdate("ended", "+15551212", "Ada Lovelace"),
            session.onState(CallLineState.IDLE),
        )
        assertEquals(
            CallUpdate("ringing", "", ""),
            session.onState(CallLineState.RINGING, "", ""),
        )
        assertEquals(CallUpdate("active", "", ""), session.onState(CallLineState.OFFHOOK))
        assertEquals(CallUpdate("ended", "", ""), session.onState(CallLineState.IDLE))
    }

    @Test
    fun newRingingDoesNotKeepThePreviousName() {
        val session = CallSession()
        session.onState(CallLineState.RINGING, "555", "Ada")
        session.onState(CallLineState.IDLE)
        assertEquals(
            CallUpdate("ringing", "777", ""),
            session.onState(CallLineState.RINGING, "777", ""),
        )
    }

    @Test
    fun callWaitingReportsTheNewCaller() {
        val session = CallSession()
        session.onState(CallLineState.RINGING, "555", "Ada")
        session.onState(CallLineState.OFFHOOK)
        assertEquals(
            CallUpdate("ringing", "666", "Bob"),
            session.onState(CallLineState.RINGING, "666", "Bob"),
        )
        assertEquals(CallUpdate("active", "666", "Bob"), session.onState(CallLineState.OFFHOOK))
    }

    @Test
    fun callWaitingWithBlankNumberDoesNotKeepThePreviousCaller() {
        val session = CallSession()
        session.onState(CallLineState.RINGING, "555", "Ada")
        session.onState(CallLineState.OFFHOOK)
        assertEquals(
            CallUpdate("ringing", "", ""),
            session.onState(CallLineState.RINGING, "", ""),
        )
    }

    @Test
    fun callWaitingWithBlankNameDoesNotKeepThePreviousName() {
        val session = CallSession()
        session.onState(CallLineState.RINGING, "555", "Ada")
        session.onState(CallLineState.OFFHOOK)
        assertEquals(
            CallUpdate("ringing", "666", ""),
            session.onState(CallLineState.RINGING, "666", ""),
        )
    }

    @Test
    fun idleWhileAlreadyIdleIsNotEnded() {
        val session = CallSession()
        assertNull(session.onState(CallLineState.IDLE))
        assertNull(session.onState(CallLineState.IDLE, "555", "Ada"))
        assertEquals(CallUpdate("ringing", "", ""), session.onState(CallLineState.RINGING))
    }

    @Test
    fun offHookIsActiveEvenWithoutRinging() {
        val session = CallSession()
        assertEquals(CallUpdate("active", "", ""), session.onState(CallLineState.OFFHOOK))
        assertEquals(CallUpdate("ended", "", ""), session.onState(CallLineState.IDLE))
    }

    @Test
    fun numberArrivingWhileActiveIsReported() {
        val session = CallSession()
        assertEquals(CallUpdate("active", "", ""), session.onState(CallLineState.OFFHOOK))
        assertEquals(
            CallUpdate("active", "555", "Ada"),
            session.onState(CallLineState.OFFHOOK, "555", "Ada"),
        )
        assertNull(session.onState(CallLineState.OFFHOOK, "555", "Ada"))
    }

    @Test
    fun startupRingingPrefersNumberInEitherOrder() {
        assertEquals(
            listOf(CallUpdate("ringing", "555", "Ada")),
            framesAfterStartup(
                listOf(
                    CallSnapshot(CallLineState.RINGING, ""),
                    CallSnapshot(CallLineState.RINGING, "555"),
                ),
                "Ada",
            ),
        )
        assertEquals(
            listOf(CallUpdate("ringing", "555", "Ada")),
            framesAfterStartup(
                listOf(
                    CallSnapshot(CallLineState.RINGING, "555"),
                    CallSnapshot(CallLineState.RINGING, ""),
                ),
                "Ada",
            ),
        )
    }

    @Test
    fun startupActivePrefersNumberInEitherOrder() {
        assertEquals(
            listOf(CallUpdate("active", "555", "")),
            framesAfterStartup(
                listOf(
                    CallSnapshot(CallLineState.OFFHOOK, ""),
                    CallSnapshot(CallLineState.OFFHOOK, "555"),
                ),
            ),
        )
        assertEquals(
            listOf(CallUpdate("active", "555", "")),
            framesAfterStartup(
                listOf(
                    CallSnapshot(CallLineState.OFFHOOK, "555"),
                    CallSnapshot(CallLineState.OFFHOOK, ""),
                ),
            ),
        )
    }

    @Test
    fun startupBlankRingingIsNotLeftUnended() {
        val gate = CallStartupGate()
        assertTrue(gate.observe(CallLineState.RINGING, "").isEmpty())
        assertTrue(gate.observe(CallLineState.RINGING, "555").isEmpty())
        val session = CallSession()
        val frames = gate.finish().mapNotNull { session.onState(it.state, it.number, "Ada") }
        assertEquals(listOf(CallUpdate("ringing", "555", "Ada")), frames)
        assertEquals(CallUpdate("ended", "555", "Ada"), session.onState(CallLineState.IDLE))
    }

    @Test
    fun startupWithoutNumberStillReportsTheCurrentCall() {
        assertEquals(
            listOf(CallUpdate("ringing", "", "")),
            framesAfterStartup(listOf(CallSnapshot(CallLineState.RINGING, ""))),
        )
        assertEquals(
            listOf(CallUpdate("active", "", "")),
            framesAfterStartup(listOf(CallSnapshot(CallLineState.OFFHOOK, "   "))),
        )
    }

    @Test
    fun startupIdleIsNotACall() {
        val frames = framesAfterStartup(
            listOf(
                CallSnapshot(CallLineState.IDLE, ""),
                CallSnapshot(CallLineState.IDLE, ""),
            ),
        )
        assertTrue(frames.isEmpty())
    }

    @Test
    fun startupKeepsARealTransitionAndDropsTheBlankTwin() {
        assertEquals(
            listOf(
                CallSnapshot(CallLineState.RINGING, "555"),
                CallSnapshot(CallLineState.OFFHOOK, "555"),
            ),
            coalesceCallSnapshots(
                listOf(
                    CallSnapshot(CallLineState.RINGING, ""),
                    CallSnapshot(CallLineState.RINGING, "555"),
                    CallSnapshot(CallLineState.OFFHOOK, ""),
                    CallSnapshot(CallLineState.OFFHOOK, "555"),
                ),
            ),
        )
        // Numbered off-hook arrived first, then the ringing pair. Different states stay
        // in that order; the blank ringing is dropped in favor of the numbered one.
        assertEquals(
            listOf(
                CallUpdate("active", "555", "Ada"),
                CallUpdate("ringing", "555", "Ada"),
            ),
            framesAfterStartup(
                listOf(
                    CallSnapshot(CallLineState.OFFHOOK, "555"),
                    CallSnapshot(CallLineState.RINGING, ""),
                    CallSnapshot(CallLineState.RINGING, "555"),
                ),
                "Ada",
            ),
        )
    }

    @Test
    fun afterStartupLaterEventsPassThrough() {
        val gate = CallStartupGate()
        gate.observe(CallLineState.RINGING, "")
        gate.observe(CallLineState.RINGING, "555")
        val session = CallSession()
        assertEquals(
            listOf(CallUpdate("ringing", "555", "Ada")),
            gate.finish().mapNotNull { session.onState(it.state, it.number, "Ada") },
        )
        assertEquals(
            listOf(CallSnapshot(CallLineState.OFFHOOK, "")),
            gate.observe(CallLineState.OFFHOOK, ""),
        )
        assertEquals(CallUpdate("active", "555", "Ada"), session.onState(CallLineState.OFFHOOK))
        assertEquals(
            listOf(CallSnapshot(CallLineState.RINGING, "666")),
            gate.observe(CallLineState.RINGING, "666"),
        )
        assertEquals(
            CallUpdate("ringing", "666", "Bob"),
            session.onState(CallLineState.RINGING, "666", "Bob"),
        )
    }

    @Test
    fun coalesceTrimsAndPrefersANumber() {
        assertEquals(
            listOf(CallSnapshot(CallLineState.RINGING, "555")),
            coalesceCallSnapshots(
                listOf(
                    CallSnapshot(CallLineState.RINGING, "  "),
                    CallSnapshot(CallLineState.RINGING, "555"),
                ),
            ),
        )
        assertTrue(coalesceCallSnapshots(emptyList()).isEmpty())
    }

    private fun framesAfterStartup(events: List<CallSnapshot>, name: String = ""): List<CallUpdate> {
        val gate = CallStartupGate()
        for (event in events) {
            assertTrue(gate.observe(event.state, event.number).isEmpty())
        }
        val session = CallSession()
        return gate.finish().mapNotNull { snap ->
            val who = if (snap.number.isEmpty()) "" else name
            session.onState(snap.state, snap.number, who)
        }
    }
}
