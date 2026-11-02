package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.download.DownloadQueueOwner
import moe.rukamori.archivetune.shared.data.download.IosDownloadFiles
import moe.rukamori.archivetune.shared.data.download.IosDownloadTransfer
import moe.rukamori.archivetune.shared.data.download.IosDownloadedFileLocator
import moe.rukamori.archivetune.shared.data.download.PortableDownloadTaskDao
import moe.rukamori.archivetune.shared.data.download.RoomDownloadTaskStore
import moe.rukamori.archivetune.shared.data.download.downloadTaskId
import moe.rukamori.archivetune.shared.data.persistence.PortableStoredSongDao
import moe.rukamori.archivetune.shared.data.persistence.PortableStoredSongRow
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.data.persistence.RoomStoredSongIndex
import moe.rukamori.archivetune.shared.data.persistence.storedOccurrence
import moe.rukamori.archivetune.shared.data.provider.AudioResolution
import moe.rukamori.archivetune.shared.data.provider.OrderedAudioResolver
import moe.rukamori.archivetune.shared.domain.download.DownloadSourceResolver
import moe.rukamori.archivetune.shared.domain.download.DownloadTask
import moe.rukamori.archivetune.shared.domain.repository.AudioPurpose
import moe.rukamori.archivetune.shared.domain.repository.ContentDownload
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadActions
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.host.HostDownloadsInputs
import moe.rukamori.archivetune.shared.ui.host.hostSourceSnapshot
import moe.rukamori.archivetune.shared.ui.host.toAudioResolveRequest
import moe.rukamori.archivetune.shared.ui.menu.CollectionDownloadState

/** The origin a committed download row carries, the `StoredSongOrigin.DOWNLOAD` schema value. */
private const val DOWNLOAD_ORIGIN = "DOWNLOAD"

/**
 * The quality a task stores when the caller names none. The stored row, its file name and every later removal are
 * keyed by it, so a blank quality can never commit a download that nothing can find again.
 */
private const val DEFAULT_DOWNLOAD_QUALITY = "default"

/**
 * The `downloads` owner on this host, implementing the domain [DownloadActions]. The transfer path is the portable
 * one: the occurrence's own resolve request with `purpose = DOWNLOAD` runs through the same [OrderedAudioResolver]
 * and source chain playback uses, and only a [PreparedAudio.Progressive] answer is stored. A source that answers with
 * an encrypted or native preparation is skipped for the next source in the chain, and a chain with none fails the
 * task rather than writing bytes the engine cannot play.
 *
 * The queue is the shared [DownloadQueueOwner]: each task is persisted in the portable database, so a restarted app
 * resumes an interrupted download, and progress, pause, resume and removal all read the same rows. A completed
 * download is listed in the stored-song index that the library, the downloads page and the offline locator read.
 */
