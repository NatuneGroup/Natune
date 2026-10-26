package moe.rukamori.archivetune.shared.data.download

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.shared.domain.download.DownloadTask
import moe.rukamori.archivetune.shared.domain.download.DownloadTaskStore
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

/** The persisted download queue row. Phase and the nullable columns round-trip [DownloadTask] one to one. */
@Entity(tableName = "portable_download_task")
data class PortableDownloadTaskRow(
    @PrimaryKey val id: String,
    val provider: String,
    val kind: String,
    val mediaId: String,
    val title: String,
    val source: String?,
    val quality: String,
    val phase: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val resumeBytes: Long,
    val failReason: String?,
    val updateTimeMs: Long,
)

@Dao
abstract class PortableDownloadTaskDao {
    @Query("SELECT * FROM portable_download_task ORDER BY updateTimeMs DESC")
    abstract suspend fun tasks(): List<PortableDownloadTaskRow>

    @Query("SELECT * FROM portable_download_task ORDER BY updateTimeMs DESC")
    abstract fun observeTasks(): Flow<List<PortableDownloadTaskRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(row: PortableDownloadTaskRow)

    @Query("DELETE FROM portable_download_task WHERE id = :id")
    abstract suspend fun remove(id: String)
}

class RoomDownloadTaskStore(private val dao: PortableDownloadTaskDao) : DownloadTaskStore {
    override suspend fun tasks(): List<DownloadTask> = dao.tasks().map { it.toTask() }
    override fun observeTasks(): Flow<List<DownloadTask>> =
        dao.observeTasks().map { rows -> rows.map { it.toTask() } }
    override suspend fun put(task: DownloadTask) = dao.put(task.toRow())
    override suspend fun remove(taskId: String) = dao.remove(taskId)
}

private fun DownloadTask.toRow() = PortableDownloadTaskRow(
    id = id, provider = ref.provider, kind = ref.kind.name, mediaId = mediaId, title = title,
    source = source?.name, quality = quality, phase = phase.name,
    bytesDownloaded = bytesDownloaded, totalBytes = totalBytes, resumeBytes = resumeBytes,
    failReason = failReason, updateTimeMs = updateTimeMs,
)

private fun PortableDownloadTaskRow.toTask() = DownloadTask(
    id = id, ref = ContentRef(provider, ContentKind.valueOf(kind), mediaId),
    mediaId = mediaId, provider = provider, title = title,
    source = source?.let { runCatching { AudioSourceType.valueOf(it) }.getOrNull() },
    quality = quality, phase = DownloadPhase.valueOf(phase),
    bytesDownloaded = bytesDownloaded, totalBytes = totalBytes, resumeBytes = resumeBytes,
    failReason = failReason, updateTimeMs = updateTimeMs,
)
