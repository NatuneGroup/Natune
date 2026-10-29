package moe.rukamori.archivetune.models

import moe.rukamori.archivetune.innertube.models.SongItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastMediaMetadataTest {
    @Test fun historyAndPlaylistSongShapedEpisodesKeepThePodcastMarkerAtThePlayerBoundary() {
        val item = SongItem(id = "episode_001", title = "Episode", artists = emptyList(),
            thumbnail = "https://example.com/art", isPodcast = true)
        assertTrue(item.toMediaMetadata().isPodcast)
        assertFalse(item.copy(isPodcast = false).toMediaMetadata().isPodcast)
    }
}
