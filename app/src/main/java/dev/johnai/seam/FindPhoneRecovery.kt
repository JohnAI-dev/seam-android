package dev.johnai.seam

/** What to do with a persisted alarm volume when this process is not ringing. */
enum class FindPhoneRecovery { NONE, RESTORE, RESUME }

/**
 * A saved alarm volume must be put back once the ring deadline has passed, or if the
 * deadline is missing or further away than one ring (the clock moved). Inside the
 * deadline the ring may resume. An in-progress ring is left alone.
 */
fun findPhoneRecovery(
    volumeSaved: Boolean,
    nowMs: Long,
    deadlineMs: Long,
    ringMs: Long = Protocol.RING_DURATION_MS,
    alreadyRinging: Boolean = false,
): FindPhoneRecovery {
    if (alreadyRinging || !volumeSaved) return FindPhoneRecovery.NONE
    val remaining = deadlineMs - nowMs
    if (remaining <= 0L || remaining > ringMs) return FindPhoneRecovery.RESTORE
    return FindPhoneRecovery.RESUME
}
