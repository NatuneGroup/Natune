package moe.rukamori.archivetune.shared.data.deviceconnect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectErrorCode
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlaybackAction
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceQueueEntry
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceRemoteCommand
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceSyncMessage
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceSyncMessageType
import moe.rukamori.archivetune.shared.domain.deviceconnect.toDeviceQueueEntry
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.domain.repository.QueueState

class QueueDevicePlaybackPortTest {
    private val occurrence = ContentOccurrence(
        "a",
        ContentItem(ContentRef("youtube", ContentKind.SONG, "video-a"), "First"),
    )
    private val second = ContentOccurrence(
        "b",
        ContentItem(ContentRef("youtube", ContentKind.SONG, "video-b"), "Second"),
    )

    @Test
    fun captureUsesTheOneQueueAndPlayer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = FakePlaybackPort()
            val player = MusicPlayerController(port, scope)
            port.publish(snapshot())
            yield()
            val queue = FakeQueue(player, listOf(occurrence, second))
            val adapter = QueueDevicePlaybackPort(queue)
            val captured = assertNotNull(adapter.capture())
            assertEquals(listOf("a", "b"), captured.entries.map { it.occurrenceId })
            assertEquals("a", captured.currentOccurrenceId)
            assertEquals(2, captured.entries.size)
            assertEquals(2L, captured.queueRevision)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun localFilesAndStaleCommandsAreRefused() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = FakePlaybackPort()
            val player = MusicPlayerController(port, scope)
            port.publish(snapshot())
            yield()
            val queue = FakeQueue(player, listOf(occurrence, second))
            val adapter = QueueDevicePlaybackPort(queue)
            val local = DevicePlaybackSnapshot(
                entries = listOf(DeviceQueueEntry("x", "local", "SONG", "local-1", "Local")),
                currentOccurrenceId = "x",
                positionMs = 0,
            )
            assertEquals(DeviceConnectErrorCode.UNSHAREABLE_QUEUE, adapter.apply(local)?.code)
            val stale = DeviceSyncMessage(
                DeviceSyncMessageType.COMMAND,
                sessionId = "s",
                command = DeviceRemoteCommand(DevicePlaybackAction.PLAY, expectedQueueRevision = 99),
            )
            assertEquals(DeviceConnectErrorCode.STALE_QUEUE, adapter.command(stale)?.code)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun commandsMapOntoThePlayerAndQueue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = FakePlaybackPort()
            val player = MusicPlayerController(port, scope)
            port.publish(snapshot())
            yield()
            val queue = FakeQueue(player, listOf(occurrence, second))
            val adapter = QueueDevicePlaybackPort(queue, repeatMode = { QueueRepeatMode.OFF })
            assertNull(
                adapter.command(
                    DeviceSyncMessage(
                        DeviceSyncMessageType.COMMAND,
                        sessionId = "s",
                        command = DeviceRemoteCommand(DevicePlaybackAction.NEXT),
                    ),
                ),
            )
            assertEquals("b", queue.state.value.currentOccurrenceId)
            assertNull(
                adapter.command(
                    DeviceSyncMessage(
                        DeviceSyncMessageType.COMMAND,
                        sessionId = "s",
                        command = DeviceRemoteCommand(DevicePlaybackAction.SEEK, positionMs = 1_500),
                    ),
                ),
            )
            assertEquals(1_500L, port.snapshots.value?.positionMs)
            val replacement = DeviceSyncMessage(
                DeviceSyncMessageType.COMMAND,
                sessionId = "s",
                command = DeviceRemoteCommand(
                    DevicePlaybackAction.REPLACE_QUEUE,
                    entries = listOf(second.toDeviceQueueEntry()),
                    occurrenceId = "b",
                    enabled = true,
                ),
            )
            assertNull(adapter.command(replacement))
            assertEquals(listOf("b"), queue.state.value.entries.map { it.occurrenceId })
        } finally {
            scope.cancel()
        }
    }

    private fun snapshot() = PlaybackSnapshot(
        occurrenceId = "a",
        mediaId = "video-a",
        playbackGeneration = 1,
        discontinuityGeneration = 0,
        positionMs = 0,
        sampleMonotonicNanos = 0,
        durationMs = 1_000,
        isSeekable = true,
        playbackSpeed = 1f,
        playWhenReady = true,
        isAudioAdvancing = true,
        isBuffering = false,
        outputRoute = PlaybackOutputRoute.LOCAL,
    )

    private class FakePlaybackPort : PlaybackPort {
        override val snapshots = MutableStateFlow<PlaybackSnapshot?>(null)
        override val failures: SharedFlow<PlaybackFailure> = MutableSharedFlow()
        private var generation = 1L

        override fun submit(command: PlaybackCommand) {
            when (command) {
                is PlaybackCommand.Load -> {
                    generation++
                    snapshots.value = snapshots.value?.copy(
                        occurrenceId = command.request.occurrenceId,
                        playbackGeneration = generation,
                        playWhenReady = command.playWhenReady,
                        positionMs = command.startPositionMs,
                    )
                }
                PlaybackCommand.Play -> snapshots.update { it?.copy(playWhenReady = true) }
                PlaybackCommand.Pause -> snapshots.update { it?.copy(playWhenReady = false, isAudioAdvancing = false) }
                is PlaybackCommand.SeekTo -> snapshots.update { current ->
                    if (current == null) null
                    else current.copy(
                        positionMs = command.positionMs,
                        discontinuityGeneration = current.discontinuityGeneration + 1,
                    )
                }
                is PlaybackCommand.SetSpeed -> snapshots.update { it?.copy(playbackSpeed = command.speed) }
                is PlaybackCommand.SetPitch -> Unit
                PlaybackCommand.Stop -> snapshots.value = null
            }
        }

        fun publish(value: PlaybackSnapshot) {
            snapshots.value = value
        }

        override fun close() = Unit
    }

    private class FakeQueue(
        override val player: MusicPlayerController,
        initial: List<ContentOccurrence>,
    ) : QueueController {
        override val state = MutableStateFlow(
            QueueState(
                revision = initial.size.toLong(),
                entries = initial,
                currentOccurrenceId = initial.firstOrNull()?.occurrenceId,
                playOrder = initial.map { it.occurrenceId },
            ),
        )

        override suspend fun replace(
            entries: List<ContentOccurrence>,
            startOccurrenceId: String,
            playWhenReady: Boolean,
            staysFinite: Boolean,
        ) {
            state.value = state.value.copy(
                revision = state.value.revision + 1,
                entries = entries,
                currentOccurrenceId = startOccurrenceId,
                playOrder = entries.map { it.occurrenceId },
                staysFinite = staysFinite,
            )
            player.load(
                moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest(
                    occurrenceId = startOccurrenceId,
                    mediaId = startOccurrenceId,
                    locator = moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator.Remote("https://example.test/$startOccurrenceId"),
                ),
                playWhenReady = playWhenReady,
            )
        }

        override suspend fun insert(entries: List<ContentOccurrence>, kind: UserQueuedKind): Int {
            state.value = state.value.copy(entries = state.value.entries + entries, revision = state.value.revision + 1)
            return entries.size
        }

        override suspend fun remove(occurrenceIds: Set<String>) {
            state.value = state.value.copy(
                entries = state.value.entries.filterNot { it.occurrenceId in occurrenceIds },
                revision = state.value.revision + 1,
            )
        }

        override suspend fun move(occurrenceId: String, beforeOccurrenceId: String?) = Unit

        override suspend fun select(occurrenceId: String, positionMs: Long) {
            state.value = state.value.copy(currentOccurrenceId = occurrenceId)
            player.seekTo(positionMs)
        }
    }
}
