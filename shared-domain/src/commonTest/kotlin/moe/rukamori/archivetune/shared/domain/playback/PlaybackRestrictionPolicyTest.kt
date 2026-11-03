package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every denied action must carry the reason a surface can show — no silent dead ends. */
class PlaybackRestrictionPolicyTest {
    @Test
    fun nothingLoadedDeniesEverything() {
        val restrictions =
            PlaybackRestrictionPolicy.resolve(
                hasCurrentItem = false,
                hasSuccessor = false,
                atQueueStart = true,
                canRestartCurrentItem = false,
                currentItemSeekable = false,
            )
        assertEquals(PlaybackRestrictionReason.NOTHING_LOADED, restrictions.next.reason)
        assertEquals(PlaybackRestrictionReason.NOTHING_LOADED, restrictions.previous.reason)
        assertEquals(PlaybackRestrictionReason.NOTHING_LOADED, restrictions.seekWithin.reason)
        assertFalse(restrictions.next.allowed)
    }

    @Test
    fun theQueueEdgeReasonsDistinguishStartAndEnd() {
        val restrictions =
            PlaybackRestrictionPolicy.resolve(
                hasCurrentItem = true,
                hasSuccessor = false,
                atQueueStart = true,
                canRestartCurrentItem = false,
                currentItemSeekable = true,
            )
        assertEquals(PlaybackRestrictionReason.END_OF_QUEUE, restrictions.next.reason)
        assertEquals(PlaybackRestrictionReason.START_OF_QUEUE, restrictions.previous.reason)
        assertTrue(restrictions.seekWithin.allowed)
    }

    @Test
    fun aRestartableCurrentItemKeepsPreviousAvailable() {
        val restrictions =
            PlaybackRestrictionPolicy.resolve(
                hasCurrentItem = true,
                hasSuccessor = true,
                atQueueStart = true,
                canRestartCurrentItem = true,
                currentItemSeekable = true,
            )
        assertTrue(restrictions.previous.allowed)
        assertTrue(restrictions.next.allowed)
    }

    @Test
    fun anUnseekableItemCarriesTheReason() {
        val restrictions =
            PlaybackRestrictionPolicy.resolve(
                hasCurrentItem = true,
                hasSuccessor = true,
                atQueueStart = false,
                canRestartCurrentItem = false,
                currentItemSeekable = false,
            )
        assertFalse(restrictions.seekWithin.allowed)
        assertEquals(PlaybackRestrictionReason.NOT_SEEKABLE, restrictions.seekWithin.reason)
        assertTrue(restrictions.next.allowed)
        assertTrue(restrictions.previous.allowed)
    }

    @Test
    fun allowedActionsCarryNoReason() {
        val restrictions =
            PlaybackRestrictionPolicy.resolve(
                hasCurrentItem = true,
                hasSuccessor = true,
                atQueueStart = false,
                canRestartCurrentItem = false,
                currentItemSeekable = true,
            )
        assertTrue(restrictions.next.allowed)
        assertEquals(PlaybackRestrictionReason.NONE, restrictions.next.reason)
    }
}
