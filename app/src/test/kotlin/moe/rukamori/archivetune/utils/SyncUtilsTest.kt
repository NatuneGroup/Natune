package moe.rukamori.archivetune.utils

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.constants.InnerTubeCookieKey
import moe.rukamori.archivetune.constants.YtmSyncKey
import moe.rukamori.archivetune.constants.SelectedYtmPlaylistsKey
import moe.rukamori.archivetune.db.InternalDatabase
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.AlbumEntity
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.AlbumPage
import moe.rukamori.archivetune.innertube.pages.LibraryPage
import moe.rukamori.archivetune.playback.queues.FiniteFixtureSource
import moe.rukamori.archivetune.playback.queues.finitePage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SyncUtilsTest {
    private lateinit var database: MusicDatabase
    private lateinit var sync: SyncUtils
    private val remote = FakeLibrarySource()
    private val playlists = FiniteFixtureSource(finitePage(songs = listOf(song("remote")), token = null, count = "1"))
    private val savedAt = LocalDateTime.of(2026, 1, 1, 0, 0)

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication<Application>()
        context.dataStore.edit {
            it.clear()
            it[InnerTubeCookieKey] = "SAPISID=synthetic-test-cookie"
            it[YtmSyncKey] = true
            it[SelectedYtmPlaylistsKey] = "unselected-fixture"
        }
        database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build())
        sync = SyncUtils(database, context, remote, playlists)
        database.insert(SongEntity("local", "Local file", inLibrary = savedAt, isLocal = true))
        database.insert(SongEntity("missing-remote", "Saved remote", inLibrary = savedAt))
        database.insert(AlbumEntity("local-album", title = "Local album", songCount = 1, duration = 1, bookmarkedAt = savedAt, isLocal = true))
        database.insert(AlbumEntity("missing-album", title = "Saved album", songCount = 1, duration = 1, bookmarkedAt = savedAt))
        database.insert(ArtistEntity("local-artist", "Local artist", bookmarkedAt = savedAt, isLocal = true))
        database.insert(ArtistEntity("missing-artist", "Saved artist", bookmarkedAt = savedAt))
        database.insert(PlaylistEntity("local-playlist", "Local playlist", bookmarkedAt = savedAt, isLocal = true))
        database.insert(PlaylistEntity("missing-playlist", "Saved playlist", browseId = "absent", bookmarkedAt = savedAt))
    }

    @After
    fun tearDown() {
        sync.close()
        database.close()
    }

    @Test
    fun localSongAndRemoteSavesSurviveSoftSync() = runTest {
        sync.syncLibrarySongs()
        sync.syncLikedAlbums()
        sync.syncArtistsSubscriptions()
        sync.syncSavedPlaylists()
        assertSavedContentUnchanged()
        assertNotNull(database.song("remote").first()?.song?.inLibrary)
    }

    @Test
    fun authoritativeCompleteSyncRemovesOnlyMissingRemoteContent() = runTest {
        // Each nonempty snapshot contains only songs, so no album/artist/playlist is remotely saved.
        remote.onlySongs = true
        sync.syncLibrarySongs(authoritative = true)
        sync.syncLikedAlbums(authoritative = true)
        sync.syncArtistsSubscriptions(authoritative = true)
        sync.syncSavedPlaylists(authoritative = true)
        assertEquals(savedAt, database.song("local").first()?.song?.inLibrary)
        assertEquals(savedAt, database.album("local-album").first()?.album?.bookmarkedAt)
        assertEquals(savedAt, database.artist("local-artist").first()?.artist?.bookmarkedAt)
        assertEquals(savedAt, database.getPlaylistById("local-playlist")?.playlist?.bookmarkedAt)
        assertNull(database.song("missing-remote").first()?.song?.inLibrary)
        assertNull(database.album("missing-album").first()?.album?.bookmarkedAt)
        assertNull(database.artist("missing-artist").first()?.artist?.bookmarkedAt)
        assertNull(database.getPlaylistById("missing-playlist")?.playlist?.bookmarkedAt)
    }

    @Test
    fun partialFailedOrMalformedRemotePagesDeleteNothing() = runTest {
        database.insert(PlaylistEntity("duplicate-playlist", "Duplicate", browseId = "absent", bookmarkedAt = savedAt))
        for (mode in listOf("initial-failure", "empty", "continuation-failure", "cycle", "malformed", "ceiling")) {
            remote.mode = mode
            sync.syncLibrarySongs(authoritative = true)
            sync.syncLikedAlbums(authoritative = true)
            sync.syncArtistsSubscriptions(authoritative = true)
            sync.syncSavedPlaylists(authoritative = true)
            assertSavedContentUnchanged()
            assertNotNull(database.getPlaylistById("duplicate-playlist"))
            // Nothing arrives without a first page; an interrupted listing still adds what it read.
            val added = database.song("remote").first()?.song?.inLibrary
            if (mode == "initial-failure" || mode == "empty") assertNull(mode, added) else assertNotNull(mode, added)
        }
    }

    @Test
    fun concurrentAutoSyncsCannotInterleaveTheirFetchAndCommit() = runTest {
        database.insert(PlaylistEntity("auto", "Auto", browseId = "fixture", isAutoSync = true))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fetches = 0
        playlists.initial = {
            fetches++
            if (fetches == 1) {
                entered.complete(Unit)
                release.await()
            }
            playlists.first
        }
        val first = launch { sync.syncAutoSyncPlaylists() }
        entered.await()
        val second = launch { sync.syncAutoSyncPlaylists() }
        runCurrent()
        assertEquals(1, fetches)
        assertTrue(database.playlistSongs("auto").first().isEmpty())
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(2, fetches)
        assertEquals(listOf("remote"), database.playlistSongs("auto").first().map { it.song.id })
    }

    @Test
    fun cancellationReleasesAutoSyncGateForManualSync() = runTest {
        database.insert(PlaylistEntity("auto", "Auto", browseId = "fixture", isAutoSync = true))
        val entered = CompletableDeferred<Unit>()
        playlists.initial = {
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
            playlists.first
        }
        val automatic = launch { sync.syncAutoSyncPlaylists() }
        entered.await()
        val manual = launch { sync.syncPlaylistNow("fixture", "auto", propagateFailures = true) }
        runCurrent()
        assertEquals(1, playlists.calls.size)
        playlists.initial = { playlists.first }
        automatic.cancelAndJoin()
        manual.join()
        assertEquals(2, playlists.calls.size)
        assertEquals(listOf("remote"), database.playlistSongs("auto").first().map { it.song.id })
    }

    private suspend fun assertSavedContentUnchanged() {
        for (id in listOf("local", "missing-remote")) assertEquals(id, savedAt, database.song(id).first()?.song?.inLibrary)
        for (id in listOf("local-album", "missing-album")) assertEquals(id, savedAt, database.album(id).first()?.album?.bookmarkedAt)
        for (id in listOf("local-artist", "missing-artist")) assertEquals(id, savedAt, database.artist(id).first()?.artist?.bookmarkedAt)
        for (id in listOf("local-playlist", "missing-playlist")) assertEquals(id, savedAt, database.getPlaylistById(id)?.playlist?.bookmarkedAt)
    }

    private class FakeLibrarySource : LibrarySyncSource {
        var mode = "complete"
        var onlySongs = false
        private var continuations = 0
        override suspend fun initial(browseId: String): Result<LibraryPage> {
            continuations = 0
            if (mode == "initial-failure") return Result.failure(IllegalStateException("Synthetic network failure"))
            if (mode == "empty") return Result.success(LibraryPage(emptyList(), null))
            val item: YTItem = when {
                onlySongs -> song("remote")
                browseId.endsWith("albums") -> AlbumItem("remote-album", "album-playlist", title = "Remote album", artists = emptyList(), thumbnail = "")
                browseId.endsWith("playlists") -> PlaylistItem("remote-playlist", "Remote playlist", null, "0", null, null, null, null)
                browseId.endsWith("artists") -> ArtistItem("remote-artist", "Remote artist", null, shuffleEndpoint = null, radioEndpoint = null)
                else -> song("remote")
            }
            return Result.success(LibraryPage(listOf(item), if (mode == "complete") null else "next"))
        }
        override suspend fun album(browseId: String): Result<AlbumPage> = Result.failure(IllegalStateException("No album detail fixture"))
        override suspend fun continuation(token: String): LibraryPage = when (mode) {
            "continuation-failure" -> error("Synthetic continuation failure")
            "cycle" -> LibraryPage(listOf(song("remote")), token)
            "ceiling" -> LibraryPage(listOf(song("remote")), "next-${++continuations}")
            else -> LibraryPage(emptyList(), null)
        }
    }

    companion object {
        private fun song(id: String) = SongItem(id, "Remote song", emptyList(), thumbnail = "", duration = 1)
    }
}