internal class IosDownloadsOwner(
    private val tasks: PortableDownloadTaskDao,
    private val storedSongs: PortableStoredSongDao,
    private val library: RoomLibraryRepository,
    private val settings: SettingsStore,
    resolver: OrderedAudioResolver,
    root: String,
    client: HttpClient,
    scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val report: (String) -> Unit,
) : DownloadActions {

    /** The owner's own job, so [close] stops its transfers and waits for them without cancelling the graph's other work. */
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownerScope = CoroutineScope(scope.coroutineContext + ownerJob)
    private val files = IosDownloadFiles(root)
    private val index = RoomStoredSongIndex(storedSongs)
    private val locator = IosDownloadedFileLocator(files)
    /** The songs started from a catalog row this session, so an in-flight transfer shows its real metadata. */
    private val started = MutableStateFlow<List<ContentItem>>(emptyList())

    private val queue = DownloadQueueOwner(
        store = RoomDownloadTaskStore(tasks),
        transfer = IosDownloadTransfer(client, files, index, nowMs),
        resolver = IosDownloadResolver(
            audio = resolver,
            preferences = { settings.snapshots.value },
            itemOf = { ref -> metadataOf(ref) },
        ),
        itemOf = { ref -> metadataOf(ref) },
        removeCommitted = { task -> deleteCommitted(task) },
        timeMs = nowMs,
        scope = ownerScope,
    )

    override fun observe(): Flow<List<ContentDownload>> = queue.observe()

    /**
     * The page's one snapshot: the live transfers plus every song whose metadata this host knows, which is the
     * committed row's stored occurrence, else the catalog row that started the transfer this session. A row whose
     * stored occurrence cannot be read is skipped rather than shown with a made-up title.
     */
    fun inputs(): Flow<HostDownloadsInputs> = combine(storedSongs.observe(DOWNLOAD_ORIGIN), observe(), started) {
            rows, transfers, startedSongs ->
        val itemsByRef = LinkedHashMap<ContentRef, ContentItem>()
        startedSongs.forEach { itemsByRef[it.ref] = it }
        for (row in rows) {
            val item = runCatching { row.storedOccurrence().item }.getOrNull() ?: continue
            itemsByRef[item.ref] = item
        }
        HostDownloadsInputs(transfers, itemsByRef.values.toList())
    }

    /**
     * The transfer a song's menu starts. A catalog row already has canonical metadata, so downloading it must not
     * require saving it to the library first. A provider this host cannot store is refused here, before a task
     * exists, so the menu says why instead of failing after a resolution.
     */
    suspend fun start(item: ContentItem, quality: String = "") {
        iosDownloadRefusal(item.ref)?.let { throw DownloadRefused(it) }
        remember(item)
        queue.start(item.ref, quality.defaultQuality())
    }

    override suspend fun start(ref: ContentRef, quality: String) {
        iosDownloadRefusal(ref)?.let { throw DownloadRefused(it) }
        val item = metadataOf(ref) ?: throw IllegalStateException("The song metadata is not available")
        remember(item)
        queue.start(item.ref, quality.defaultQuality())
    }

    override suspend fun stop(id: String) = queue.stop(id)

    override suspend fun resume(id: String) = queue.resume(id)

    override suspend fun remove(id: String) = queue.remove(id)

    /** Re-arms the persisted queue and drops rows whose committed file no longer holds its bytes. */
    suspend fun start() {
        try {
            queue.open()
            reconcile()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("the download queue was not restored: ${failure::class.simpleName}")
        }
    }

    /**
     * The committed file of a listed download, for offline playback. A row is only answered while its file holds the
     * committed byte count, so a removed or truncated file falls back to ordinary source resolution.
     */
    suspend fun committedFile(ref: ContentRef): String? {
        if (ref.kind != ContentKind.SONG) return null
        val task = listedTask(ref) ?: return null
        return locator.fileOf(task)
    }

    /** The stored download rows the export page lists, in the index order the library reads. */
    fun storedRows(): Flow<List<PortableStoredSongRow>> = storedSongs.observe(DOWNLOAD_ORIGIN)

    /**
     * The committed file a stored row names, while it holds the bytes the row committed; the export page copies it
     * under a readable name. A row whose committed count is unknown answers while the file exists.
     */
    suspend fun fileOf(provider: String, mediaId: String, source: AudioSourceType, quality: String): String? {
        val path = files.destinationPath(provider, mediaId, source, quality)
        if (!files.exists(path)) return null
        val committed = storedSongs.find(DOWNLOAD_ORIGIN, provider, mediaId)?.bytes?.takeIf { it > 0L }
        return if (committed == null || files.sizeOf(path) == committed) path else null
    }

    /**
     * The storage page's Clear downloads: every queued, running and completed task loses its staged and committed
     * bytes through the queue's own removal, then any orphan row and the staging tree itself.
     */
    suspend fun clearAll() {
        queue.observe().first().forEach { task -> queue.remove(task.id) }
        storedSongs.rows(DOWNLOAD_ORIGIN).forEach { row ->
            val source = AudioSourceType.entries.firstOrNull { it.name == row.source }
            val quality = row.quality?.takeIf { it.isNotBlank() }
            if (source != null && quality != null) {
                files.removeCommitted(files.destinationPath(row.provider, row.mediaId, source, quality))
            }
            storedSongs.delete(DOWNLOAD_ORIGIN, row.provider, row.mediaId)
        }
        files.removeStagingRoot()
    }

    /** The download row of [ref] as a completed task, or null when the index lists no usable download of it. */
    private suspend fun listedTask(ref: ContentRef): DownloadTask? {
        val row = storedSongs.find(DOWNLOAD_ORIGIN, ref.provider, ref.id) ?: return null
        val source = row.source.let { name -> AudioSourceType.entries.firstOrNull { it.name == name } } ?: return null
        val quality = row.quality?.takeIf { it.isNotBlank() } ?: return null
        return DownloadTask(
            id = downloadTaskId(ref), ref = ref, mediaId = ref.id, provider = ref.provider, title = "",
            source = source, quality = quality, phase = DownloadPhase.COMPLETED,
            bytesDownloaded = row.bytes ?: 0L, totalBytes = row.bytes, resumeBytes = 0L,
            failReason = null, updateTimeMs = row.storedAtMs,
        )
    }

    /** The state the song menu shows for [ref], from the same live transfer list the page reads. */
    fun downloadStateOf(transfers: List<ContentDownload>, ref: ContentRef): CollectionDownloadState =
        when (transfers.firstOrNull { it.id == downloadTaskId(ref) && it.ref == ref }?.phase) {
            DownloadPhase.COMPLETED -> CollectionDownloadState.COMPLETED
            DownloadPhase.QUEUED, DownloadPhase.DOWNLOADING, DownloadPhase.REMOVING -> CollectionDownloadState.IN_PROGRESS
            DownloadPhase.STOPPED, DownloadPhase.FAILED, null -> CollectionDownloadState.NOT_DOWNLOADED
        }

    /** Drops one task's staged and committed files and its index row. The queue calls this only once it owns the task. */
    private suspend fun deleteCommitted(task: DownloadTask) {
        files.discard(task.id)
        val source = task.source
        if (source == null) {
            storedSongs.delete(DOWNLOAD_ORIGIN, task.provider, task.mediaId)
            return
        }
        files.removeCommitted(files.destinationPath(task, source))
        if (task.quality.isNotBlank()) index.downloadRemoved(task.provider, task.mediaId, source, task.quality)
    }

    /**
     * The startup reconciliation: a listed row that no running task owns and whose file is missing or does not hold
     * its committed bytes is dropped, so the library never offers a download that cannot play.
     */
    private suspend fun reconcile() {
        val active = queue.observe().first()
            .filter { it.phase != DownloadPhase.COMPLETED }
            .mapTo(HashSet()) { it.id }
        for (row in storedSongs.rows(DOWNLOAD_ORIGIN)) {
            val id = row.provider + ":" + row.mediaId
            if (id in active) continue
            val source = row.source.let { name -> AudioSourceType.entries.firstOrNull { it.name == name } }
            val quality = row.quality?.takeIf { it.isNotBlank() }
            val bytes = row.bytes?.takeIf { it > 0L }
            val path = if (source != null && quality != null) {
                files.destinationPath(row.provider, row.mediaId, source, quality)
            } else {
                null
            }
            val intact = path != null && bytes != null && files.exists(path) && files.sizeOf(path) == bytes
            if (intact) continue
            if (path != null) files.removeCommitted(path)
            // The completed queue row would otherwise still read as downloaded, so it goes with the listing.
            queue.remove(id)
            storedSongs.delete(DOWNLOAD_ORIGIN, row.provider, row.mediaId)
            report("a downloaded file was missing, so its listing was dropped: ${row.provider}")
        }
    }

    /** Runs one page command; a failure is reported and never escapes into the page. */
    fun run(action: suspend () -> Unit) {
        ownerScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("the download action failed: ${failure::class.simpleName}")
            }
        }
    }

    /**
     * Stops the queue's workers and waits for them to unwind, so no transfer or row write runs after the graph
     * closes the database. A row still transferring keeps its staged bytes and re-arms on the next start.
     */
    suspend fun close() = ownerJob.cancelAndJoin()

    /** Keeps a started song's metadata for the page while its transfer runs; the bounds keep the list tiny. */
    private fun remember(item: ContentItem) {
        started.value = (started.value.filterNot { it.ref == item.ref } + item).takeLast(STARTED_SONG_LIMIT)
    }

    /**
     * The metadata a transfer needs. A song this session started from a catalog row resolves from that row, so it
     * never has to be saved to the library first; any other song resolves from the library.
     */
    private suspend fun metadataOf(ref: ContentRef): ContentItem? =
        started.value.lastOrNull { it.ref == ref } ?: library.findSong(ref.id, ref.provider)

    private companion object {
        const val STARTED_SONG_LIMIT = 64
    }
}

