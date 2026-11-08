package moe.rukamori.archivetune.shared.data.download

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.download.DownloadSourceResolver
import moe.rukamori.archivetune.shared.domain.download.DownloadTask
import moe.rukamori.archivetune.shared.domain.download.DownloadTaskStore
import moe.rukamori.archivetune.shared.domain.download.PreparedDownloadTransfer
import moe.rukamori.archivetune.shared.domain.download.DownloadTransfer
import moe.rukamori.archivetune.shared.domain.repository.ContentDownload
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadActions
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import kotlin.coroutines.cancellation.CancellationException

/**
 * The queueing download engine shared by desktop and iOS, the portable form of the retained
 * `DownloadRepository`/`DownloadUtil` pair. Media3 kept one persisted queue drained by
 * `DownloadService` with per-request coroutines; this owner keeps the identical behavior on the portable
 * [DownloadTask] rows: `start` enqueues (a failed or stopped entry for the same song re-arms rather than
 * duplicating), `stop` marks STOPPED and cancels the worker the way `setStopReason` did, `resume` re-queues,
 * `remove` enters REMOVING and deletes the committed file plus its row. At most [maxParallel] transfers run at
 * once, matching the retained three-worker executor. A task is keyed by its provider and media id, because two
 * providers can name one media id. [dispatcher] runs the workers and the persisted queue collector.
 */
