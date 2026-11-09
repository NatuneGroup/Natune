package moe.rukamori.archivetune.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.songmetadata.SongMetadataOverrideCache
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class QueueMetadataUpdatesTest {
    @After
    @Before
    fun clearOverrides() = SongMetadataOverrideCache.replaceAll(emptyMap())

    @Test
    fun editingDuplicateSongsDoesNotReplaceOrSeekTheInterveningPlayingSong() {
        val fixture = Fixture(FakeQueue(listOf(item("A"), item("B"), item("A")), 1))
        val playing = fixture.queue.items[1]

        fixture.updates.applySongEdits(mapOf("A" to metadata("A").copy(title = "Edited A")))

        assertEquals(listOf(0 until 1, 2 until 3), fixture.queue.replacements)
        assertSame(playing, fixture.queue.player.currentMediaItem)
        assertEquals(30_000L, fixture.queue.player.currentPosition)
        assertEquals(listOf("Edited A", "B", "Edited A"), fixture.queue.items.map { it.mediaMetadata.title })
        assertEquals(0, fixture.queue.seeks)
    }

    @Test
    fun editingThePlayingSongSupportsAnInPlaceModuleUpdate() {
        val fixture = Fixture(FakeQueue(listOf(item("A"), item("B")), 0))

        fixture.updates.applySongEdits(mapOf("A" to metadata("A").copy(title = "Edited A")))

        assertEquals("A", fixture.queue.player.currentMediaItem?.mediaId)
        assertEquals(30_000L, fixture.queue.player.currentPosition)
        assertEquals("Edited A", fixture.current.value?.title)
        assertEquals(0, fixture.queue.seeks)
    }

    @Test
    fun prefetchedCatalogMetadataReachesTheSessionWithoutAPlaylistChangeOnTransition() {
        val fixture = Fixture(FakeQueue(listOf(item("B"), item("A")), 0))
        val resolved = metadata("A").copy(title = "Resolved A", thumbnailUrl = "https://art/resolved-A", duration = 240)
        fixture.updates.commitCatalog("A", resolved)
        assertTrue(fixture.recorder.events.isEmpty())

        fixture.queue.currentIndex = 1
        fixture.updates.publishCurrentMetadata()

        assertEquals("Resolved A", fixture.session.mediaMetadata.title)
        assertEquals(resolved.thumbnailUrl, fixture.session.mediaMetadata.artworkUri?.toString())
        assertEquals(resolved, fixture.current.value)
        assertTrue(fixture.queue.replacements.isEmpty())
        assertEquals(1, fixture.recorder.events.size)
        assertTrue(fixture.recorder.events.single().contains(Player.EVENT_MEDIA_METADATA_CHANGED))
        assertFalse(fixture.recorder.events.single().contains(Player.EVENT_TIMELINE_CHANGED))
        assertSame(fixture.session, fixture.recorder.eventPlayer)
        fixture.updates.publishCurrentMetadata()
        assertEquals(1, fixture.recorder.events.size)
    }

    @Test
    fun fallbackArtworkUsesMetadataEventsAndLeavesEveryQueueItemUntouched() {
        val fixture = Fixture(FakeQueue(listOf(item("A"), item("A")), 0))
        val original = fixture.queue.items.toList()

        fixture.updates.commitArtwork("A", "https://art/fallback")

        assertEquals("https://art/fallback", fixture.recorder.metadata.single().artworkUri?.toString())
        original.forEachIndexed { index, mediaItem -> assertSame(mediaItem, fixture.queue.items[index]) }
        assertTrue(fixture.queue.replacements.isEmpty())
        fixture.session.removeListener(fixture.recorder)
        fixture.updates.commitArtwork("A", "https://art/new-fallback")
        assertEquals(1, fixture.recorder.metadata.size)
    }

    @Test
    fun promotionPublishesArtworkResolvedBeforePlayerOwnershipTransfer() {
        val fixture = Fixture(FakeQueue(listOf(item("B"), item("A")), 0))
        fixture.updates.commitCatalog("A", metadata("A").copy(thumbnailUrl = "https://art/incoming"))

        fixture.promote(FakeQueue(listOf(item("B"), item("A")), 1))
        fixture.updates.publishCurrentMetadata()

        assertEquals("A", fixture.current.value?.id)
        assertEquals("https://art/incoming", fixture.session.mediaMetadata.artworkUri?.toString())
        assertEquals("https://art/incoming", fixture.recorder.metadata.single().artworkUri?.toString())
        assertTrue(fixture.queue.replacements.isEmpty())
    }

    @Test
    fun normalUnderlyingMetadataCallbacksCarryTheResolvedProjection() {
        val fixture = Fixture(FakeQueue(listOf(item("A")), 0))
        fixture.updates.commitArtwork("A", "https://art/resolved")
        fixture.recorder.metadata.clear()

        fixture.queue.listeners.toList().forEach { it.onMediaMetadataChanged(fixture.queue.player.mediaMetadata) }

        assertEquals("https://art/resolved", fixture.recorder.metadata.single().artworkUri?.toString())
    }

    private class Fixture(var queue: FakeQueue) {
        val current = MutableStateFlow<MediaMetadata?>(null)
        val recorder = Recorder()
        lateinit var session: NowPlayingMetadataPlayer
        val updates = QueueMetadataUpdates({ queue.player }, current) { session.publishMetadata() }

        init { promote(queue) }

        fun promote(incoming: FakeQueue) {
            if (::session.isInitialized) session.removeListener(recorder)
            queue = incoming
            session = NowPlayingMetadataPlayer(queue.player, updates::sessionMetadata)
            session.addListener(recorder)
        }
    }

    private class Recorder : Player.Listener {
        val metadata = mutableListOf<androidx.media3.common.MediaMetadata>()
        val events = mutableListOf<Player.Events>()
        var eventPlayer: Player? = null
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) { metadata.add(mediaMetadata) }
        override fun onEvents(player: Player, events: Player.Events) {
            eventPlayer = player
            this.events.add(events)
        }
    }

    private class FakeQueue(initial: List<MediaItem>, var currentIndex: Int) {
        val items = initial.toMutableList()
        val replacements = mutableListOf<IntRange>()
        val listeners = mutableListOf<Player.Listener>()
        var seeks = 0
        private var positionMs = 30_000L
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "getApplicationLooper" -> Looper.getMainLooper()
                "getCurrentMediaItem" -> items.getOrNull(currentIndex)
                "getCurrentMediaItemIndex" -> currentIndex
                "getCurrentPosition" -> positionMs
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args!![0] as Int]
                "getMediaMetadata" -> items[currentIndex].mediaMetadata
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                "replaceMediaItems" -> {
                    val from = args!![0] as Int
                    val to = args[1] as Int
                    @Suppress("UNCHECKED_CAST") val updated = args[2] as List<MediaItem>
                    replacements.add(from until to)
                    if (currentIndex in from until to && !moduleMediaItemCanUpdate(items[currentIndex], updated[currentIndex - from])) {
                        currentIndex = to.coerceAtMost(items.lastIndex)
                        positionMs = 0L
                    }
                    updated.forEachIndexed { offset, item -> items[from + offset] = item }
                    null
                }
                "seekTo" -> { seeks++; null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "FakeQueuePlayer"
                else -> throw AssertionError("Unexpected Player.${method.name}")
            }
        } as Player
    }

    private companion object {
        fun metadata(id: String) = MediaMetadata(id, id, listOf(MediaMetadata.Artist("artist", "Artist")), 180, "https://art/$id")
        fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).setUri(id).setCustomCacheKey(id)
            .setTag(metadata(id)).setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(id)
                .setArtist("Artist").setArtworkUri(android.net.Uri.parse("https://art/$id")).build()).build()
    }
}
