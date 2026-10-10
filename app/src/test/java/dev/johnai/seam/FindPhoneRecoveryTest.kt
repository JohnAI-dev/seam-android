package dev.johnai.seam

import org.junit.Assert.assertEquals
import org.junit.Test

/** Volume must not stay changed past a find-my-phone ring, including across process death. */
class FindPhoneRecoveryTest {
    private val ring = Protocol.RING_DURATION_MS

    @Test
    fun nothingToRestoreWhileIdleOrAlreadyRinging() {
        assertEquals(FindPhoneRecovery.NONE, findPhoneRecovery(false, 1_000L, 2_000L, ring, false))
        assertEquals(FindPhoneRecovery.NONE, findPhoneRecovery(true, 1_000L, 2_000L, ring, true))
        assertEquals(FindPhoneRecovery.NONE, findPhoneRecovery(true, 5_000L, 0L, ring, true))
    }

    @Test
    fun expiredOrMissingDeadlineRestores() {
        assertEquals(FindPhoneRecovery.RESTORE, findPhoneRecovery(true, 5_000L, 5_000L, ring))
        assertEquals(FindPhoneRecovery.RESTORE, findPhoneRecovery(true, 5_000L, 4_999L, ring))
        assertEquals(FindPhoneRecovery.RESTORE, findPhoneRecovery(true, 5_000L, 0L, ring))
    }

    @Test
    fun insideTheRingMayResume() {
        assertEquals(FindPhoneRecovery.RESUME, findPhoneRecovery(true, 1_000L, 1_000L + ring, ring))
        assertEquals(FindPhoneRecovery.RESUME, findPhoneRecovery(true, 1_000L, 1_001L, ring))
    }

    @Test
    fun deadlineBeyondOneRingRestores() {
        assertEquals(FindPhoneRecovery.RESTORE, findPhoneRecovery(true, 1_000L, 1_000L + ring + 1, ring))
    }
}
