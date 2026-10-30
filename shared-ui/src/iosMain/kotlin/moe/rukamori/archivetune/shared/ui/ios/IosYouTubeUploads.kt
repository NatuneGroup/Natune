package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.platform.ios.IosFileUploadContent
import moe.rukamori.archivetune.shared.data.platform.ios.IosPickedAudioFile
import moe.rukamori.archivetune.shared.data.platform.ios.iosPickAudioFile
import moe.rukamori.archivetune.shared.data.provider.YouTubeDeleteResult
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadClient
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
import moe.rukamori.archivetune.shared.domain.settings.DataSyncIdKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeCookieKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.VisitorDataKey
import moe.rukamori.archivetune.shared.domain.settings.hasYouTubeSignInCookie
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.UploadTaskStage
import moe.rukamori.archivetune.shared.ui.screens.UploadTaskUi
import moe.rukamori.archivetune.shared.ui.screens.UploadsActions
import moe.rukamori.archivetune.shared.ui.screens.UploadsFailure
import moe.rukamori.archivetune.shared.ui.screens.UploadsUi
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore
import platform.Foundation.NSUUID

private const val PROCESSING_POLLS = 12
private const val PROCESSING_POLL_MS = 10_000L
private const val UPLOAD_CONNECT_TIMEOUT_MS = 20_000L
private const val UPLOAD_SOCKET_TIMEOUT_MS = 300_000L

/** Transfer rows that survive a shelf re-read: still running, or failed and awaiting the user's next step. */
private val KEPT_AFTER_REFRESH =
    setOf(UploadTaskStage.QUEUED, UploadTaskStage.UPLOADING, UploadTaskStage.PROCESSING, UploadTaskStage.FAILED)

/**
 * The `uploaded_songs` owner on iOS: the shelf, the transfer and the delete of the signed-in YouTube Music account,
 * over the shared upload client and the one settings sign-in, the same flow as the desktop owner. Work runs on [scope],
 * the graph's, because a transfer of up to 300 MiB outlives the page. The picked file is a private copy, deleted when
 * its transfer ends. A failed delete is surfaced, never reported as success.
 */