/** The reason this host refuses a download before it queues one. */
internal enum class DownloadRefusal {
    /** The row is not a song, or its audio already belongs to another owner on this device. */
    NOT_A_SONG,

    /** Spotify streaming is not a download source anywhere, and this host has no web-player route for it. */
    STREAMING_ONLY,

    /** The source only hands out an encrypted stream or a native recipe this host cannot store as a file. */
    ENCRYPTED_ON_DEVICE,
}

/** The refusal [ref] earns on this host, or null when its provider can be stored. */
internal fun iosDownloadRefusal(ref: ContentRef): DownloadRefusal? = when {
    ref.kind != ContentKind.SONG -> DownloadRefusal.NOT_A_SONG
    ref.provider == ContentProvider.SPOTIFY_STREAMING.id -> DownloadRefusal.STREAMING_ONLY
    // Deezer answers only with its encrypted stream and Apple only with a FairPlay HLS manifest. Both need the
    // Android native preparers, so this host cannot write a playable file for either.
    ref.provider == ContentProvider.DEEZER.id || ref.provider == ContentProvider.APPLE.id ->
        DownloadRefusal.ENCRYPTED_ON_DEVICE
    // A local file is already on this device and Telegram audio stays in TDLib's own cache.
    ref.provider == ContentProvider.LOCAL.id || ref.provider == ContentProvider.TELEGRAM.id ->
        DownloadRefusal.NOT_A_SONG
    ref.id.startsWith("telegram://") -> DownloadRefusal.NOT_A_SONG
    else -> null
}

