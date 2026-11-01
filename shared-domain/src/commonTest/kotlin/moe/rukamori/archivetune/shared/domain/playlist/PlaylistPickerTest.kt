package moe.rukamori.archivetune.shared.domain.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.customartwork.isLocalPlaylistCoverUri
import moe.rukamori.archivetune.shared.domain.customartwork.localPlaylistCoverUri
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * Characterizes the playlist picker order, the batch-add progress report, the move-to-top/bottom orders and the
 * local mosaic/cover identities. The successor-pointer moves name the first or last row of the complete order,
 * because the same neighbour insert the up and down moves use lands a song at an end in one step.
 */
class PlaylistPickerTest {
    private fun playlist(id: String, title: String) =
        PlaylistPickerCandidate(
            item = ContentItem(ContentRef(ContentProvider.LOCAL.id, ContentKind.PLAYLIST, id), title),
            songCount = 0,
            createdSeq = 0,
            updatedSeq = 0,
        )

    @Test
    fun nameSortUsesTheCollationAndBreaksTiesById() {
        val candidates =
            listOf(
                playlist("b", "beta"),
                playlist("a2", "Alpha"),
                playlist("a1", "alpha"),
            )

        val ordered = PlaylistPickerOrder.sorted(candidates, PlaylistPickerSortType.NAME, descending = false)

        assertEquals(listOf("a1", "a2", "b"), ordered.map { it.item.ref.id })
    }

    @Test
    fun numericSortsOrderByTheirStoredValueAndDescendingReversesThem() {
        val candidates =
            listOf(
                playlist("a", "A").copy(createdSeq = 3, updatedSeq = 1, songCount = 9),
                playlist("b", "B").copy(createdSeq = 1, updatedSeq = 7, songCount = 2),
                playlist("c", "C").copy(createdSeq = 2, updatedSeq = 4, songCount = 5),
            )

        assertEquals(
            listOf("b", "c", "a"),
            PlaylistPickerOrder.sorted(candidates, PlaylistPickerSortType.CREATE_DATE, descending = false)
                .map { it.item.ref.id },
        )
        assertEquals(
            listOf("a", "c", "b"),
            PlaylistPickerOrder.sorted(candidates, PlaylistPickerSortType.LAST_UPDATED, descending = false)
                .map { it.item.ref.id },
        )
        assertEquals(
            listOf("a", "c", "b"),
            PlaylistPickerOrder.sorted(candidates, PlaylistPickerSortType.SONG_COUNT, descending = true)
                .map { it.item.ref.id },
        )
    }

    @Test
    fun holdsAllRequiresEveryBatchRef() {
        val songA = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, "A")
        val songB = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, "B")
        val candidate = playlist("p", "P").copy(memberRefs = setOf(songA, songB))

        assertTrue(candidate.holdsAll(listOf(songA, songB)))
        assertFalse(candidate.holdsAll(listOf(songA)))
        assertFalse(candidate.holdsAll(emptyList()))
    }

    @Test
    fun progressCountsOutcomesPerPlaylistTracks() {
        val progress =
            PlaylistAddProgress(total = 9)
                .record("ok", 3, PlaylistAddOutcome.COMPLETED)
                .record("present", 3, PlaylistAddOutcome.ALREADY_PRESENT)
                .record("refused", 3, PlaylistAddOutcome.FAILED, "IllegalStateException")

        assertEquals(9, progress.processed)
        assertEquals(6, progress.settled)
        assertEquals(0, progress.unconfirmed)
        assertFalse(progress.clean)
        assertEquals(listOf(PlaylistAddFailure("refused", "IllegalStateException")), progress.failed)
        assertEquals(emptyList(), progress.uncertain)
    }

    @Test
    fun aStoppedRunMarksTheCurrentPlaylistUncertainAndTheTailUnconfirmed() {
        val progress =
            PlaylistAddProgress(total = 9)
                .record("ok", 3, PlaylistAddOutcome.COMPLETED)
                .stoppedAt("interrupted", 3)

        assertTrue(progress.stopped)
        assertFalse(progress.clean)
        assertEquals(listOf("interrupted"), progress.uncertain)
        // Three tracks committed; the interrupted three plus the unprocessed three stay unconfirmed.
        assertEquals(3, progress.settled)
        assertEquals(6, progress.unconfirmed)
    }

    @Test
    fun aCleanRunHasNoReport() {
        val progress =
            PlaylistAddProgress(total = 6)
                .record("ok", 3, PlaylistAddOutcome.COMPLETED)
                .record("present", 3, PlaylistAddOutcome.ALREADY_PRESENT)

        assertTrue(progress.clean)
    }

    @Test
    fun theMosaicKeepsTheFirstRowsInStoredOrder() {
        fun item(id: String) = ContentItem(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, id), id)
        val rows = (1..6).map { ContentOccurrence("occ-$it", item("s$it")) }

        val mosaic = playlistMosaicItems(rows)

        assertEquals(4, mosaic.size)
        assertEquals(listOf("s1", "s2", "s3", "s4"), mosaic.map { it.ref.id })
        assertEquals(emptyList(), playlistMosaicItems(emptyList()))
        assertEquals(2, playlistMosaicItems(rows.take(2)).size)
    }

    @Test
    fun theCoverIdentityRoundTripsAndStaysOffTheWeb() {
        val uri = localPlaylistCoverUri("abc-123")

        assertTrue(isLocalPlaylistCoverUri(uri))
        assertFalse(isLocalPlaylistCoverUri("https://i.ytimg.com/vi/x/default.jpg"))
        assertFalse(isLocalPlaylistCoverUri(null))
        // The key is stable: two reads of one playlist name the same stored cover.
        assertEquals(uri, localPlaylistCoverUri("abc-123"))
        assertTrue(uri != localPlaylistCoverUri("other"))
    }

    @Test
    fun moveBesideBeforeTheFirstRowLandsTheSongAtTheTop() {
        val order = listOf("one", "two", "three")

        val moved = PlaylistSongOrder.moveBeside(order, "three", "one", after = false)

        assertEquals(listOf("three", "one", "two"), moved)
    }

    @Test
    fun moveBesideAfterTheLastRowLandsTheSongAtTheBottom() {
        val order = listOf("one", "two", "three")

        val moved = PlaylistSongOrder.moveBeside(order, "one", "three", after = true)

        assertEquals(listOf("two", "three", "one"), moved)
    }

    @Test
    fun moveBesideRefusesUnknownOrSelfMoves() {
        val order = listOf("one", "two")

        assertNull(PlaylistSongOrder.moveBeside(order, "one", "one", after = true))
        assertNull(PlaylistSongOrder.moveBeside(order, "gone", "one", after = false))
        assertNull(PlaylistSongOrder.moveBeside(order, "one", "gone", after = false))
    }
}