internal class IosYouTubeUploads(
    private val scope: CoroutineScope,
    private val settings: VaultSettingsStore,
    private val queue: QueueController,
    private val report: (String) -> Unit,
) {
    // A dedicated client: a transfer must not share a request timeout sized for catalogue reads.
    private val http = HttpClient(Darwin) {
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

    private val rowFlow = MutableStateFlow<List<ContentOccurrence>>(emptyList())
    val rows: StateFlow<List<ContentOccurrence>> = rowFlow.asStateFlow()
    private val taskFlow = MutableStateFlow<List<UploadTaskUi>>(emptyList())
    val tasks: StateFlow<List<UploadTaskUi>> = taskFlow.asStateFlow()
    private val loadingFlow = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = loadingFlow.asStateFlow()
    private val hasMoreFlow = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = hasMoreFlow.asStateFlow()
    private val failureFlow = MutableStateFlow<UploadsFailure?>(null)
    val failure: StateFlow<UploadsFailure?> = failureFlow.asStateFlow()

    /** Releases the dedicated transfer client. The graph calls it on close. */
    fun close() {
        http.close()
    }

    private fun uploadSession(): YouTubeUploadSession? = iosYouTubeUploadSession(settings.snapshots.value)

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
                rowFlow.value = emptyList()
                hasMoreFlow.value = false
                failureFlow.value = UploadsFailure.SIGN_IN
                return
            }
            if (!reset && continuation == null) return
            loadingFlow.value = true
            try {
                val page = client.browseUploads(if (reset) null else continuation)
                continuation = page.continuation
                hasMoreFlow.value = page.continuation != null
                // A duplicate key would crash the lazy list, so every page is de-duplicated before it is shown.
                songs = (if (reset) page.songs else songs + page.songs).distinctBy { it.videoId }
                rowFlow.value = songs.map(::occurrenceOf)
                failureFlow.value = null
                taskFlow.update { tasks -> tasks.filter { it.stage in KEPT_AFTER_REFRESH } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: YouTubeUploadException) {
                failureFlow.value = failureOf(failure.failure)
            } catch (failure: Exception) {
                report("the uploads were not read: ${failure::class.simpleName}")
                failureFlow.value = UploadsFailure.UNKNOWN
            } finally {
                loadingFlow.value = false
            }
        }
    }

    /** Opens the Files picker over [host] and uploads the picked file. A cancelled picker changes nothing. */
    fun pickAndUpload(host: IosHost) {
        scope.launch {
            val picked = try {
                iosPickAudioFile(host.viewController)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("the file chooser failed: ${failure::class.simpleName}")
                taskFlow.update { it + UploadTaskUi(newTaskId(), "", UploadTaskStage.FAILED, failure = UploadsFailure.READ) }
                null
            } ?: return@launch
            try {
                upload(picked)
            } finally {
                picked.delete()
            }
        }
    }

    private suspend fun upload(file: IosPickedAudioFile) {
        val id = newTaskId()
        val name = file.name
        fun fail(kind: UploadsFailure) {
            taskFlow.update { it + UploadTaskUi(id, name, UploadTaskStage.FAILED, failure = kind) }
        }
        if (uploadSession() == null) return fail(UploadsFailure.SIGN_IN)
        // The policy is checked before the file is opened or any request is sent.
        YouTubeUploadPolicy.validate(name, file.length)?.let { return fail(failureOf(it)) }
        val before = songs.mapTo(HashSet()) { it.videoId }
        val content = try {
            IosFileUploadContent(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return fail(UploadsFailure.READ)
        }
        taskFlow.update { it + UploadTaskUi(id, name, UploadTaskStage.UPLOADING, percent = 0) }
        val result = try {
            client.upload(fileName = name, content = content) { sent ->
                updateTask(id) { task -> task.copy(percent = (sent * 100L / file.length).toInt().coerceIn(0, 100)) }
            }
        } catch (cancelled: CancellationException) {
            removeTask(id)
            throw cancelled
        } catch (failure: Exception) {
            report("the upload failed: ${failure::class.simpleName}")
            updateTask(id) { it.copy(stage = UploadTaskStage.FAILED, failure = UploadsFailure.UNKNOWN) }
            null
        } finally {
            content.close()
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

    /** The service lists an upload only after processing; the shelf is polled as the Android worker polls it. */
    private suspend fun awaitProcessed(before: Set<String>): Boolean {
        repeat(PROCESSING_POLLS) {
            delay(PROCESSING_POLL_MS)
            val page = try {
                client.browseUploads(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
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
            val entityId = pageMutex.withLock { songs.firstOrNull { it.videoId == videoId }?.entityId }
            if (entityId == null) {
                failureFlow.value = UploadsFailure.DELETE
                return@launch
            }
            when (val result = client.delete(entityId)) {
                YouTubeDeleteResult.Deleted -> loadPage(reset = true)
                is YouTubeDeleteResult.Failed -> failureFlow.value =
                    if (result.failure == YouTubeUploadFailure.SIGN_IN_REQUIRED) UploadsFailure.SIGN_IN else UploadsFailure.DELETE
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
                report("the uploaded song was not played: ${failure::class.simpleName}")
            }
        }
    }

    private fun occurrenceOf(song: YouTubeUploadedSong): ContentOccurrence =
        ContentOccurrence(
            occurrenceId = "youtube-upload:${song.videoId}",
            item = ContentItem(
                ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, song.videoId),
                title = song.title,
                subtitle = song.artist.orEmpty(),
                artworkUri = song.thumbnailUrl,
                durationMs = song.durationSeconds?.let { it * 1_000L },
                album = song.album,
                artists = song.artist?.takeIf(String::isNotBlank)?.let { listOf(ContentCredit(name = it)) }.orEmpty(),
            ),
        )

    private fun newTaskId(): String = NSUUID().UUIDString

    private fun updateTask(id: String, transform: (UploadTaskUi) -> UploadTaskUi) {
        taskFlow.update { tasks -> tasks.map { if (it.id == id) transform(it) else it } }
    }

    private fun removeTask(id: String) {
        taskFlow.update { tasks -> tasks.filterNot { it.id == id } }
    }

    private fun failureOf(failure: YouTubeUploadFailure): UploadsFailure = when (failure) {
        YouTubeUploadFailure.SIGN_IN_REQUIRED -> UploadsFailure.SIGN_IN
        YouTubeUploadFailure.UNSUPPORTED_TYPE -> UploadsFailure.UNSUPPORTED_TYPE
        YouTubeUploadFailure.EMPTY_FILE -> UploadsFailure.EMPTY_FILE
        YouTubeUploadFailure.TOO_LARGE -> UploadsFailure.TOO_LARGE
        YouTubeUploadFailure.HTTP -> UploadsFailure.HTTP
        YouTubeUploadFailure.UNKNOWN -> UploadsFailure.UNKNOWN
    }
}

/** The YouTube Music account an upload request uses, or null when no signed-in cookie is stored. */
internal fun iosYouTubeUploadSession(snapshot: PreferenceSnapshot?): YouTubeUploadSession? {
    val values = snapshot ?: return null
    val cookie = values[InnerTubeCookieKey]?.takeIf { hasYouTubeSignInCookie(it) } ?: return null
    return YouTubeUploadSession(
        cookie = cookie,
        visitorData = values[VisitorDataKey]?.takeIf(String::isNotBlank),
        dataSyncId = values[DataSyncIdKey]?.takeIf(String::isNotBlank),
    )
}

/** The uploads shelf over [owner]. It re-reads on entry and when the sign-in changes. */
@Composable
internal fun iosUploadsPage(owner: IosYouTubeUploads, settings: VaultSettingsStore, host: IosHost): ArchiveGraphPage {
    val snapshot by settings.snapshots.collectAsState()
    val signedIn = iosYouTubeUploadSession(snapshot) != null
    val rows by owner.rows.collectAsState()
    val tasks by owner.tasks.collectAsState()
    val loading by owner.loading.collectAsState()
    val failure by owner.failure.collectAsState()
    val hasMore by owner.hasMore.collectAsState()
    LaunchedEffect(owner, signedIn) { owner.refresh() }
    return ArchiveGraphPage.Uploads(
        state = UploadsUi(
            rows = rows,
            tasks = tasks,
            available = true,
            signedIn = signedIn,
            loading = loading,
            failure = failure,
            hasMore = hasMore,
        ),
        actions = UploadsActions(
            back = {},
            upload = { owner.pickAndUpload(host) },
            delete = owner::delete,
            play = owner::play,
            retry = owner::refresh,
            loadMore = owner::loadMore,
        ),
    )
}
