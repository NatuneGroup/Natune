package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpoint
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.repository.TypedQueueOwnerPort

class DesktopRadioOwnerTest {
    @Test
    fun onlyYouTubeSongsOfferRadio() {
        val song = ref("seed")
        assertTrue(desktopRadioSeedSupported(song))
        assertFalse(desktopRadioSeedSupported(song.copy(kind = ContentKind.EPISODE)))
        assertFalse(desktopRadioSeedSupported(song.copy(kind = ContentKind.PLAYLIST)))
        assertFalse(desktopRadioSeedSupported(song.copy(provider = ContentProvider.LOCAL.id)))
        assertFalse(desktopRadioSeedSupported(song.copy(provider = ContentProvider.SPOTIFY_CATALOG.id)))
        assertFalse(desktopRadioSeedSupported(song.copy(id = "telegram://1/2")))
    }

    @Test
    fun currentSongUsesSeamlessRadioInsideTheQueueLock() = runBlocking {
        val queue = QueuePort(ref("seed"))
        val before = queue.published.value
        DesktopRadioOwner(queue).start(ref("seed"), "selected-set")
        assertEquals(1, queue.accesses)
        assertEquals(1, queue.seamlessStarts)
        assertTrue(queue.loads.isEmpty())
        assertEquals(before, queue.published.value)
    }

    @Test
    fun anotherSongLoadsWatchNextAndRetainsItsEndpointSetId() = runBlocking {
        val queue = QueuePort(ref("playing"))
        val seed = ref("selected")
        DesktopRadioOwner(queue).start(seed, "selected-set")
        val request = queue.loads.single()
        assertEquals(QueueCollectionKind.RADIO, request.kind)
        assertEquals(ContentSurface.RADIO, request.collection.surface)
        assertEquals(seed, request.collection.parent)
        assertEquals("selected-set", request.collection.section?.id)
        assertEquals(seed, request.start?.item)
        assertEquals("selected-set", request.start?.setVideoId)
        assertTrue(request.playWhenReady)
        assertFalse(request.shuffle)
        assertEquals(0, queue.seamlessStarts)
    }

    @Test
    fun matchingIdFromAnotherProviderDoesNotReuseTheCurrentSong() = runBlocking {
        val queue = QueuePort(ref("seed").copy(provider = ContentProvider.LOCAL.id))
        DesktopRadioOwner(queue).start(ref("seed"))
        assertEquals(1, queue.loads.size)
        assertEquals(0, queue.seamlessStarts)
    }

    @Test
    fun unsupportedSeedNeverEntersTheQueue() = runBlocking {
        val queue = QueuePort(ref("playing"))
        assertFailsWith<IllegalArgumentException> {
            DesktopRadioOwner(queue).start(ref("episode").copy(kind = ContentKind.EPISODE))
        }
        assertEquals(0, queue.accesses)
    }

    @Test
    fun cancellationAndSeamlessRefusalPropagateWithoutASoloReplacement() = runBlocking {
        val queue = QueuePort(ref("playing"))
        queue.loadFailure = CancellationException("cancelled radio")
        assertFailsWith<CancellationException> { DesktopRadioOwner(queue).start(ref("selected")) }
        queue.seamlessResult = false
        assertFailsWith<IllegalStateException> { DesktopRadioOwner(queue).start(ref("playing")) }
        assertTrue(queue.loads.isEmpty())
        assertEquals("current", queue.published.value.currentOccurrenceId)
    }

    private fun ref(id: String) = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, id)

    private class QueuePort(current: ContentRef) : TypedQueueOwnerPort {
        val published = MutableStateFlow(
            QueueState(1, listOf(ContentOccurrence("current", ContentItem(current, "Current"))), "current", listOf("current")),
        )
        private var inside = false
        var accesses = 0
        var seamlessStarts = 0
        var seamlessResult = true
        var loadFailure: Exception? = null
        val loads = mutableListOf<QueueLoadRequest>()

        override val player: MusicPlayerController get() = error("Radio must not drive the player directly")
        override val state get() = published.also { check(inside) }
        override suspend fun <T> withQueueAccess(operation: suspend () -> T): T {
            check(!inside)
            inside = true
            accesses++
            return try { operation() } finally { inside = false }
        }
        override suspend fun loadCollection(request: QueueLoadRequest) {
            check(inside)
            loadFailure?.let { throw it }
            loads += request
        }
        override suspend fun startRadioSeamlessly(): Boolean {
            check(inside)
            seamlessStarts++
            return seamlessResult
        }
        override suspend fun replace(
            entries: List<ContentOccurrence>,
            startOccurrenceId: String,
            playWhenReady: Boolean,
            staysFinite: Boolean,
        ) = error("Radio must not commit a solo queue")
        override suspend fun insert(entries: List<ContentOccurrence>, kind: UserQueuedKind) = error("Unexpected insert")
        override suspend fun remove(occurrenceIds: Set<String>) = error("Unexpected remove")
        override suspend fun move(occurrenceId: String, beforeOccurrenceId: String?) = error("Unexpected move")
        override suspend fun select(occurrenceId: String, positionMs: Long) = error("Unexpected select")
        override suspend fun capture(): QueueCheckpoint = error("Unexpected capture")
        override suspend fun restore(checkpoint: QueueCheckpoint) = error("Unexpected restore")
        override suspend fun setShuffle(enabled: Boolean): Boolean = error("Unexpected shuffle")
        override suspend fun raiseRevisionTo(floor: Long) = error("Unexpected revision")
    }
}
