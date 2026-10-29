package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.integration.DesktopFilePickers
import moe.rukamori.archivetune.desktop.provider.youTubeSignIn
import moe.rukamori.archivetune.shared.data.provider.YouTubeDeleteResult
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadClient
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadContent
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadException
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadFailure
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadPolicy
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadResult
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadSession
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadedSong
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.UploadTaskStage
import moe.rukamori.archivetune.shared.ui.screens.UploadTaskUi
import moe.rukamori.archivetune.shared.ui.screens.UploadsActions
import moe.rukamori.archivetune.shared.ui.screens.UploadsFailure
import moe.rukamori.archivetune.shared.ui.screens.UploadsUi
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val PROCESSING_POLLS = 12
private const val PROCESSING_POLL_MS = 10_000L
private const val UPLOAD_CONNECT_TIMEOUT_MS = 20_000L
private const val UPLOAD_SOCKET_TIMEOUT_MS = 300_000L

/** Transfer rows that survive a shelf re-read: still running, or failed and awaiting the user's next step. */
private val KEPT_AFTER_REFRESH =
    setOf(UploadTaskStage.QUEUED, UploadTaskStage.UPLOADING, UploadTaskStage.PROCESSING, UploadTaskStage.FAILED)

/**
 * The `uploaded_songs` owner on desktop: the shelf browse, the transfer and the delete of the signed-in
 * YouTube Music account, over the shared-data client and the one settings sign-in. Uploads run on [scope]
 * because the native picker and a 300 MiB transfer outlive any screen. A failed delete is surfaced, never
 * reported as success, and the list is re-read only after the service confirms a change.
 */
