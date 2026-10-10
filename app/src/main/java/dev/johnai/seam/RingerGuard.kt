package dev.johnai.seam

/**
 * Mutes the ring stream once, then unmutes when the call leaves ringing or after
 * [TIMEOUT_MS], whichever is first. The clock is injected so the timeout is testable
 * without waiting. Not thread-safe; the owner serializes calls.
 *
 * If [mute] throws, the failure is ignored and the stream is not treated as muted,
 * so a later [release] will not unmute a ringer this guard did not mute.
 */
class RingerGuard(
    private val clock: () -> Long,
    private val mute: () -> Unit,
    private val unmute: () -> Unit,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private var muted = false
    private var unmuteAtMs = 0L

    fun isMuted(): Boolean = muted

    /** Milliseconds until the timeout unmute, or 0 if this guard is not silencing. */
    fun remainingMs(now: Long = clock()): Long {
        if (!muted) return 0L
        return (unmuteAtMs - now).coerceAtLeast(0L)
    }

    /**
     * Mute once. Returns false if already muted, or if [mute] throws.
     * A second call does not mute again and does not extend the deadline.
     */
    fun silence(now: Long = clock()): Boolean {
        if (muted) return false
        try {
            mute()
        } catch (_: Exception) {
            return false
        }
        muted = true
        unmuteAtMs = now + timeoutMs
        return true
    }

    /**
     * Update from the phone line. Leaving ringing (answered or ended) unmutes.
     * A still-ringing line unmutes only if the timeout has elapsed.
     */
    fun onLineState(state: CallLineState, now: Long = clock()) {
        if (!muted) return
        if (state == CallLineState.RINGING) {
            onTick(now)
            return
        }
        onEnded()
    }

    /** Unmute because the call left ringing (answered, declined, or missed). */
    fun onEnded() {
        finish()
    }

    /** Unmute if the silence timeout has elapsed. */
    fun onTick(now: Long = clock()) {
        if (!muted || now < unmuteAtMs) return
        finish()
    }

    /** Unmute if silenced. The link service calls this from onDestroy. */
    fun release() {
        finish()
    }

    private fun finish() {
        if (!muted) return
        muted = false
        unmuteAtMs = 0L
        try {
            unmute()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Unmute this long after silence if the call has not left ringing. */
        const val TIMEOUT_MS = 60_000L
    }
}
