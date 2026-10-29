package moe.rukamori.archivetune.shared.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.data.persistence.QueueCheckpointJson
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueLocatorResolver
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataOverride
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataRepository

/**
 * The song-metadata projection of [PortableQueueOwner]: edits reach the published queue and a re-edit or
 * reset re-projects every occurrence in place, while provider resolution and the persisted checkpoint
 * always see the provider names.
 */
class PortableQueueOwnerMetadataTest {
    // Unconfined so the owner's collectors resume inline during advanceUntilIdle; a StandardTestDispatcher
    // leaves them parked in the queue behind the idle check.
    private val scope = TestScope(UnconfinedTestDispatcher())

    private val songRef = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, "song")
    private val song = ContentItem(songRef, "Provider title", artists = listOf(ContentCredit("Provider artist", "UC1")))
    private val other = ContentItem(songRef.copy(id = "other"), "Other", artists = listOf(ContentCredit("Other artist")))
    private val edit = SongMetadataOverride(title = "Edited title", artistNames = listOf("Edited artist"), updatedAtMs = 7L)

    private class FakePlaybackPort : PlaybackPort {
        val commands = mutableListOf<PlaybackCommand>()
        override val snapshots = MutableStateFlow<PlaybackSnapshot?>(null)
        override val failures = MutableSharedFlow<PlaybackFailure>()
        override fun submit(command: PlaybackCommand) { commands += command }
        override fun close() = Unit
    }

    private class RecordingLocators : QueueLocatorResolver {
        val items = mutableListOf<ContentItem>()
        override suspend fun locate(occurrence: ContentOccurrence): PlaybackLocator {
            items += occurrence.item
            return PlaybackLocator.Remote("https://cdn.example/stream")
        }
    }

    /** [emitChanges] false mimics the window before the repository's first observed emission. */
    private class FakeSongMetadata(
        initial: Map<ContentRef, SongMetadataOverride>,
        private val emitChanges: Boolean = true,
    ) : SongMetadataRepository {
        val edits = MutableStateFlow(initial)
        override fun observeOverrides(): Flow<Map<ContentRef, SongMetadataOverride>> = if (emitChanges) edits else emptyFlow()
        override suspend fun overrides(): Map<ContentRef, SongMetadataOverride> = edits.value
        override suspend fun setOverride(song: ContentRef, metadata: SongMetadataOverride?) {
            edits.value = if (metadata == null) edits.value - song else edits.value + (song to metadata)
        }
    }

    private class Harness(background: CoroutineScope, songMetadata: SongMetadataRepository) {
        val port = FakePlaybackPort()
        val player = MusicPlayerController(port, background)
        val locators = RecordingLocators()
        val owner = PortableQueueOwner(
            player = player,
            scope = background,
            content = ContentRepositoryRouter(emptyList()),
            locators = locators,
            songMetadata = songMetadata,
        )
    }

    @Test
    fun firstQueueAccessAppliesEditsBeforeAnyObserverEmission() = scope.runTest {
        val metadata = FakeSongMetadata(mapOf(songRef to edit), emitChanges = false)
        val h = Harness(backgroundScope, metadata)

        // The observer never emitted: the locked read inside withQueueAccess must apply the edits
        // synchronously rather than publish provider names first.
        h.owner.withQueueAccess {
            h.owner.replace(
                listOf(
                    ContentOccurrence("first", song, setVideoId = "set-a"),
                    ContentOccurrence("second", other),
                    ContentOccurrence("third", song, setVideoId = "set-b"),
                ),
                startOccurrenceId = "first",
                playWhenReady = true,
            )
        }

        val queue = h.owner.state.value
        assertEquals(1L, queue.revision)
        assertEquals(listOf("first", "second", "third"), queue.entries.map { it.occurrenceId })
        assertEquals(listOf("set-a", null, "set-b"), queue.entries.map { it.setVideoId })
        assertEquals("Edited title", queue.entries[0].item.title)
        assertEquals(listOf("Edited artist"), queue.entries[0].item.artists.map { it.name })
        assertEquals("Provider title", queue.entries[0].item.canonicalSongMetadata?.title)
        assertEquals("Other", queue.entries[1].item.title)
        assertNull(queue.entries[1].item.canonicalSongMetadata)
        assertEquals("Edited title", queue.entries[2].item.title)

        // The load went through the canonical song, not the displayed one.
        val located = h.locators.items.single()
        assertEquals("Provider title", located.title)
        assertEquals(listOf("Provider artist"), located.artists.map { it.name })
        assertNull(located.canonicalSongMetadata)
        assertIs<PlaybackCommand.Load>(h.port.commands.single())

        // A checkpoint taken now persists provider names, so a restore never bakes an edit in.
        val checkpoint = h.owner.withQueueAccess { h.owner.capture() }
        val persisted = QueueCheckpointJson.decode(QueueCheckpointJson.encode(checkpoint))
        assertEquals(listOf("Provider title", "Other", "Provider title"), persisted.queue.entries.map { it.item.title })
        assertEquals(listOf("Provider artist"), persisted.queue.entries[0].item.artists.map { it.name })
        assertEquals(1L, persisted.queue.revision)
    }

    @Test
    fun editAndResetReprojectPublishedQueueWithoutTouchingPlayback() = scope.runTest {
        val metadata = FakeSongMetadata(mapOf(songRef to edit))
        val h = Harness(backgroundScope, metadata)
        h.owner.withQueueAccess {
            h.owner.replace(
                listOf(
                    ContentOccurrence("first", song, setVideoId = "set-a"),
                    ContentOccurrence("second", song, setVideoId = "set-b"),
                ),
                startOccurrenceId = "first",
                playWhenReady = true,
            )
        }
        assertEquals(listOf("Edited title", "Edited title"), h.owner.state.value.entries.map { it.item.title })

        // Playing a displayed occurrence resolves the canonical song.
        h.owner.withQueueAccess { h.owner.select("second", 0L) }
        assertEquals(listOf("Provider title", "Provider title"), h.locators.items.map { it.title })
        val revisionBefore = h.owner.state.value.revision
        val loadsBefore = h.port.commands.size

        // A re-edit re-projects every occurrence of the song, order and identity untouched.
        metadata.setOverride(songRef, SongMetadataOverride(title = "Newer title", updatedAtMs = 9L))
        advanceUntilIdle()
        val reprojected = h.owner.state.value
        assertEquals(revisionBefore, reprojected.revision)
        assertEquals("second", reprojected.currentOccurrenceId)
        assertEquals(listOf("first", "second"), reprojected.entries.map { it.occurrenceId })
        assertEquals(listOf("set-a", "set-b"), reprojected.entries.map { it.setVideoId })
        assertEquals(listOf("Newer title", "Newer title"), reprojected.entries.map { it.item.title })
        assertEquals(listOf("Provider artist"), reprojected.entries[0].item.artists.map { it.name })
        assertEquals(loadsBefore, h.port.commands.size)

        // A reset returns the queue to the provider names and drops the retained canonical copy.
        metadata.setOverride(songRef, null)
        advanceUntilIdle()
        val reset = h.owner.state.value
        assertEquals(revisionBefore, reset.revision)
        assertEquals(listOf("Provider title", "Provider title"), reset.entries.map { it.item.title })
        assertNull(reset.entries[0].item.canonicalSongMetadata)
        assertEquals(loadsBefore, h.port.commands.size)
    }
}