internal class DesktopYouTubeUploads(
    private val scope: CoroutineScope,
    private val settings: VaultSettingsStore,
    private val queue: QueueController,
    private val report: (String) -> Unit,
) {
    // A dedicated client: a transfer must not share a request timeout sized for catalogue reads.
    private val http =
        HttpClient(CIO) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = Long.MAX_VALUE
                connectTimeoutMillis = UPLOAD_CONNECT_TIMEOUT_MS
                socketTimeoutMillis = UPLOAD_SOCKET_TIMEOUT_MS
            }
        }
    private val client = YouTubeUploadClient(http, session = ::uploadSession)

    private val pageMutex = Mutex()
    private var continuation: String? = null
    private var songs: List<YouTubeUploadedSong> = emptyList()

    private val _rows = MutableStateFlow<List<ContentOccurrence>>(emptyList())
    val rows: StateFlow<List<ContentOccurrence>> = _rows.asStateFlow()

    private val _tasks = MutableStateFlow<List<UploadTaskUi>>(emptyList())
    val tasks: StateFlow<List<UploadTaskUi>> = _tasks.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _failure = MutableStateFlow<UploadsFailure?>(null)
    val failure: StateFlow<UploadsFailure?> = _failure.asStateFlow()

    private fun uploadSession(): YouTubeUploadSession? =
        settings.snapshots.value.youTubeSignIn()?.let { signIn ->
            YouTubeUploadSession(
                cookie = signIn.cookie,
                visitorData = signIn.visitorData,
                dataSyncId = signIn.dataSyncId,
            )
        }

    fun signedIn(): Boolean = uploadSession() != null

    fun refresh() {
        scope.launch { loadPage(reset = true) }
    }

    fun loadMore() {
        if (continuation == null) return
        scope.launch { loadPage(reset = false) }
    }

    private suspend fun loadPage(reset: Boolean) {
        pageMutex.withLock {
            if (uploadSession() == null) {
                continuation = null
                songs = emptyList()
                _rows.value = emptyList()
                _hasMore.value = false
                _failure.value = UploadsFailure.SIGN_IN
                return
            }
            if (!reset && continuation == null) return
            _loading.value = true
            try {
                val page = client.browseUploads(if (reset) null else continuation)
                continuation = page.continuation
                _hasMore.value = page.continuation != null
                // A duplicate key would crash the lazy list, so every page is de-duplicated before it is shown.
                songs = (if (reset) page.songs else songs + page.songs).distinctBy { it.videoId }
                _rows.value = songs.map(::occurrenceOf)
                _failure.value = null
                // Terminal success rows are cleared once the shelf was re-read; failures stay visible.
                _tasks.update { tasks -> tasks.filter { it.stage in KEPT_AFTER_REFRESH } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: YouTubeUploadException) {
                _failure.value = failureOf(failure.failure)
            } catch (failure: Exception) {
                report("the uploads were not read: ${failure.javaClass.simpleName}")
                _failure.value = UploadsFailure.UNKNOWN
            } finally {
                _loading.value = false
            }
        }
    }

    /** Opens the native chooser and uploads the picked file. A cancelled chooser changes nothing. */
    fun pickAndUpload() {
        scope.launch {
            val path =
                try {
                    DesktopFilePickers().use { pickers -> pickers.files(multiple = false).firstOrNull() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    report("the file chooser failed: ${failure.javaClass.simpleName}")
                    _tasks.update { it + failedTask(name = "", kind = UploadsFailure.READ) }
                    return@launch
                }
            if (path != null) upload(path)
        }
    }

    private suspend fun upload(path: Path) {
        val name = path.fileName?.toString().orEmpty()
        val id = UUID.randomUUID().toString()
        fun fail(kind: UploadsFailure) {
            _tasks.update { it + UploadTaskUi(id, name, UploadTaskStage.FAILED, failure = kind) }
        }
        if (uploadSession() == null) {
            fail(UploadsFailure.SIGN_IN)
            return
        }
        val length =
            try {
                Files.size(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(UploadsFailure.READ)
                return
            }
        // The policy is checked before the file is opened or any request is sent.
        YouTubeUploadPolicy.validate(name, length)?.let { rejection ->
            fail(failureOf(rejection))
            return
        }
        val before = songs.mapTo(HashSet()) { it.videoId }
        val input =
            try {
                Files.newInputStream(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fail(UploadsFailure.READ)
                return
            }
        val content =
            object : YouTubeUploadContent {
                override val length: Long = length

                override suspend fun read(buffer: ByteArray): Int =
                    withContext(Dispatchers.IO) { input.read(buffer) }
            }
        _tasks.update { it + UploadTaskUi(id, name, UploadTaskStage.UPLOADING, percent = 0) }
        val result =
            try {
                client.upload(fileName = name, content = content) { sent ->
                    updateTask(id) { task ->
                        task.copy(percent = (sent * 100L / length).toInt().coerceIn(0, 100))
                    }
                }
            } catch (cancelled: CancellationException) {
                removeTask(id)
                throw cancelled
            } catch (failure: Exception) {
                report("the upload failed: ${failure.javaClass.simpleName}")
                updateTask(id) { it.copy(stage = UploadTaskStage.FAILED, failure = UploadsFailure.UNKNOWN) }
                null
            } finally {
                runCatching { input.close() }
            }
        when (result) {
            null -> Unit
            YouTubeUploadResult.AlreadyUploaded -> {
                updateTask(id) { it.copy(stage = UploadTaskStage.ALREADY_THERE, percent = 100) }
                loadPage(reset = true)
            }

            YouTubeUploadResult.Uploaded -> {
                updateTask(id) { it.copy(stage = UploadTaskStage.PROCESSING, percent = 100) }
                if (awaitProcessed(before)) {
                    removeTask(id)
                    loadPage(reset = true)
                } else {
                    updateTask(id) { it.copy(stage = UploadTaskStage.ACCEPTED) }
                }
            }

            is YouTubeUploadResult.Failed ->
                updateTask(id) { it.copy(stage = UploadTaskStage.FAILED, failure = failureOf(result.failure)) }
        }
    }

    /** The service lists an upload only after processing; poll the shelf like the Android worker does. */
    private suspend fun awaitProcessed(before: Set<String>): Boolean {
        repeat(PROCESSING_POLLS) {
            delay(PROCESSING_POLL_MS)
            val page =
                try {
                    client.browseUploads(null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    null
                }
            if (page != null && page.songs.any { it.videoId !in before }) return true
        }
        return false
    }

    /** Deletes the upload behind [row]. Only a confirmed delete reloads the shelf. */
    fun delete(row: ContentOccurrence) {
        val videoId = row.item.ref.id
        scope.launch {
            val entityId = songs.firstOrNull { it.videoId == videoId }?.entityId
            if (entityId == null) {
                _failure.value = UploadsFailure.DELETE
                return@launch
            }
            when (val result = client.delete(entityId)) {
                YouTubeDeleteResult.Deleted -> loadPage(reset = true)
                is YouTubeDeleteResult.Failed ->
                    _failure.value =
                        if (result.failure == YouTubeUploadFailure.SIGN_IN_REQUIRED) {
                            UploadsFailure.SIGN_IN
                        } else {
                            UploadsFailure.DELETE
                        }
            }
        }
    }

    /** Plays [selected] inside the list the page shows, in shelf order. */
    fun play(selected: ContentOccurrence, visibleQueue: List<ContentOccurrence>) {
        scope.launch {
            try {
                queue.replace(visibleQueue, selected.occurrenceId, playWhenReady = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("the uploaded song was not played: ${failure.javaClass.simpleName}")
            }
        }
    }

    private fun occurrenceOf(song: YouTubeUploadedSong): ContentOccurrence =
        ContentOccurrence(
            occurrenceId = "youtube-upload:${song.videoId}",
            item =
                ContentItem(
                    ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, song.videoId),
                    title = song.title,
                    subtitle = song.artist.orEmpty(),
                    artworkUri = song.thumbnailUrl,
                    durationMs = song.durationSeconds?.let { it * 1_000L },
                    album = song.album,
                    artists = song.artist?.takeIf(String::isNotBlank)?.let { listOf(ContentCredit(name = it)) }.orEmpty(),
                ),
        )

    private fun failedTask(name: String, kind: UploadsFailure): UploadTaskUi =
        UploadTaskUi(UUID.randomUUID().toString(), name, UploadTaskStage.FAILED, failure = kind)

    private fun updateTask(id: String, transform: (UploadTaskUi) -> UploadTaskUi) {
        _tasks.update { tasks -> tasks.map { if (it.id == id) transform(it) else it } }
    }

    private fun removeTask(id: String) {
        _tasks.update { tasks -> tasks.filterNot { it.id == id } }
    }

    private fun failureOf(failure: YouTubeUploadFailure): UploadsFailure =
        when (failure) {
            YouTubeUploadFailure.SIGN_IN_REQUIRED -> UploadsFailure.SIGN_IN
            YouTubeUploadFailure.UNSUPPORTED_TYPE -> UploadsFailure.UNSUPPORTED_TYPE
            YouTubeUploadFailure.EMPTY_FILE -> UploadsFailure.EMPTY_FILE
            YouTubeUploadFailure.TOO_LARGE -> UploadsFailure.TOO_LARGE
            YouTubeUploadFailure.HTTP -> UploadsFailure.HTTP
            YouTubeUploadFailure.UNKNOWN -> UploadsFailure.UNKNOWN
        }
}

/** The uploads shelf bound to the session graph's owner. Re-reads on entry and on a sign-in change. */
@Composable
internal fun desktopUploadsPage(graph: DesktopSessionGraph): ArchiveGraphPage {
    val owner = graph.uploads
    val snapshot by graph.settings.snapshots.collectAsState()
    val signedIn = snapshot.youTubeSignIn() != null
    val rows by owner.rows.collectAsState()
    val tasks by owner.tasks.collectAsState()
    val loading by owner.loading.collectAsState()
    val failure by owner.failure.collectAsState()
    val hasMore by owner.hasMore.collectAsState()
    LaunchedEffect(owner, signedIn) { owner.refresh() }
    return ArchiveGraphPage.Uploads(
        state =
            UploadsUi(
                rows = rows,
                tasks = tasks,
                available = true,
                signedIn = signedIn,
                loading = loading,
                failure = failure,
                hasMore = hasMore,
            ),
        actions =
            UploadsActions(
                back = {},
                upload = owner::pickAndUpload,
                delete = owner::delete,
                play = owner::play,
                retry = owner::refresh,
                loadMore = owner::loadMore,
            ),
    )
}
