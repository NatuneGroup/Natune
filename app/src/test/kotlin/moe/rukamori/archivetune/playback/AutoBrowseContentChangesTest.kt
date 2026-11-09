package moe.rukamori.archivetune.playback

import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.db.entities.ArtistEntity
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
    private val likedAlias = "${MusicService.PLAYLIST}/${PlaylistEntity.LIKED_PLAYLIST_ID}"

    @Test
    fun playbackStatisticsDurationsAndLyricsOffsetsDoNotInvalidateAnyFolder() {
        val before = mapOf("A" to song.autoBrowseContent())
        val played = song.song.copy(totalPlayTime = 180_000, duration = 215, lyricsOffset = 200)
        val after = mapOf("A" to song.copy(song = played).autoBrowseContent())
        assertEquals(before, after)
        val changes = autoBrowseSongChanges(before, after)
        assertTrue(changes.folders.isEmpty())
        assertTrue(changes.prefixes.isEmpty())
    }

    @Test
    fun songsThatPlaybackStoresRefreshNoFolder() {
        // Playback stores the current and related songs outside the library, without a song_album_map row.
        val related = Song(
            SongEntity("B", "Related", thumbnailUrl = "https://example.com/b.jpg", albumId = "album-B", albumName = "Album B"),
            listOf(ArtistEntity("artist-B", "Artist B")),
        )
        val before = mapOf("A" to song.autoBrowseContent())
        val changes = autoBrowseSongChanges(before, before + ("B" to related.autoBrowseContent()))
        assertTrue(changes.folders.isEmpty())
        assertTrue(changes.prefixes.isEmpty())
    }

    @Test
    fun aLikeRefreshesLikedAliasesAndCountsWithoutInvalidatingAnUnrelatedPlaylist() {
        val before = mapOf("A" to song.autoBrowseContent())
        val after = mapOf("A" to song.copy(song = song.song.copy(liked = true, likedDate = addedAt)).autoBrowseContent())
        val changes = autoBrowseSongChanges(before, after)
        assertTrue(changes.affects(MusicService.LIKED))
        assertTrue(changes.affects(likedAlias))
        assertTrue(changes.affects("$likedAlias/sort"))
        assertTrue(changes.affects(MusicService.PLAYLIST))
        assertFalse(changes.affects("${MusicService.PLAYLIST}/other"))
        assertFalse(changes.affects(MusicService.SONG))
        assertFalse(changes.affects(MusicService.DOWNLOADED))
        assertFalse(changes.affects(MusicService.RECENT))
        assertFalse(changes.affects(MusicService.QUEUE))
    }

    @Test
    fun songPresentationChangesRefreshOnlyPlaylistsContainingThatSong() {
        val before = mapOf("A" to song.autoBrowseContent())
        val after = mapOf("A" to song.copy(song = song.song.copy(title = "Edited song")).autoBrowseContent())
        val change = autoBrowseSongChanges(before, after, mapOf("A" to setOf("contains-A")))
        assertTrue(change.affects("${MusicService.PLAYLIST}/contains-A"))
        assertTrue(change.affects("${MusicService.PINNED_PLAYLISTS}/local/contains-A"))
        assertTrue(change.affects(MusicService.SONG))
        assertFalse(change.affects("${MusicService.PLAYLIST}/unrelated"))
        assertFalse(change.affects(MusicService.LIKED))
        assertFalse(change.affects(MusicService.QUEUE))
    }

    @Test
    fun savingASongToTheLibraryRefreshesTheLibraryFolders() {
        val stored = Song(SongEntity("B", "Song B", albumId = "album-B"), listOf(ArtistEntity("artist-B", "Artist B")))
        val saved = stored.copy(song = stored.song.copy(inLibrary = addedAt))
        val change = autoBrowseSongChanges(mapOf("B" to stored.autoBrowseContent()), mapOf("B" to saved.autoBrowseContent()))
        listOf(MusicService.SONG, MusicService.ARTIST, "${MusicService.ARTIST}/artist-B", MusicService.ALBUM).forEach {
            assertTrue(it, change.affects(it))
        }
        assertFalse(change.affects(MusicService.LIKED))
        assertFalse(change.affects(MusicService.DOWNLOADED))
    }

    @Test
    fun blockingAnArtistRefreshesTheFoldersThatHideTheirSongs() {
        val artist = ArtistEntity("artist-A", "Artist A")
        val liked = song.copy(song = song.song.copy(liked = true, likedDate = addedAt), artists = listOf(artist))
        val blocked = liked.copy(artists = listOf(artist.copy(blockedAt = addedAt)))
        val change = autoBrowseSongChanges(mapOf("A" to liked.autoBrowseContent()), mapOf("A" to blocked.autoBrowseContent()))
        assertTrue(change.affects(MusicService.LIKED))
        assertTrue(change.affects("$likedAlias/sort"))
        assertTrue(change.affects(MusicService.HOME_KEEP_LISTENING))
        assertFalse(change.affects(MusicService.PLAYLIST))
        assertFalse(change.affects(MusicService.SONG))
        assertFalse(change.affects(MusicService.ARTIST))
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
        assertTrue(autoBrowseDownloadChanges.affects("${MusicService.PLAYLIST}/${PlaylistEntity.DOWNLOADED_PLAYLIST_ID}/sort"))
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
