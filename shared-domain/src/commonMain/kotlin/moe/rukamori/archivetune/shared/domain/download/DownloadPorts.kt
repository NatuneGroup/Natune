package moe.rukamori.archivetune.shared.domain.download

import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream

/**
 * One queued download, the portable form of the retained Media3 `Download` row. [id] is the opaque request id the
 * owner minted; [ref] names the song; [mediaId] is [ref]'s id kept flat for file naming. [resumeBytes] is the staged
 * byte count the next transfer continues from; it only ever moves forward for one task.
 */
data class DownloadTask(
    val id: String,
    val ref: ContentRef,
    val mediaId: String,
    val provider: String,
    val title: String,
    val source: AudioSourceType?,
    val quality: String,
    val phase: DownloadPhase,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val resumeBytes: Long,
    val failReason: String?,
    val updateTimeMs: Long,
)

/** The one shot the engine gets to move a task's bytes. Staging always belongs to one request. */
interface DownloadTransfer {
    /**
     * Streams [stream] into the task's staging area and commits it under the index, recording the item metadata
     * [song] carries. [resumeFromBytes] asks for an HTTP range continuation; a server that answers without honoring
     * it restarts the staging file, never appends. The implementation reports progress through [progress] (bytes
     * staged so far, total length when known) and must honor cancellation by leaving the staged bytes readable at
     * their reported count. The returned count is the committed file size.
     */
    suspend fun transfer(
        task: DownloadTask,
        stream: DirectStream,
        resumeFromBytes: Long,
        song: ContentItem?,
        progress: suspend (bytesDone: Long, totalBytes: Long?) -> Unit,
    ): Long
}

/**
 * Resolves the stream a download reads. The host binds the same provider chain playback uses ([OrderedAudioResolver]
 * over the enabled `AudioProviderRepository`s) gated by the user's download source order; the returned audio is always
 * a progressive HTTPS stream the transfer can read, because the downloader never runs a native recipe.
 */
interface DownloadSourceResolver {
    suspend fun resolve(task: DownloadTask): PreparedAudio?
}

/**
 * The persistent queue store, the portable form of the Media3 download index. Phases round-trip so a restarted
 * process resumes interrupted work exactly like the retained download service.
 */
interface DownloadTaskStore {
    suspend fun tasks(): List<DownloadTask>
    fun observeTasks(): Flow<List<DownloadTask>>
    suspend fun put(task: DownloadTask)
    suspend fun remove(taskId: String)
}

/**
 * Where the completed file of [task] lives for offline playback, or null when it is not a committed download.
 * Backed by the same index the file store reconciles, so a row never names a missing file.
 */
interface DownloadedFileLocator {
    suspend fun fileOf(task: DownloadTask): String?
}
