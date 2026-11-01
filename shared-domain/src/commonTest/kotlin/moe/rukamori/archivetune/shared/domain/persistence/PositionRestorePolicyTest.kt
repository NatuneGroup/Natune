package moe.rukamori.archivetune.shared.domain.persistence

import kotlin.test.Test
import kotlin.test.assertEquals

class PositionRestorePolicyTest {
    private val now = 1_700_000_000_000L

    @Test
    fun freshStampForTheSameItemRestoresThePosition() {
        assertEquals(
            45_678L,
            PositionRestorePolicy.positionMs(
                savedItemId = "song",
                restoredItemId = "song",
                savedAtEpochMs = now - 1_000L,
                positionMs = 45_678L,
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun positionsOlderThanTheWindowStartOver() {
        assertEquals(
            0L,
            PositionRestorePolicy.positionMs(
                savedItemId = "song",
                restoredItemId = "song",
                savedAtEpochMs = now - PositionRestorePolicy.MAX_SAVED_POSITION_AGE_MS - 1,
                positionMs = 45_678L,
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun theWindowEdgeStillRestores() {
        // Just inside: now - saved < MAX, inclusive lower bound on a zero-age stamp.
        assertEquals(
            45_678L,
            PositionRestorePolicy.positionMs("song", "song", now, 45_678L, now),
        )
        assertEquals(
            45_678L,
            PositionRestorePolicy.positionMs(
                "song", "song", now - PositionRestorePolicy.MAX_SAVED_POSITION_AGE_MS + 1, 45_678L, now,
            ),
        )
        // Exactly at the window end is already too old.
        assertEquals(
            0L,
            PositionRestorePolicy.positionMs(
                "song", "song", now - PositionRestorePolicy.MAX_SAVED_POSITION_AGE_MS, 45_678L, now,
            ),
        )
    }

    @Test
    fun unstampedOrMismatchedCheckpointsDropThePosition() {
        // A checkpoint written before the stamp existed cannot prove it is recent.
        assertEquals(0L, PositionRestorePolicy.positionMs("song", "song", null, 45_678L, now))
        // The position belonged to another item than the one being restored.
        assertEquals(
            0L,
            PositionRestorePolicy.positionMs("other-song", "song", now, 45_678L, now),
        )
        assertEquals(
            0L,
            PositionRestorePolicy.positionMs("song", null, now, 45_678L, now),
        )
        // A saved position of zero is nothing to restore even when everything else lines up.
        assertEquals(0L, PositionRestorePolicy.positionMs("song", "song", now, 0L, now))
    }

    @Test
    fun clockSkewBehindTheStampDropsThePosition() {
        // A stamp in the future reads as tampered time, not freshness.
        assertEquals(
            0L,
            PositionRestorePolicy.positionMs("song", "song", now + 60_000L, 45_678L, now),
        )
    }
}
