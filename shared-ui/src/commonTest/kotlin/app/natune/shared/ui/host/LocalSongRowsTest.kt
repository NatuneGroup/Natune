package app.natune.shared.ui.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import app.natune.shared.domain.localmedia.LocalAlbumRecord
import app.natune.shared.domain.localmedia.LocalArtistRecord
import app.natune.shared.domain.localmedia.LocalTrackRecord
import app.natune.shared.domain.repository.ContentItem
import app.natune.shared.domain.repository.ContentKind
import app.natune.shared.domain.repository.ContentRef
import app.natune.shared.ui.screens.LocalSongSort
import app.natune.shared.ui.screens.visibleLocalSongs

class LocalSongRowsTest {
    private val trackId = "local:7a0e1b62-3f6f-3dc5-9e35-6d0f5d5d3f3f"
    private val localRef = ContentRef("local", ContentKind.SONG, trackId)

    @Test fun aScannedFileJoinsItsAlbumNameAndFileTime() {
        val item = ContentItem(localRef, "Imported song")
        val rows = localSongRows(listOf(item), listOf(record()))
        val row = rows.single()
        assertEquals(trackId, row.row.occurrenceId)
        assertEquals("Imported song", row.row.item.title)
        assertEquals("Imported album", row.album)
        assertEquals(1_700_000_000_000L, row.modifiedEpochMs)
    }

    @Test fun aRowWithoutAScanRecordSortsLastRatherThanClaimingAFreshTime() {
        val item = ContentItem(localRef, "Imported song")
        val row = localSongRows(listOf(item), emptyList()).single()
        assertEquals("", row.album)
        assertEquals(Long.MIN_VALUE, row.modifiedEpochMs)
        val newer = localSongRows(
            listOf(ContentItem(localRef.copy(id = "local:other"), "Other"), item),
            listOf(record()),
        )
        assertEquals(
            listOf("Imported song", "Other"),
            visibleLocalSongs(newer, "", LocalSongSort.MODIFIED, true).map { it.row.item.title },
        )
    }

    @Test fun aSongOfAnotherProviderIsNotALocalRow() {
        val remote = ContentItem(ContentRef("tidal", ContentKind.SONG, "100"), "Remote")
        assertTrue(localSongRows(listOf(remote), emptyList()).isEmpty())
    }

    private fun record() = LocalTrackRecord(
        id = trackId,
        path = "/container/imported-media/song.mp3",
        title = "Imported song",
        artists = listOf(LocalArtistRecord("LOCAL_ARTIST_x", "An artist")),
        album = LocalAlbumRecord("LOCAL_ALBUM_x", "Imported album", null, null),
        durationMs = 200_000L,
        modifiedEpochMs = 1_700_000_000_000L,
        sizeBytes = 4_000_000L,
        year = null,
        trackNumber = null,
        artworkUri = null,
        lyricsText = null,
    )
}
