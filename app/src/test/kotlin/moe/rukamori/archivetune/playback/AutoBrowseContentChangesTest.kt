package moe.rukamori.archivetune.playback

import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.db.entities.PlaylistSongMap
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AutoBrowseContentChangesTest {
    private val addedAt = LocalDateTime.of(2026, 1, 1, 0, 0)
    private val song = Song(SongEntity("A", "Song", inLibrary = addedAt), emptyList())

    @Test
    fun playbackStatisticsAndLyricsOffsetsDoNotInvalidateAnyFolder() {
        val before = mapOf("A" to song.autoBrowseContent())
        val after = mapOf("A" to song.copy(song = song.song.copy(totalPlayTime = 180_000, lyricsOffset = 200)).autoBrowseContent())
        assertEquals(before, after)
        val changes = autoBrowseSongChanges(before, after)
        assertTrue(changes.folders.isEmpty())
        assertTrue(changes.prefixes.isEmpty())
    }

    @Test
    fun aLikeRefreshesLikedAliasesAndCountsWithoutInvalidatingAnUnrelatedPlaylist() {
        val before = mapOf("A" to song.autoBrowseContent())
        val after = mapOf("A" to song.copy(song = song.song.copy(liked = true, likedDate = addedAt)).autoBrowseContent())
        val changes = autoBrowseSongChanges(before, after)
        assertTrue(changes.affects(MusicService.LIKED))
        assertTrue(changes.affects("${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}"))
        assertTrue(changes.affects(MusicService.PLAYLIST))
        assertFalse(changes.affects("${MusicService.PLAYLIST}/other"))
        assertFalse(changes.affects(MusicService.QUEUE))
    }

    @Test
    fun songPresentationChangesRefreshOnlyPlaylistsContainingThatSong() {
        val before = mapOf("A" to song.autoBrowseContent())
        val after = mapOf("A" to song.copy(song = song.song.copy(title = "Edited song")).autoBrowseContent())
        val change = autoBrowseSongChanges(before, after, mapOf("A" to setOf("contains-A")))
        assertTrue(change.affects("${MusicService.PLAYLIST}/contains-A"))
        assertTrue(change.affects("${MusicService.PINNED_PLAYLISTS}/local/contains-A"))
        assertFalse(change.affects("${MusicService.PLAYLIST}/unrelated"))
        assertFalse(change.affects(MusicService.QUEUE))
    }

    @Test
    fun playlistInsertionRemovalAndReorderRefreshThePlaylistAndItsDrivingPin() {
        val playlist = PlaylistEntity(id = "P", name = "Playlist", browseId = "remote-P", createdAt = addedAt, lastUpdateTime = addedAt)
        val entries = listOf(PlaylistSongMap(1, "P", "A", 0), PlaylistSongMap(2, "P", "B", 1))
        val before = mapOf("P" to AutoBrowsePlaylistContent(playlist, entries))
        val mutations = listOf(
            entries + PlaylistSongMap(3, "P", "C", 2), // Auto add-current-song changes membership after its transaction commits.
            entries.take(1),
            entries.reversed().mapIndexed { index, row -> row.copy(position = index) },
        )
        mutations.forEach { updated ->
            val change = autoBrowsePlaylistChanges(before, mapOf("P" to AutoBrowsePlaylistContent(playlist, updated)))
            assertTrue(change.affects("${MusicService.PLAYLIST}/P"))
            assertTrue(change.affects("${MusicService.PLAYLIST}/P/sort"))
            assertTrue(change.affects("${MusicService.PINNED_PLAYLISTS}/local/P"))
            assertTrue(change.affects("${MusicService.ONLINE_PLAYLIST}/remote-P"))
            assertTrue(change.affects(MusicService.PLAYLIST))
            assertFalse(change.affects("${MusicService.PLAYLIST}/other"))
        }
        assertTrue(autoBrowsePlaylistChanges(before, before).folders.isEmpty())
    }

    @Test
    fun onlyCompletedDownloadMembershipChangesTheBrowseProjection() {
        val queued = mapOf("A" to Download.STATE_QUEUED)
        val downloading = mapOf("A" to Download.STATE_DOWNLOADING)
        val finished = mapOf("A" to Download.STATE_COMPLETED)
        assertEquals(autoBrowseCompletedDownloadIds(queued) { it }, autoBrowseCompletedDownloadIds(downloading) { it })
        assertEquals(setOf("A"), autoBrowseCompletedDownloadIds(finished) { it })
        assertEquals(setOf("A"), autoBrowseCompletedDownloadIds(finished + ("B" to Download.STATE_DOWNLOADING)) { it })
        assertTrue(autoBrowseCompletedDownloadIds(emptyMap<String, Int>()) { it }.isEmpty())
    }

    @Test
    fun downloadAndSpotifyChangesRefreshTheirNestedFolders() {
        assertTrue(autoBrowseDownloadChanges.affects(MusicService.DOWNLOADED))
        assertTrue(autoBrowseDownloadChanges.affects("${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}"))
        assertTrue(autoBrowseSpotifyChanges.affects(MusicService.PLAYLIST))
        assertTrue(autoBrowseSpotifyChanges.affects(MusicService.SPOTIFY_LIKED))
        assertTrue(autoBrowseSpotifyChanges.affects("${MusicService.SPOTIFY_PLAYLIST}/account-playlist"))
        assertFalse(autoBrowseSpotifyChanges.affects("${MusicService.PLAYLIST}/local-playlist"))
    }

    @Test
    fun contentInvalidationNotifiesSubscribersAndReloadsOnlyAffectedSnapshots() = runTest {
        val folders = AutoBrowseFolderUpdates<Int>()
        var likedLoads = 0
        folders.children(MusicService.LIKED) { listOf(++likedLoads) }
        folders.children("${MusicService.PLAYLIST}/other") { listOf(7) }
        val notifications = mutableListOf<String>()
        val liked = song.copy(song = song.song.copy(liked = true, likedDate = addedAt))

        folders.invalidate(autoBrowseSongChanges(mapOf("A" to song.autoBrowseContent()), mapOf("A" to liked.autoBrowseContent())),
            { true }, notifications::add)

        assertEquals(listOf(MusicService.LIKED), notifications)
        assertEquals(listOf(2), folders.children(MusicService.LIKED) { listOf(++likedLoads) })
        assertEquals(listOf(7), folders.children("${MusicService.PLAYLIST}/other") { error("unrelated snapshot was lost") })
        folders.invalidate(AutoBrowseContentChange(), { true }, notifications::add)
        assertEquals(1, notifications.size)
    }

    @Test
    fun subscribersAreNotLostWhenTheirSnapshotIsEvicted() = runTest {
        val folders = AutoBrowseFolderUpdates<Int>()
        folders.children(MusicService.LIKED) { listOf(1) }
        repeat(17) { index -> folders.children("${MusicService.PLAYLIST}/$index") { listOf(index) } }
        val notifications = mutableListOf<String>()
        folders.invalidate(AutoBrowseContentChange(setOf(MusicService.LIKED)), { it == MusicService.LIKED }, notifications::add)
        assertEquals(listOf(MusicService.LIKED), notifications)
    }
}
