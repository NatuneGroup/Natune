package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutoOfflineSongsTest {
    private var offline = true
    private val downloaded = mutableSetOf("download")
    private val fullyCached = mutableSetOf("cached")
    private val policy = AutoOfflineSongs(
        isOffline = { offline },
        isDownloaded = { it in downloaded },
        isFullyCached = { it.id in fullyCached },
    )

    @Test
    fun offlineFilteringKeepsOnlyLocalCompletedAndFullyCachedRowsInOriginalOrder() {
        val cached = song("cached")
        val rows = listOf(song("online"), cached, song("partial"), song("download"),
            song("file:///music/a/b.mp3"), song("content://media/external/audio/42"), song("local-row", local = true), cached)

        val result = policy.filter(rows)

        assertEquals(listOf("cached", "download", "file:///music/a/b.mp3", "content://media/external/audio/42", "local-row", "cached"),
            result.map { it.id })
        assertSame(cached, result.first())
        assertSame(cached, result.last())
    }

    @Test
    fun onlineFilteringAndSelectionPreserveTheOriginalListAndPosition() {
        offline = false
        val rows = listOf(song("online"), song("partial"))
        assertSame(rows, policy.filter(rows))
        val selected = policy.select(rows, "partial", 9_000L)!!
        assertSame(rows, selected.items)
        assertEquals(1, selected.startIndex)
        assertEquals(9_000L, selected.startPositionMs)
    }

    @Test
    fun filteringRemapsAnExplicitSelectionWithoutChangingItsIdentityOrPosition() {
        val rows = listOf(song("online"), song("download"), song("cached"))
        val selected = policy.select(rows, "cached", 9_000L)!!
        assertEquals(listOf("download", "cached"), selected.items.map { it.id })
        assertEquals(1, selected.startIndex)
        assertEquals(9_000L, selected.startPositionMs)
        assertSame(rows[2], selected.items[selected.startIndex])
    }

    @Test
    fun aMissingOrUnavailableTapNeverSubstitutesAnotherSong() {
        val rows = listOf(song("online"), song("download"), song("cached"))
        assertNull(policy.select(rows, "gone", 9_000L))
        assertNull(policy.select(rows, "online", 9_000L))
        fullyCached.clear() // Eviction between browse and tap.
        assertNull(policy.select(rows, "cached", 9_000L))
        assertNull(policy.select(emptyList(), null, 0L))
    }

    @Test
    fun playingTheWholeFolderStartsAtTheFirstEligibleRowWithAFreshPosition() {
        val selected = policy.select(listOf(song("online"), song("cached"), song("download")), null, 9_000L)!!
        assertEquals(listOf("cached", "download"), selected.items.map { it.id })
        assertEquals(0, selected.startIndex)
        assertEquals(0L, selected.startPositionMs)
    }

    @Test
    fun aQueueTapKeepsTheStampedDuplicateOccurrenceAfterFiltering() {
        val first = item("cached")
        val last = item("cached").buildUpon().setMediaMetadata(MediaMetadata.Builder().setTitle("Last occurrence").build()).build()
        val items = listOf(item("online"), first, item("download"), last)
        policy.updateSongs(listOf(song("cached"), song("download"), song("online")))
        val entry = AutoQueueMediaIds.parse(AutoQueueMediaIds.mediaIdFor(3, "cached"))!!
        val originalIndex = AutoQueueMediaIds.resolvePosition(entry, items.map { it.mediaId })!!

        val selected = policy.selectItems(items, originalIndex, 7_000L)!!

        assertEquals(2, selected.startIndex)
        assertSame(last, selected.items[selected.startIndex])
        assertEquals(7_000L, selected.startPositionMs)
        assertNull(policy.selectItems(items, 0, 7_000L))
        assertNull(policy.selectItems(items, 12, 7_000L))
    }

    @Test
    fun localUrisSurviveEveryDatabaseBrowsePrefix() {
        val id = "content://media/external/audio/media/42"
        val paths = listOf("song/$id", "artist/artist-id/$id", "album/album-id/$id",
            "playlist/list/$id", "playlist/list/_shuffle/$id", "playlist/list/_sort/NAME/asc/$id",
            "pinned_playlists/local/list/$id", "queue/4/$id")
        paths.forEach { assertEquals(it, id, it.autoSongMediaId()) }
        assertEquals(id, id.autoSongMediaId())
        assertEquals("https://example.com/audio/42", "https://example.com/audio/42".autoSongMediaId())
        assertEquals("file:///music/a/b.mp3", "song/file:///music/a/b.mp3".autoSongPathId(1))
        assertNull("song/".autoSongPathId(1))
    }

    @Test
    fun availabilityChangesReuseTheSameBrowseSnapshotAndRecheckEviction() = runTest {
        offline = false
        policy.updateSongs(listOf(song("online"), song("cached"), song("download")))
        val folders = AutoBrowseFolderUpdates<MediaItem>()
        val action = item("playlist/list/_shuffle", browsable = true)
        val original = listOf(action, item("song/online"), item("song/cached"), item("song/download"))
        val initialSnapshot = folders.children("playlist/list") { original }
        assertSame(original, policy.filterBrowseItems(original))
        offline = true
        val notified = mutableListOf<String>()
        folders.availabilityChanged({ true }, notified::add)
        val snapshot = folders.children("playlist/list") { error("Availability must not reshuffle a folder") }
        assertSame(initialSnapshot, snapshot)
        assertEquals(listOf("playlist/list"), notified)
        assertEquals(listOf(action, original[2], original[3]), policy.filterBrowseItems(snapshot))
        fullyCached.clear()
        assertEquals(listOf(action, original[3]), policy.filterBrowseItems(snapshot))
        offline = false
        assertSame(snapshot, policy.filterBrowseItems(snapshot))
    }

    @Test
    fun cacheSearchIdsRecognizeSourcePrefixesAndRenditionsWithoutDroppingUnknownIds() {
        assertEquals("video", cachedSongMediaId("qobuz:video"))
        assertEquals("video", cachedSongMediaId("video#i251"))
        assertEquals("video", cachedSongMediaId("qobuz_backup:video"))
        assertEquals("video#invalid", cachedSongMediaId("video#invalid"))
        assertEquals("video#i0", cachedSongMediaId("video#i0"))
        assertTrue(policy.filterItems(emptyList()).isEmpty())
    }

    @Test
    fun anEmptyFolderOpenedOfflineCanLoadNormallyAfterReconnection() = runTest {
        val folders = AutoBrowseFolderUpdates<MediaItem>()
        assertTrue(folders.children("home_suggested_songs", cacheEmpty = false) { emptyList() }.isEmpty())
        val online = item("cached")
        assertEquals(listOf(online), folders.children("home_suggested_songs") { listOf(online) })
        assertEquals(listOf(online), folders.children("home_suggested_songs", cacheEmpty = false) { error("Keep the existing snapshot") })
    }

    private fun song(id: String, local: Boolean = false) = Song(SongEntity(id, id, isLocal = local), emptyList())

    private fun item(id: String, browsable: Boolean = false): MediaItem = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setIsPlayable(true).setIsBrowsable(browsable).build()).build()
}
