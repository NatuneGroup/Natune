package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.repository.ContentDownload
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase
import moe.rukamori.archivetune.shared.ui.menu.CollectionDownloadState
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueDownloadState

class DesktopDownloadMenuTest {
    private val song = ContentRef("youtube", ContentKind.SONG, "same:media-id")

    @Test
    fun bothMenusMapEveryTransferPhaseIncludingRemoval() {
        val phases = listOf(
            DownloadPhase.QUEUED to CollectionDownloadState.IN_PROGRESS,
            DownloadPhase.DOWNLOADING to CollectionDownloadState.IN_PROGRESS,
            DownloadPhase.REMOVING to CollectionDownloadState.IN_PROGRESS,
            DownloadPhase.COMPLETED to CollectionDownloadState.COMPLETED,
            DownloadPhase.STOPPED to CollectionDownloadState.NOT_DOWNLOADED,
            DownloadPhase.FAILED to CollectionDownloadState.NOT_DOWNLOADED,
        )
        for ((phase, expected) in phases) {
            val rows = listOf(ContentDownload(song.desktopDownloadId(), song, phase, 0, null))
            assertEquals(expected, desktopDownloadMenuState(rows, song))
            assertEquals(expected.name, desktopPlayerDownloadState(rows, song).name)
        }
    }

    @Test
    fun downloadIdentityIncludesProviderAndDoesNotSplitTheMediaId() {
        val other = song.copy(provider = "qobuz")
        val rows = listOf(ContentDownload(other.desktopDownloadId(), other, DownloadPhase.COMPLETED, 12, 12))
        assertEquals("youtube:same:media-id", song.desktopDownloadId())
        assertEquals(CollectionDownloadState.NOT_DOWNLOADED, desktopDownloadMenuState(rows, song))
        assertEquals(PlayerQueueDownloadState.COMPLETED, desktopPlayerDownloadState(rows, other))
        assertEquals(PlayerQueueDownloadState.NOT_DOWNLOADED, desktopPlayerDownloadState(emptyList(), song))
    }

    @Test
    fun aForeignRefCannotBorrowACompletedDownloadId() {
        val foreign = song.copy(provider = "qobuz")
        val rows = listOf(ContentDownload(song.desktopDownloadId(), foreign, DownloadPhase.COMPLETED, 12, 12))
        assertEquals(CollectionDownloadState.NOT_DOWNLOADED, desktopDownloadMenuState(rows, song))
    }

    @Test
    fun catalogSongsCanDownloadButNativeStreamingAndNonSongsCannot() {
        assertTrue(desktopDownloadSupported(song))
        assertTrue(desktopDownloadSupported(song.copy(provider = ContentProvider.SPOTIFY_CATALOG.id)))
        assertFalse(desktopDownloadSupported(song.copy(provider = ContentProvider.SPOTIFY_STREAMING.id)))
        assertFalse(desktopDownloadSupported(song.copy(provider = ContentProvider.LOCAL.id)))
        assertFalse(desktopDownloadSupported(song.copy(provider = ContentProvider.TELEGRAM.id)))
        assertFalse(desktopDownloadSupported(song.copy(id = "telegram://1/2")))
        assertFalse(desktopDownloadSupported(song.copy(kind = ContentKind.EPISODE)))
    }

    @Test
    fun playlistDownloadRowAggregatesEverySongState() {
        // An all-complete list reports complete, any in-flight row wins the progress label, and the remaining
        // mixes count as partially downloaded, as the shared menu's states table does.
        assertEquals(
            CollectionDownloadState.COMPLETED,
            desktopPlaylistDownloadState(listOf(CollectionDownloadState.COMPLETED, CollectionDownloadState.COMPLETED)),
        )
        assertEquals(
            CollectionDownloadState.IN_PROGRESS,
            desktopPlaylistDownloadState(listOf(CollectionDownloadState.COMPLETED, CollectionDownloadState.IN_PROGRESS)),
        )
        assertEquals(
            CollectionDownloadState.PARTIALLY,
            desktopPlaylistDownloadState(listOf(CollectionDownloadState.COMPLETED, CollectionDownloadState.NOT_DOWNLOADED)),
        )
        assertEquals(
            CollectionDownloadState.NOT_DOWNLOADED,
            desktopPlaylistDownloadState(listOf(CollectionDownloadState.NOT_DOWNLOADED)),
        )
        assertEquals(CollectionDownloadState.NOT_DOWNLOADED, desktopPlaylistDownloadState(emptyList()))
    }
}