/** A refusal the song menu turns into the matching notice instead of a bare failure. */
internal class DownloadRefused(val refusal: DownloadRefusal) : IllegalStateException("download refused: $refusal")

private fun String.defaultQuality(): String = ifBlank { DEFAULT_DOWNLOAD_QUALITY }

/**
 * The download side of this host's provider chain. Each task resolves with [AudioPurpose.DOWNLOAD] under the user's
 * download source order, exactly like playback, and a source whose answer cannot be stored is skipped so a later
 * source can still answer. When nothing storable answers, the failure names how many attempts it made.
 */
private class IosDownloadResolver(
    private val audio: OrderedAudioResolver,
    private val preferences: () -> PreferenceSnapshot?,
    private val itemOf: suspend (ContentRef) -> ContentItem?,
) : DownloadSourceResolver {
    override suspend fun resolve(task: DownloadTask): PreparedAudio? {
        val item = itemOf(task.ref) ?: error("The song metadata is not available")
        val occurrence = ContentOccurrence("download:" + task.id, item)
        val request = occurrence.toAudioResolveRequest(preferences()).copy(purpose = AudioPurpose.DOWNLOAD)
        val skipped = linkedSetOf<AudioSourceType>()
        while (true) {
            val prepared = when (val resolution = audio.resolve(request, hostSourceSnapshot(preferences()), rejected = skipped)) {
                is AudioResolution.Resolved -> resolution.audio
                is AudioResolution.Unavailable ->
                    error("no connected source stored a file for ${task.mediaId} (${resolution.attempts.size} attempts)")
                is AudioResolution.NativeRoute -> error("A native route cannot be stored as a download file")
            }
            if (prepared is PreparedAudio.Progressive) return prepared
            skipped += prepared.source
        }
    }
}
