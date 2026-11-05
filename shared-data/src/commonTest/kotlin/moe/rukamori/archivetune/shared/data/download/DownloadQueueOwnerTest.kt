package moe.rukamori.archivetune.shared.data.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.download.DownloadSourceResolver
import moe.rukamori.archivetune.shared.domain.download.DownloadTask
import moe.rukamori.archivetune.shared.domain.download.DownloadTaskStore
import moe.rukamori.archivetune.shared.domain.download.DownloadTransfer
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import kotlin.test.*

class DownloadQueueOwnerTest {
    private val song = ContentItem(ContentRef("youtube", ContentKind.SONG, "abc"), "Song", "Artist")

    private val progressive = PreparedAudio.Progressive(
        DirectStream(
            uri = "https://media.example/song",
            mimeType = "audio/flac",
            codecs = "flac",
            contentLength = 20,
            label = "Tidal LOSSLESS",
            source = AudioSourceType.TIDAL,
        ),
    )

    @Test fun aStartRunsOneTransferAndCommitsTheCompletedSize() = runTest {
        val store = MemoryStore()
        val transfer = RecordingTransfer(size = 20)
        val owner = owner(store, transfer = transfer)
        owner.start(song.ref, "LOSSLESS")
        advanceUntilIdle()
        val task = store.state.value.single()
        assertEquals("youtube:abc", task.id)
        assertEquals(DownloadPhase.COMPLETED, task.phase)
        assertEquals(20L, task.totalBytes)
        assertEquals(20L, task.bytesDownloaded)
        assertEquals(AudioSourceType.TIDAL, task.source)
        assertEquals("LOSSLESS", transfer.calls.single().task.quality)
        assertEquals(listOf(10L), transfer.progressBytes)
    }

    @Test fun twoProvidersNamingOneMediaIdKeepTheirOwnTasks() = runTest {
        val store = MemoryStore()
        val owner = owner(store)
        owner.start(ContentRef("youtube", ContentKind.SONG, "same"), "")
        owner.start(ContentRef("tidal", ContentKind.SONG, "same"), "")
        advanceUntilIdle()
        assertEquals(setOf("youtube:same", "tidal:same"), store.state.value.map { it.id }.toSet())
        assertEquals(listOf("tidal", "youtube"), store.state.value.map { it.provider }.sorted())
    }

    @Test fun aSecondStartNeitherDuplicatesNorReRunsACompletedDownload() = runTest {
        val store = MemoryStore()
        val transfer = RecordingTransfer()
        val owner = owner(store, transfer = transfer)
        owner.start(song.ref, "")
        advanceUntilIdle()
        owner.start(song.ref, "")
        advanceUntilIdle()
        assertEquals(1, store.state.value.size)
        assertEquals(1, transfer.calls.size)
    }

    @Test fun aResolutionFailureLeavesTheReasonAndNotACompletedRow() = runTest {
        val store = MemoryStore()
        val owner = owner(
            store,
            resolver = StaticResolver(failure = IllegalStateException("no connected source resolved abc")),
        )
        owner.start(song.ref, "")
        advanceUntilIdle()
        val task = store.state.value.single()
        assertEquals(DownloadPhase.FAILED, task.phase)
        assertTrue(task.failReason.orEmpty().contains("no connected source resolved abc"))
    }

    @Test fun aNonProgressivePreparationFailsInsteadOfStoringBytes() = runTest {
        val store = MemoryStore()
        val encrypted = PreparedAudio.DeezerEncrypted(
            DirectStream(
                uri = "https://media.example/encrypted",
                mimeType = "audio/mpeg",
                codecs = "mp3",
                contentLength = 20,
                label = "Deezer",
                source = AudioSourceType.DEEZER,
            ),
            trackId = "1",
            keyScope = "scope",
        )
        val owner = owner(store, resolver = StaticResolver(encrypted))
        owner.start(song.ref, "")
        advanceUntilIdle()
        val task = store.state.value.single()
        assertEquals(DownloadPhase.FAILED, task.phase)
        assertTrue(task.failReason.orEmpty().contains("progressive"))
    }

    @Test fun stopCancelsTheWorkerAndResumeQueuesItAgain() = runTest {
        val store = MemoryStore()
        val gate = CompletableDeferred<Unit>()
        val transfer = RecordingTransfer(size = 20, gate = gate)
        val owner = owner(store, transfer = transfer)
        owner.start(song.ref, "")
        advanceUntilIdle()
        assertEquals(DownloadPhase.DOWNLOADING, store.state.value.single().phase)
        owner.stop("youtube:abc")
        advanceUntilIdle()
        assertEquals(DownloadPhase.STOPPED, store.state.value.single().phase)
        gate.complete(Unit)
        owner.resume("youtube:abc")
        advanceUntilIdle()
        assertEquals(DownloadPhase.COMPLETED, store.state.value.single().phase)
        assertEquals(2, transfer.calls.size)
    }

    @Test fun aProgressDeliveredAfterStopKeepsTheRowStopped() = runTest {
        val store = MemoryStore()
        val release = CompletableDeferred<Unit>()
        val owner = owner(store, transfer = LateProgressTransfer(release))
        owner.start(song.ref, "")
        advanceUntilIdle()
        assertEquals(DownloadPhase.DOWNLOADING, store.state.value.single().phase)
        owner.stop("youtube:abc")
        advanceUntilIdle()
        release.complete(Unit)
        advanceUntilIdle()
        val task = store.state.value.single()
        assertEquals(DownloadPhase.STOPPED, task.phase)
        assertEquals(0L, task.bytesDownloaded)
    }

