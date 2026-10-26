/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Characterizes playlist reorder, remove and undo over occurrences. A song can appear more than once, so identity is the
 * occurrence row, never the song id. Reorder shifts the rows between the two positions the way the playlist DAO's move
 * does, and remove mirrors the app's move-to-end-then-delete. Undo is new: the app has no playlist undo today.
 */
class LibraryPlaylistOccurrenceTest {
    private val duplicatedSong =
        listOf(
            PlaylistOccurrence(id = 1, songId = "A", position = 0),
            PlaylistOccurrence(id = 2, songId = "B", position = 1),
            PlaylistOccurrence(id = 3, songId = "A", position = 2),
        )

    private fun ids(occurrences: List<PlaylistOccurrence>) = occurrences.map { it.id to it.position }

    @Test
    fun movingForwardShiftsTheRowsBetweenAndKeepsDuplicateIdentity() {
        val result = PlaylistOccurrenceEdits.move(duplicatedSong, fromPosition = 0, toPosition = 2)

        assertEquals(listOf(2 to 0, 3 to 1, 1 to 2), ids(result.occurrences))
        assertEquals(listOf("B", "A", "A"), result.occurrences.map { it.songId })
    }

    @Test
    fun movingBackwardShiftsTheRowsBetweenAndKeepsDuplicateIdentity() {
        val result = PlaylistOccurrenceEdits.move(duplicatedSong, fromPosition = 2, toPosition = 0)

        assertEquals(listOf(3 to 0, 1 to 1, 2 to 2), ids(result.occurrences))
    }

    @Test
    fun aMoveToTheSameSlotChangesNothing() {
        val result = PlaylistOccurrenceEdits.move(duplicatedSong, fromPosition = 1, toPosition = 1)

        assertEquals(duplicatedSong, result.occurrences)
    }

    @Test
    fun undoingAMoveRestoresTheOriginalOrder() {
        val moved = PlaylistOccurrenceEdits.move(duplicatedSong, fromPosition = 0, toPosition = 2)

        val restored = PlaylistOccurrenceEdits.undo(moved.occurrences, moved.undo)

        assertEquals(duplicatedSong, restored)
    }

    @Test
    fun removingADuplicateTakesOnlyThatOccurrence() {
        val result = PlaylistOccurrenceEdits.remove(duplicatedSong, position = 2)!!

        assertEquals(listOf(1 to 0, 2 to 1), ids(result.occurrences))
        assertEquals(3, (result.undo as PlaylistEditUndo.Reinsert).occurrence.id)
    }

    @Test
    fun removingAnEarlierRowShiftsTheLaterRowsUp() {
        val result = PlaylistOccurrenceEdits.remove(duplicatedSong, position = 0)!!

        assertEquals(listOf(2 to 0, 3 to 1), ids(result.occurrences))
    }

    @Test
    fun undoingARemovalPutsBackTheSameOccurrenceAtItsPosition() {
        val removed = PlaylistOccurrenceEdits.remove(duplicatedSong, position = 2)!!

        val restored = PlaylistOccurrenceEdits.undo(removed.occurrences, removed.undo)

        assertEquals(duplicatedSong, restored)
        assertEquals(3, restored[2].id)
    }

    @Test
    fun undoingAnEarlierRemovalRestoresTheRowsItShifted() {
        val removed = PlaylistOccurrenceEdits.remove(duplicatedSong, position = 0)!!

        val restored = PlaylistOccurrenceEdits.undo(removed.occurrences, removed.undo)

        assertEquals(duplicatedSong, restored)
    }

    @Test
    fun removeKeepsTheSetVideoIdOfTheRemovedOccurrenceForUndo() {
        val withVideoIds =
            duplicatedSong.map { if (it.id == 3) it.copy(setVideoId = "set-3") else it }

        val removed = PlaylistOccurrenceEdits.remove(withVideoIds, position = 2)!!
        val restored = PlaylistOccurrenceEdits.undo(removed.occurrences, removed.undo)

        assertEquals("set-3", restored.single { it.id == 3 }.setVideoId)
    }

    @Test
    fun removingAMissingPositionChangesNothing() {
        assertNull(PlaylistOccurrenceEdits.remove(duplicatedSong, position = 9))
    }

    @Test
    fun aMoveThenRemoveThenUndoLeavesTheListAsItWasAfterTheMove() {
        val moved = PlaylistOccurrenceEdits.move(duplicatedSong, fromPosition = 2, toPosition = 0)
        val removed = PlaylistOccurrenceEdits.remove(moved.occurrences, position = 1)!!

        val restored = PlaylistOccurrenceEdits.undo(removed.occurrences, removed.undo)

        assertEquals(moved.occurrences, restored)
    }
}
