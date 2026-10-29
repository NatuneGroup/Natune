package moe.rukamori.archivetune.uploads

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.shared.data.provider.YouTubeDeleteResult
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadClient
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadContent
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadResult
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadSession
import moe.rukamori.archivetune.shared.data.provider.YouTubeUploadedSongsPage
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.withAppProxy
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Video ids of uploads the app has listed or finished processing. SponsorBlock skips these, because
 * an upload is private to the account and is never looked up against the public segment database.
 */
val YouTubeUploadedVideoIdsKey = stringSetPreferencesKey("youtube_uploaded_video_ids")

private const val MAX_REMEMBERED_UPLOAD_IDS = 5_000

/** Upload, uploads-shelf browse and delete for the signed-in YouTube Music account. */
@Singleton
class YouTubeUploadsRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val client by lazy {
            HttpClient(OkHttp) {
                engine {
                    config {
                        withAppProxy()
                    }
                }
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    // Uploads stream up to the 300 MiB limit, so the whole-request timeout is lifted here.
                    // The JSON calls keep their own bound inside YouTubeUploadClient.
                    requestTimeoutMillis = Long.MAX_VALUE
                    socketTimeoutMillis = 60_000
                }
                expectSuccess = false
            }
        }

        private val uploads by lazy {
            YouTubeUploadClient(client, session = { currentSession() })
        }

        /** True when the account has a login cookie the upload requests can be signed with. */
        fun signedIn(): Boolean = currentSession() != null

        /** One page of the uploads shelf. Pass the previous page's continuation to read the next one. */
        suspend fun browse(continuation: String?): YouTubeUploadedSongsPage = uploads.browseUploads(continuation)

        suspend fun upload(
            fileName: String,
            content: YouTubeUploadContent,
            onProgress: (Long) -> Unit,
        ): YouTubeUploadResult = uploads.upload(fileName, content, onProgress)

        suspend fun delete(entityId: String): YouTubeDeleteResult = uploads.delete(entityId)

        /** Remembers listed upload ids. A failed write is logged and never fails the shelf that was just read. */
        suspend fun rememberUploadedVideoIds(videoIds: Collection<String>) {
            if (videoIds.isEmpty()) return
            updateUploadedVideoIds { current ->
                // Newest first, so the cap drops the oldest ids rather than the ones just listed.
                LinkedHashSet<String>()
                    .apply {
                        addAll(videoIds)
                        addAll(current)
                    }.take(MAX_REMEMBERED_UPLOAD_IDS)
                    .toSet()
            }
        }

        suspend fun forgetUploadedVideoId(videoId: String) {
            updateUploadedVideoIds { current -> current - videoId }
        }

        private suspend fun updateUploadedVideoIds(transform: (Set<String>) -> Set<String>) {
            try {
                context.dataStore.edit { preferences ->
                    preferences[YouTubeUploadedVideoIdsKey] = transform(preferences[YouTubeUploadedVideoIdsKey].orEmpty())
                }
            } catch (failure: IOException) {
                Timber.w(failure, "Uploaded song ids were not saved")
            }
        }

        /** Read on every request, so signing out takes effect on the next upload, browse or delete. */
        private fun currentSession(): YouTubeUploadSession? {
            if (!YouTube.hasLoginCookie()) return null
            val cookie = YouTube.cookie?.takeIf { it.isNotBlank() } ?: return null
            return YouTubeUploadSession(
                cookie = cookie,
                visitorData = YouTube.visitorData,
                dataSyncId = YouTube.dataSyncId,
                gl = YouTube.locale.gl,
                hl = YouTube.locale.hl,
            )
        }
    }