    @Test fun aProgressDeliveredAfterRemoveDoesNotResurrectTheRow() = runTest {
        val store = MemoryStore()
        val removed = mutableListOf<DownloadTask>()
        val release = CompletableDeferred<Unit>()
        val owner = owner(store, transfer = LateProgressTransfer(release), removeCommitted = { removed += it })
        owner.start(song.ref, "")
        advanceUntilIdle()
        owner.remove("youtube:abc")
        advanceUntilIdle()
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(store.state.value.isEmpty())
        assertEquals(listOf(DownloadPhase.REMOVING), removed.map { it.phase })
    }

    @Test fun removeDropsTheCommittedFileAndItsRow() = runTest {
        val store = MemoryStore()
        val removed = mutableListOf<DownloadTask>()
        val owner = owner(store, removeCommitted = { removed += it })
        owner.start(song.ref, "")
        advanceUntilIdle()
        owner.remove("youtube:abc")
        advanceUntilIdle()
        assertTrue(store.state.value.isEmpty())
        assertEquals(listOf(DownloadPhase.REMOVING), removed.map { it.phase })
    }

    @Test fun openReArmsTheRowsAFinishedProcessLeftBehind() = runTest {
        val pending = listOf(
            task(phase = DownloadPhase.QUEUED, provider = "youtube", mediaId = "queued"),
            task(phase = DownloadPhase.DOWNLOADING, provider = "tidal", mediaId = "running"),
            task(phase = DownloadPhase.COMPLETED, provider = "qobuz", mediaId = "done"),
        )
        val store = MemoryStore(pending)
        val transfer = RecordingTransfer()
        val owner = owner(store, transfer = transfer)
        owner.open()
        advanceUntilIdle()
        assertEquals(DownloadPhase.COMPLETED, store.state.value.single { it.mediaId == "queued" }.phase)
        assertEquals(DownloadPhase.COMPLETED, store.state.value.single { it.mediaId == "running" }.phase)
        assertEquals(DownloadPhase.COMPLETED, store.state.value.single { it.mediaId == "done" }.phase)
        assertEquals(2, transfer.calls.size)
    }

    private fun TestScope.owner(
        store: DownloadTaskStore,
        transfer: DownloadTransfer = RecordingTransfer(),
        resolver: DownloadSourceResolver = StaticResolver(progressive),
        removeCommitted: suspend (DownloadTask) -> Unit = {},
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ) = DownloadQueueOwner(
        store = store,
        transfer = transfer,
        resolver = resolver,
        itemOf = { song },
        removeCommitted = removeCommitted,
        timeMs = { 1_000L },
        scope = backgroundScope,
        dispatcher = dispatcher,
    )

    private fun task(phase: DownloadPhase, provider: String, mediaId: String) = DownloadTask(
        id = "$provider:$mediaId",
        ref = ContentRef(provider, ContentKind.SONG, mediaId),
        mediaId = mediaId,
        provider = provider,
        title = mediaId,
        source = AudioSourceType.TIDAL,
        quality = "LOSSLESS",
        phase = phase,
        bytesDownloaded = 0L,
        totalBytes = null,
        resumeBytes = 0L,
        failReason = null,
        updateTimeMs = 1L,
    )

    private class MemoryStore(initial: List<DownloadTask> = emptyList()) : DownloadTaskStore {
        val state = MutableStateFlow(initial)
        override suspend fun tasks(): List<DownloadTask> = state.value
        override fun observeTasks(): Flow<List<DownloadTask>> = state
        override suspend fun put(task: DownloadTask) {
            state.value = state.value.filterNot { it.id == task.id } + task
        }
        override suspend fun remove(taskId: String) {
            state.value = state.value.filterNot { it.id == taskId }
        }
    }

    private class RecordingTransfer(
        private val size: Long = 20,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : DownloadTransfer {
        val calls = mutableListOf<Call>()
        val progressBytes = mutableListOf<Long>()

        class Call(val task: DownloadTask)

        override suspend fun transfer(
            task: DownloadTask,
            stream: DirectStream,
            resumeFromBytes: Long,
            song: ContentItem?,
            progress: suspend (bytesDone: Long, totalBytes: Long?) -> Unit,
        ): Long {
            calls += Call(task)
            gate?.await()
            progress(size / 2, size)
            progressBytes += size / 2
            return size
        }
    }

    private class LateProgressTransfer(private val release: CompletableDeferred<Unit>) : DownloadTransfer {
        override suspend fun transfer(
            task: DownloadTask,
            stream: DirectStream,
            resumeFromBytes: Long,
            song: ContentItem?,
            progress: suspend (bytesDone: Long, totalBytes: Long?) -> Unit,
        ): Long {
            // A real transfer can deliver its last progress from an uncancellable cleanup block.
            withContext(NonCancellable) {
                release.await()
                progress(10L, 20L)
            }
            return 20L
        }
    }

    private class StaticResolver(
        private val audio: PreparedAudio? = null,
        private val failure: Exception? = null,
    ) : DownloadSourceResolver {
        override suspend fun resolve(task: DownloadTask): PreparedAudio? {
            failure?.let { throw it }
            return audio
        }
    }
}
