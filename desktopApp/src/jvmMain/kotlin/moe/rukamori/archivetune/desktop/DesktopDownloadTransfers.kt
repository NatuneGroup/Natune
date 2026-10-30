package moe.rukamori.archivetune.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase

internal data class DesktopDownloadTransfer(
    val ref: ContentRef,
    val item: ContentItem?,
    val quality: String,
    val phase: DownloadPhase,
    val job: Job?,
    val generation: Any,
)

/** Serializes menu commands, while transfer completion publishes only to the request that still owns the row. */
internal class DesktopDownloadTransfers(
    private val scope: CoroutineScope,
    private val transfer: suspend (ContentItem, String) -> Unit,
    private val report: (String) -> Unit,
) {
    private val commands = Mutex()
    private val jobs = MutableStateFlow<Map<String, DesktopDownloadTransfer>>(emptyMap())
    val state = jobs.asStateFlow()

    suspend fun start(item: ContentItem, quality: String) {
        commands.withLock { startLocked(item, quality) }
    }

    suspend fun stop(id: String) {
        commands.withLock {
            val current = jobs.value[id] ?: return@withLock
            current.job?.cancelAndJoin()
            finish(id, current.generation, DownloadPhase.STOPPED)
        }
    }

    suspend fun resume(id: String) {
        commands.withLock {
            val current = jobs.value[id] ?: return@withLock
            current.item?.let { startLocked(it, current.quality) }
        }
    }

    suspend fun remove(ref: ContentRef, removeStored: suspend () -> Unit) {
        commands.withLock {
            val id = ref.desktopDownloadId()
            val current = jobs.value[id]
            // A cancelled request must finish its cleanup before removal drops the listing and bytes.
            current?.job?.cancelAndJoin()
            val removing = DesktopDownloadTransfer(
                ref, current?.item, current?.quality ?: "", DownloadPhase.REMOVING, null, Any(),
            )
            jobs.update { it + (id to removing) }
            try {
                removeStored()
            } finally {
                jobs.update { it - id }
            }
        }
    }

    private fun startLocked(item: ContentItem, quality: String) {
        val id = item.ref.desktopDownloadId()
        if (jobs.value[id]?.phase in setOf(DownloadPhase.QUEUED, DownloadPhase.DOWNLOADING, DownloadPhase.REMOVING)) return
        val generation = Any()
        // Publish the job before it can finish, including on an undispatched host/test scope.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            finish(id, generation, DownloadPhase.DOWNLOADING, ended = false)
            try {
                transfer(item, quality)
                finish(id, generation, DownloadPhase.COMPLETED)
            } catch (cancelled: CancellationException) {
                finish(id, generation, DownloadPhase.STOPPED)
                throw cancelled
            } catch (failure: Exception) {
                finish(id, generation, DownloadPhase.FAILED)
                report("the download failed: ${failure.javaClass.simpleName}")
            }
        }
        jobs.update { it + (id to DesktopDownloadTransfer(item.ref, item, quality, DownloadPhase.QUEUED, job, generation)) }
        job.start()
    }

    private fun finish(id: String, generation: Any, phase: DownloadPhase, ended: Boolean = true) {
        jobs.update { current ->
            val owned = current[id]
            if (owned == null || owned.generation !== generation) current
            else current + (id to owned.copy(phase = phase, job = if (ended) null else owned.job))
        }
    }
}