class DownloadQueueOwner(
    private val store: DownloadTaskStore,
    private val transfer: DownloadTransfer,
    private val resolver: DownloadSourceResolver,
    private val itemOf: suspend (ContentRef) -> ContentItem?,
    private val removeCommitted: suspend (DownloadTask) -> Unit,
    private val timeMs: () -> Long,
    scope: CoroutineScope,
    private val maxParallel: Int = 3,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : DownloadActions {

    private val ownerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val queueGate = Mutex()
    private val workers = mutableMapOf<String, Job>()
    private val queued = LinkedHashMap<String, DownloadTask>()

    private var snapshot: Map<String, DownloadTask> = emptyMap()

    /** Starts the drain loop; persisted QUEUED/DOWNLOADING rows re-arm like a restarted DownloadService. */
    suspend fun open() = queueGate.withLock {
        snapshot = store.tasks().associateBy { it.id }
        for (task in snapshot.values) {
            when (task.phase) {
                DownloadPhase.QUEUED -> enqueue(task)
                DownloadPhase.DOWNLOADING -> enqueue(task.copy(phase = DownloadPhase.QUEUED))
                DownloadPhase.REMOVING -> enqueue(task)
                else -> Unit
            }
        }
        ownerScope.launch(dispatcher) {
            store.observeTasks().collect {
                queueGate.withLock { syncSnapshotLocked() }
            }
        }
    }

    override fun observe(): Flow<List<ContentDownload>> = store.observeTasks().map { rows ->
        rows.map { it.toContentDownload() }
    }

    override suspend fun start(ref: ContentRef, quality: String) {
        val id = downloadTaskId(ref)
        queueGate.withLock {
            val current = snapshot[id]
            when {
                current != null && current.phase == DownloadPhase.COMPLETED -> return
                current != null && (current.phase == DownloadPhase.QUEUED || current.phase == DownloadPhase.DOWNLOADING) -> return
                current != null && current.phase == DownloadPhase.REMOVING -> return
            }
            val item = itemOf(ref)
            val task = DownloadTask(
                id = id, ref = ref, mediaId = ref.id, provider = ref.provider,
                title = item?.title ?: current?.title ?: ref.id,
                source = null, quality = quality, phase = DownloadPhase.QUEUED,
                bytesDownloaded = current?.bytesDownloaded ?: 0L,
                totalBytes = current?.totalBytes,
                resumeBytes = current?.resumeBytes ?: 0L,
                failReason = null, updateTimeMs = timeMs(),
            )
            putLocked(task); enqueue(task)
        }
    }

    override suspend fun stop(id: String) = queueGate.withLock {
        val task = snapshot[id] ?: queued[id] ?: return@withLock
        queued.remove(id)
        workers[id]?.cancel()
        val stopped = task.copy(phase = DownloadPhase.STOPPED, updateTimeMs = timeMs())
        putLocked(stopped)
    }

    override suspend fun resume(id: String) = queueGate.withLock {
        val task = snapshot[id] ?: return@withLock
        if (task.phase != DownloadPhase.STOPPED && task.phase != DownloadPhase.FAILED) return@withLock
        val next = task.copy(phase = DownloadPhase.QUEUED, failReason = null, updateTimeMs = timeMs())
        putLocked(next); enqueue(next)
    }

    override suspend fun remove(id: String) = queueGate.withLock {
        val task = snapshot[id] ?: queued[id] ?: return@withLock
        queued.remove(id)
        workers[id]?.cancel()
        val removing = task.copy(phase = DownloadPhase.REMOVING, updateTimeMs = timeMs())
        putLocked(removing)
        try {
            removeCommitted(removing)
        } finally {
            store.remove(id)
            snapshot = snapshot - id
        }
    }

    private fun enqueue(task: DownloadTask) {
        if (workers[task.id]?.isActive == true || queued.containsKey(task.id)) return
        queued[task.id] = task
        pump()
    }

    /**
     * Moves queued rows into free worker slots; serialized callers keep exactly [maxParallel] in flight. A
     * cancelled worker keeps its slot until `runTask`'s finally drops the entry: freeing it in [stop]/[remove]
     * let a following start run [maxParallel] + 1 transfers until the cancelled coroutine unwound.
     */
    private fun pump() {
        val free = maxParallel - workers.size
        if (free <= 0) return
        val batch = queued.values
            .filter { (it.phase == DownloadPhase.QUEUED || it.phase == DownloadPhase.REMOVING) && !workers.containsKey(it.id) }
            .take(free)
        for (task in batch) {
            queued.remove(task.id)
            // LAZY so the slot is recorded before a worker can finish and remove itself.
            val job = ownerScope.launch(dispatcher, start = CoroutineStart.LAZY) { runTask(task) }
            workers[task.id] = job
            job.start()
        }
    }

    private suspend fun runTask(task: DownloadTask) {
        try {
            if (task.phase == DownloadPhase.REMOVING) {
                removeCommitted(task); store.remove(task.id); return
            }
            val downloading = claimDownloading(task) ?: return
            val prepared = resolver.resolve(downloading)
            requireNotNull(prepared) { "Source did not resolve audio" }
            if (!updateWhileDownloading(downloading.id) { it.copy(source = prepared.source) }) return
            val progress: suspend (Long, Long?) -> Unit = { done, total ->
                updateWhileDownloading(downloading.id) {
                    it.copy(bytesDownloaded = done, totalBytes = total ?: it.totalBytes,
                        resumeBytes = done, updateTimeMs = timeMs())
                }
                Unit
            }
            val size = if (transfer is PreparedDownloadTransfer) {
                transfer.transferPrepared(downloading, prepared, downloading.resumeBytes, itemOf(downloading.ref), progress)
            } else {
                val stream = (prepared as? PreparedAudio.Progressive)?.stream
                    ?: error("Source did not resolve to a progressive stream")
                transfer.transfer(downloading, stream, downloading.resumeBytes, itemOf(downloading.ref), progress)
            }
            updateWhileDownloading(downloading.id) {
                it.copy(phase = DownloadPhase.COMPLETED, bytesDownloaded = size, totalBytes = size,
                    updateTimeMs = timeMs())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            updateWhileDownloading(task.id) {
                it.copy(phase = DownloadPhase.FAILED, failReason = failure.message ?: "download failed",
                    updateTimeMs = timeMs())
            }
        } finally {
            (transfer as? PreparedDownloadTransfer)?.releasePreparation(task.id)
            // A cancelled worker still has to release its slot: stop()/remove() hold this gate while
            // they cancel, and a plain withLock in a cancelled coroutine would throw before removing
            // the entry, leaving every later resume queued behind a worker that no longer exists.
            withContext(NonCancellable) {
                queueGate.withLock {
                    workers.remove(task.id)
                    syncSnapshotLocked()
                    pump()
                }
            }
        }
    }

    private suspend fun syncSnapshotLocked() {
        val activeIds = workers.keys
        snapshot = store.tasks().associateBy { it.id } + snapshot.filterKeys { it in activeIds }
    }

    /** Persists the DOWNLOADING row for a queued task, unless a stop or remove already made the phase terminal. */
    private suspend fun claimDownloading(task: DownloadTask): DownloadTask? = queueGate.withLock {
        val live = snapshot[task.id] ?: return@withLock null
        // A re-armed row can still carry the DOWNLOADING phase a killed process persisted.
        if (live.phase != DownloadPhase.QUEUED && live.phase != DownloadPhase.DOWNLOADING) return@withLock null
        val next = live.copy(phase = DownloadPhase.DOWNLOADING, updateTimeMs = timeMs())
        store.put(next)
        snapshot = snapshot + (task.id to next)
        next
    }

    /**
     * A worker write only lands while the row is still this worker's DOWNLOADING entry. The progress and
     * completion callbacks run in the transfer coroutine, so a [stop] or [remove] can win the gate between the
     * transfer's last suspension and this write; writing anyway would resurrect a stopped download as
     * DOWNLOADING with no worker behind it, or re-create a removed row whose file is already gone.
     */
    private suspend fun updateWhileDownloading(id: String, update: (DownloadTask) -> DownloadTask): Boolean =
        queueGate.withLock {
            val live = snapshot[id] ?: return@withLock false
            if (live.phase != DownloadPhase.DOWNLOADING) return@withLock false
            val next = update(live)
            store.put(next)
            snapshot = snapshot + (id to next)
            true
        }

    /** The same write for callers serialized inside [queueGate]; the mutex is not reentrant. */
    private suspend fun putLocked(task: DownloadTask) {
        store.put(task)
        snapshot = snapshot + (task.id to task)
    }

    private fun DownloadTask.toContentDownload() = ContentDownload(
        id = id, ref = ref, phase = phase, bytesDownloaded = bytesDownloaded, totalBytes = totalBytes)
}

/**
 * The id of one download task: its provider and media id. The queue, its persisted rows and every page address a
 * download by this id, so two providers that name one media id never share an entry or a file.
 */
fun downloadTaskId(ref: ContentRef): String = ref.provider + ":" + ref.id

/** One measured byte rate, the retained ByteSample: a fresh sample overwrites one older than the hold window. */
internal class DownloadSpeedSampler(private val holdMs: Long = SPEED_SAMPLE_HOLD_MS, private val timeMs: () -> Long) {
    private var lastSample: Long = 0L
    private var lastTime: Long = 0L
    private var lastBytes: Long = 0L

    fun sample(totalBytes: Long): Long {
        val now = timeMs()
        if (now - lastTime >= holdMs) {
            val dt = (now - lastTime).coerceAtLeast(1L) / 1000.0
            lastSample = if (lastTime == 0L) 0L else ((totalBytes - lastBytes) / dt).toLong().coerceAtLeast(0L)
            lastTime = now; lastBytes = totalBytes
        }
        return lastSample
    }

    companion object { const val SPEED_SAMPLE_HOLD_MS = 2500L }
}
