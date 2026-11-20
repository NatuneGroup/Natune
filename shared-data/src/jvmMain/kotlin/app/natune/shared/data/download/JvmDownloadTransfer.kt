package app.natune.shared.data.download

import app.natune.shared.domain.download.DownloadTask
import app.natune.shared.domain.download.DownloadTransfer
import app.natune.shared.domain.download.DownloadedFileLocator
import app.natune.shared.domain.repository.ContentItem
import app.natune.shared.domain.source.AudioSourceType
import app.natune.shared.domain.source.DirectStream

/** The desktop transfer over [JvmDownloadRepository]'s resumable staging store. */
class JvmDownloadTransfer(
    private val repository: JvmDownloadRepository,
) : DownloadTransfer {
    override suspend fun transfer(
        task: DownloadTask,
        stream: DirectStream,
        resumeFromBytes: Long,
        song: ContentItem?,
        progress: suspend (bytesDone: Long, totalBytes: Long?) -> Unit,
    ): Long {
        val key = DownloadKey(task.mediaId, stream.source, task.quality, task.ref.provider)
        return repository.transfer(key, task.id, stream, song, resumeFromBytes, progress)
    }

    /** Removes both the committed file and the task's staged bytes. */
    suspend fun discard(task: DownloadTask, source: AudioSourceType) {
        repository.discardStaging(task.id)
        repository.remove(DownloadKey(task.mediaId, source, task.quality, task.ref.provider))
    }
}

/** Offline playback lookup: the committed file of a finished download. */
class JvmDownloadedFileLocator(private val repository: JvmDownloadRepository) : DownloadedFileLocator {
    override suspend fun fileOf(task: DownloadTask): String? {
        val source = task.source ?: return null
        val key = DownloadKey(task.mediaId, source, task.quality, task.ref.provider)
        return repository.find(key)?.toAbsolutePath()?.toString()
    }
}
