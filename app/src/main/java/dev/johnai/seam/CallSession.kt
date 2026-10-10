package dev.johnai.seam

/**
 * Which call frames to send (protocol/PROTOCOL.md, "Calls"). No Android types, so the
 * transitions run in JVM unit tests.
 *
 * A new ringing always starts a fresh call: its number and name are exactly what this
 * event provides, never leftovers from an earlier call. OFFHOOK is active and keeps the
 * current number and name when the event does not include a number. IDLE after ringing
 * or active is ended; IDLE while already idle is not reported.
 */
class CallSession {
    private var phase = Phase.IDLE
    private var number = ""
    private var name = ""

    private enum class Phase { IDLE, RINGING, ACTIVE }

    /**
     * @param number phone number from the listener; empty if not given
     * @param name contact name; empty if unknown. A new ringing uses this even when blank.
     * @return fields for one call frame, or null if this was not a change
     */
    fun onState(state: CallLineState, number: String = "", name: String = ""): CallUpdate? {
        val n = number.trim()
        val who = name.trim()
        return when (state) {
            CallLineState.RINGING -> onRinging(n, who)
            CallLineState.OFFHOOK -> onOffHook(n, who)
            CallLineState.IDLE -> onIdle()
        }
    }

    private fun onRinging(number: String, name: String): CallUpdate? {
        // A repeat while still ringing, with no number, is not a new call.
        if (phase == Phase.RINGING && number.isEmpty()) return null
        if (phase == Phase.RINGING && number == this.number && name == this.name) return null
        phase = Phase.RINGING
        this.number = number
        this.name = name
        return CallUpdate(RINGING, this.number, this.name)
    }

    private fun onOffHook(number: String, name: String): CallUpdate? {
        var changed = phase != Phase.ACTIVE
        if (number.isNotEmpty()) {
            if (number != this.number) {
                this.number = number
                this.name = name
                changed = true
            } else if (name.isNotEmpty() && name != this.name) {
                this.name = name
                changed = true
            }
        }
        phase = Phase.ACTIVE
        return if (changed) CallUpdate(ACTIVE, this.number, this.name) else null
    }

    private fun onIdle(): CallUpdate? {
        if (phase == Phase.IDLE) return null
        val update = CallUpdate(ENDED, number, name)
        phase = Phase.IDLE
        number = ""
        name = ""
        return update
    }

    private companion object {
        const val RINGING = "ringing"
        const val ACTIVE = "active"
        const val ENDED = "ended"
    }
}

/** Telephony line state, without Android types. */
enum class CallLineState { IDLE, RINGING, OFFHOOK }

/** One call frame. [state] is ringing, active, or ended. */
data class CallUpdate(val state: String, val number: String, val name: String)

/** One observed line state. [number] is empty when the source did not provide one. */
data class CallSnapshot(val state: CallLineState, val number: String)

/**
 * Merge the startup snapshot. [TelephonyManager.getCallState] has no number; the listener
 * may report the same state with a number, and either observation can be recorded first.
 * Same-state events collapse to one, preferring a non-empty number, so the blank frame
 * is not sent. A different state is a real change and is kept, in arrival order.
 */
fun coalesceCallSnapshots(events: List<CallSnapshot>): List<CallSnapshot> {
    val out = ArrayList<CallSnapshot>(events.size)
    for (raw in events) {
        val event = raw.copy(number = raw.number.trim())
        val lastIndex = out.lastIndex
        if (lastIndex >= 0 && out[lastIndex].state == event.state) {
            val last = out[lastIndex]
            if (event.number.isNotEmpty() && event.number != last.number) {
                out[lastIndex] = event
            }
            continue
        }
        out.add(event)
    }
    return out
}

/**
 * Holds the immediate line read and the listener's startup callback until [finish].
 * Nothing is emitted before that, so a blank number cannot be sent ahead of the number.
 * After [finish], later observations pass through immediately.
 */
class CallStartupGate {
    private val lock = Any()
    private val pending = ArrayList<CallSnapshot>()
    private var open = true

    fun observe(state: CallLineState, number: String): List<CallSnapshot> {
        val event = CallSnapshot(state, number.trim())
        return synchronized(lock) {
            if (!open) {
                listOf(event)
            } else {
                pending.add(event)
                emptyList()
            }
        }
    }

    fun finish(): List<CallSnapshot> = synchronized(lock) {
        if (!open) {
            emptyList()
        } else {
            open = false
            val merged = coalesceCallSnapshots(pending.toList())
            pending.clear()
            merged
        }
    }
}
