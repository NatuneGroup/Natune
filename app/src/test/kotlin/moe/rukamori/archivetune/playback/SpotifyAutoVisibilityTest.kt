package moe.rukamori.archivetune.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyAutoVisibilityTest {
    @Test
    fun disablingMetadataHidesCachedLaterPages() = runTest {
        val cache = AutoBrowseChildrenCache<String>()
        val folder = MusicService.SPOTIFY_LIKED
        val cached = cache.children(folder) { listOf("$folder/first", "$folder/second") }
        assertEquals(cached, spotifyAutoVisibleChildren(cached, true) { it })

        val later = cache.children(folder) { error("Must reuse the folder snapshot") }
        assertEquals(emptyList<String>(), spotifyAutoVisibleChildren(later, false) { it }.autoBrowsePage(1, 1))
    }

    @Test
    fun disabledMetadataRemovesSpotifyFoldersAndTracksButKeepsOtherSources() {
        val local = listOf("liked/local", "playlist/local", "spotify_playlist_backup/local")
        val spotify = listOf(MusicService.SPOTIFY_LIKED, MusicService.SPOTIFY_PLAYLIST,
            "${MusicService.SPOTIFY_LIKED}/track", "${MusicService.SPOTIFY_PLAYLIST}/list/track")
        assertEquals(local, spotifyAutoVisibleChildren(local + spotify, false) { it })
        assertEquals(local + spotify, spotifyAutoVisibleChildren(local + spotify, true) { it })
    }
}
