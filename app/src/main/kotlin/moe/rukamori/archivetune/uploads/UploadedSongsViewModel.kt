package moe.rukamori.archivetune.uploads

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.shared.data.provider.YouTubeDeleteResult
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadException
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadFailure
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadPolicy
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadedSong
import java.util.UUID
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** An upload that has not finished, as the Uploads screen lists it. [percent] is null before bytes are sent. */
data class YouTubeUploadTask(
    val id: UUID,
    val name: String,
    val stage: YouTubeUploadStage,
    val percent: Int?,
)

/** A one-off status line. [arg] fills the placeholder of the string when it has one. */
data class YouTubeUploadMessage(
    val textRes: Int,
    val arg: String? = null,
)

@HiltViewModel
class UploadedSongsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploads: YouTubeUploadsRepository,
) : ViewModel() {
    private val pageMutex = Mutex()
    private var continuation: String? = null

    /** Uploads this screen watched start, so only their completion is announced, never old finished work. */
    private val watchedWork = mutableSetOf<UUID>()

    private val _songs = MutableStateFlow<List<YouTubeUploadedSong>>(emptyList())
    val songs: StateFlow<List<YouTubeUploadedSong>> = _songs.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _failure = MutableStateFlow<YouTubeUploadFailure?>(null)
    val failure: StateFlow<YouTubeUploadFailure?> = _failure.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _messages = MutableSharedFlow<YouTubeUploadMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<YouTubeUploadMessage> = _messages.asSharedFlow()

    val tasks: StateFlow<List<YouTubeUploadTask>> =
        YouTubeUploadScheduler
            .observe(context)
            .onEach { infos -> onWorkChanged(infos) }
            .map { infos -> infos.mapNotNull { info -> taskOf(info) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val signedIn: Boolean
        get() = uploads.signedIn()

    init {
        refresh()
    }

    /** Reloads the first page, after a delete or a finished upload. */
    fun refresh() {
        viewModelScope.launch { loadPage(reset = true) }
    }

    fun loadMore() {
        if (continuation == null) return
        viewModelScope.launch { loadPage(reset = false) }
    }

    /**
     * Picks up a document from the Storage Access Framework picker. Unreadable, rejected and signed-out
     * files are reported here before any request is sent.
     */
    fun upload(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val info = runCatching { describeYouTubeUploadDocument(context, uri) }.getOrNull()
            if (info == null || info.length < 0L) {
                post(R.string.youtube_upload_read_failed)
                return@launch
            }
            YouTubeUploadPolicy.validate(info.fileName, info.length)?.let { rejection ->
                post(rejection.messageRes())
                return@launch
            }
            if (!uploads.signedIn()) {
                post(R.string.youtube_upload_error_sign_in)
                return@launch
            }
            // Keeps read access for the background worker, which may start after this screen is gone.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            YouTubeUploadScheduler.enqueue(context, uri, info.fileName)
            post(R.string.uploads_started, info.fileName)
        }
    }

    /** Deletes one upload. The list is reloaded only after the service confirms the delete. */
    fun delete(song: YouTubeUploadedSong) {
        val entityId = song.entityId
        if (entityId == null) {
            post(R.string.youtube_delete_failed)
            return
        }
        viewModelScope.launch {
            when (val result = uploads.delete(entityId)) {
                YouTubeDeleteResult.Deleted -> {
                    uploads.forgetUploadedVideoId(song.videoId)
                    _messages.emit(YouTubeUploadMessage(R.string.youtube_delete_done))
                    loadPage(reset = true)
                }

                is YouTubeDeleteResult.Failed -> {
                    val textRes =
                        if (result.failure == YouTubeUploadFailure.SIGN_IN_REQUIRED) {
                            R.string.youtube_upload_error_sign_in
                        } else {
                            R.string.youtube_delete_failed
                        }
                    _messages.emit(YouTubeUploadMessage(textRes))
                }
            }
        }
    }

    private suspend fun loadPage(reset: Boolean) {
        pageMutex.withLock {
            if (!uploads.signedIn()) {
                continuation = null
                _hasMore.value = false
                _songs.value = emptyList()
                _failure.value = YouTubeUploadFailure.SIGN_IN_REQUIRED
                return
            }
            if (!reset && continuation == null) return
            _loading.value = true
            try {
                val page = uploads.browse(if (reset) null else continuation)
                // Remembered before the list shows, so a tap on a listed upload already skips SponsorBlock.
                uploads.rememberUploadedVideoIds(page.songs.map { it.videoId })
                continuation = page.continuation
                _hasMore.value = page.continuation != null
                // A duplicate key would crash the lazy list, so every page is de-duplicated before it is shown.
                _songs.value = (if (reset) page.songs else _songs.value + page.songs).distinctBy { it.videoId }
                _failure.value = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: YouTubeUploadException) {
                _failure.value = failure.failure
            } catch (failure: Exception) {
                _failure.value = YouTubeUploadFailure.UNKNOWN
            } finally {
                _loading.value = false
            }
        }
    }

    private fun onWorkChanged(infos: List<WorkInfo>) {
        for (info in infos) {
            when (info.state) {
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.RUNNING,
                WorkInfo.State.BLOCKED,
                -> watchedWork += info.id

                WorkInfo.State.SUCCEEDED,
                WorkInfo.State.FAILED,
                WorkInfo.State.CANCELLED,
                -> {
                    if (watchedWork.remove(info.id)) announce(info.outputData)
                }
            }
        }
    }

    private fun announce(output: Data) {
        val stage = stageOf(output.getString(YouTubeUploadWorkData.KEY_STAGE)) ?: YouTubeUploadStage.FAILED
        when (stage) {
            YouTubeUploadStage.DONE -> {
                post(R.string.youtube_upload_done, output.getString(YouTubeUploadWorkData.KEY_TITLE).orEmpty())
                refresh()
            }

            YouTubeUploadStage.UPLOADED -> {
                post(R.string.youtube_upload_accepted)
                refresh()
            }

            YouTubeUploadStage.ALREADY_UPLOADED -> post(R.string.youtube_upload_already)

            YouTubeUploadStage.FAILED -> post(failureMessageRes(output.getString(YouTubeUploadWorkData.KEY_FAILURE)))

            YouTubeUploadStage.QUEUED,
            YouTubeUploadStage.UPLOADING,
            YouTubeUploadStage.PROCESSING,
            -> Unit
        }
    }

    private fun taskOf(info: WorkInfo): YouTubeUploadTask? {
        if (info.state.isFinished) return null
        val progress = info.progress
        val name =
            progress.getString(YouTubeUploadWorkData.KEY_NAME)
                ?: info.tags
                    .firstOrNull { tag -> tag.startsWith(YouTubeUploadWorkData.NAME_TAG_PREFIX) }
                    ?.removePrefix(YouTubeUploadWorkData.NAME_TAG_PREFIX)
                ?: ""
        return YouTubeUploadTask(
            id = info.id,
            name = name,
            stage = stageOf(progress.getString(YouTubeUploadWorkData.KEY_STAGE)) ?: YouTubeUploadStage.QUEUED,
            percent = progress.getInt(YouTubeUploadWorkData.KEY_PERCENT, -1).takeIf { it >= 0 },
        )
    }

    private fun post(textRes: Int, arg: String? = null) {
        _messages.tryEmit(YouTubeUploadMessage(textRes, arg))
    }

    private fun stageOf(stored: String?): YouTubeUploadStage? =
        stored?.let { value -> runCatching { YouTubeUploadStage.valueOf(value) }.getOrNull() }
}

/** The song a shared player row and song menu expect. Uploads carry no album id, so no album is linked. */
internal fun YouTubeUploadedSong.toSongItem(): SongItem =
    SongItem(
        id = videoId,
        title = title,
        artists = artist?.let { listOf(Artist(name = it, id = null)) }.orEmpty(),
        album = null,
        duration = durationSeconds,
        thumbnail = thumbnailUrl.orEmpty(),
    )
