package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The automotive queue ids stay stable enough for a car to cache: a tap resolves only while the
 * stamped position still holds the song, so a moved duplicate never selects another occurrence.
 */
class AutoQueueMediaIdsTest {
    @Test
    fun mediaIdFor_buildsSlashPath() {
        assertEquals("queue/3/abc123", AutoQueueMediaIds.mediaIdFor(3, "abc123"))
    }

    @Test
    fun parse_roundTrips() {
        val entry = AutoQueueMediaIds.parse("queue/7/xyz")
        assertEquals(7, entry?.position)
        assertEquals("xyz", entry?.songMediaId)
    }

    @Test
    fun parse_keepsSlashesInsideSongId() {
        val entry = AutoQueueMediaIds.parse("queue/0/video:id/42")
        assertEquals(0, entry?.position)
        assertEquals("video:id/42", entry?.songMediaId)
    }

    @Test
    fun parse_rejectsMalformedIds() {
        assertNull(AutoQueueMediaIds.parse("queue"))
        assertNull(AutoQueueMediaIds.parse("queue/"))
        assertNull(AutoQueueMediaIds.parse("queue/abc/song"))
        assertNull(AutoQueueMediaIds.parse("queue/-1/song"))
        assertNull(AutoQueueMediaIds.parse("queue/1/"))
        assertNull(AutoQueueMediaIds.parse("playlist/1"))
        assertNull(AutoQueueMediaIds.parse("queuexyz/1/song"))
    }

    @Test
    fun resolvePosition_prefersStampedPosition() {
        val entry = AutoQueueMediaIds.QueueEntry(position = 2, songMediaId = "s")
        assertEquals(2, AutoQueueMediaIds.resolvePosition(entry, listOf("a", "b", "s", "s")))
    }

    @Test
    fun resolvePosition_rejectsAMovedEntryInsteadOfRemappingIt() {
        val entry = AutoQueueMediaIds.QueueEntry(position = 2, songMediaId = "s")
        assertNull(AutoQueueMediaIds.resolvePosition(entry, listOf("s", "b", "other")))
    }

    @Test
    fun resolvePosition_aTapFilteredOutOfItsStampedPositionNeverSelectsAnEarlierDuplicate() {
        val live = listOf("online", "A", "partial", "A")
        val filtered = live.filter { it == "A" }
        val staleTap = AutoQueueMediaIds.parse(AutoQueueMediaIds.mediaIdFor(3, "A"))!!
        assertNull(AutoQueueMediaIds.resolvePosition(staleTap, filtered))
        assertEquals(3, AutoQueueMediaIds.resolvePosition(staleTap, live))
    }

    @Test
    fun resolvePosition_returnsNullWhenSongGone() {
        val entry = AutoQueueMediaIds.QueueEntry(position = 1, songMediaId = "gone")
        assertNull(AutoQueueMediaIds.resolvePosition(entry, listOf("a", "b")))
        assertNull(AutoQueueMediaIds.resolvePosition(entry, emptyList()))
    }
}
