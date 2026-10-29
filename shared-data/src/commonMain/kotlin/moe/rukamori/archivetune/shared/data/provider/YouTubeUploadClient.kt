package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

private const val UPLOAD_ENDPOINT = "https://upload.youtube.com/upload/usermusic/http"
private const val UPLOAD_HOST = "upload.youtube.com"
private const val AUTH_USER = "0"
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_CONFLICT = 409
private const val HEADER_UPLOAD_ID = "X-Goog-Upload-Id"
private const val HEADER_UPLOAD_ID_FALLBACK = "X-Guploader-Uploadid"
private const val HEADER_UPLOAD_URL = "X-Goog-Upload-Url"
private const val MAX_UPLOAD_ID_LENGTH = 512
private const val MAX_ENTITY_ID_LENGTH = 512
private const val MAX_CONTINUATION_LENGTH = 8_192
private const val MAX_JSON_BYTES = 8 * 1024 * 1024
private const val JSON_TIMEOUT_MS = 20_000L

/**
 * Upload, uploads-shelf browse and delete for the signed-in YouTube Music account. The requests mirror the
 * retained InnerTube wire format (WEB_REMIX context, SAPISIDHASH over the music origin), so every host supplies
 * its own [HttpClient] and no JVM-only module is needed. The client keeps no account state: [session] is read
 * on each call, so a sign-out takes effect on the next request.
 */
class YouTubeUploadClient(
    client: HttpClient,
    private val session: () -> YouTubeUploadSession?,
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val http = client.config { followRedirects = false; expectSuccess = false }

    /**
     * Streams [content] into the account's library as [fileName]. Sign-in and file rules are checked before any
     * request is sent. HTTP 409 means the account already holds the file and is reported as [YouTubeUploadResult.AlreadyUploaded].
     */
    suspend fun upload(
        fileName: String,
        content: YouTubeUploadContent,
        onProgress: (Long) -> Unit = {},
    ): YouTubeUploadResult {
        val current = session() ?: return YouTubeUploadResult.Failed(YouTubeUploadFailure.SIGN_IN_REQUIRED)
        val loginValue = YouTubeUploadAuth.loginCookieValue(current.cookie)
            ?: return YouTubeUploadResult.Failed(YouTubeUploadFailure.SIGN_IN_REQUIRED)
        YouTubeUploadPolicy.validate(fileName, content.length)?.let { return YouTubeUploadResult.Failed(it) }
        return try {
            transfer(fileName, content, current, loginValue, onProgress)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            YouTubeUploadResult.Failed(YouTubeUploadFailure.UNKNOWN)
        }
    }

    /** One page of the uploads shelf. Pass the previous page's continuation to read the next one. */
    suspend fun browseUploads(continuation: String? = null): YouTubeUploadedSongsPage {
        val current = session() ?: throw YouTubeUploadException(YouTubeUploadFailure.SIGN_IN_REQUIRED)
        val loginValue = YouTubeUploadAuth.loginCookieValue(current.cookie)
            ?: throw YouTubeUploadException(YouTubeUploadFailure.SIGN_IN_REQUIRED)
        if (continuation != null && continuation.length !in 1..MAX_CONTINUATION_LENGTH) {
            throw YouTubeUploadException(YouTubeUploadFailure.UNKNOWN)
        }
        val body = buildJsonObject {
            put("context", current.innerTubeContext())
            if (continuation == null) {
                put("browseId", YOUTUBE_UPLOADED_TRACKS_BROWSE_ID)
            } else {
                put("continuation", continuation)
            }
        }
        val reply = jsonExchange("browse", current, loginValue, body)
        if (reply.status !in 200..299) throw YouTubeUploadException(failureFor(reply.status), reply.status)
        val root = try {
            Json.parseToJsonElement(reply.body)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            throw YouTubeUploadException(YouTubeUploadFailure.UNKNOWN, reply.status)
        }
        return YouTubeUploadBrowse.parse(root)
    }

    /**
     * Deletes one upload. Only a 2xx answer without an error object counts as success; anything else is a
     * failure the caller must surface, because the upload is still in the account.
     */
    suspend fun delete(entityId: String): YouTubeDeleteResult {
        val current = session() ?: return YouTubeDeleteResult.Failed(YouTubeUploadFailure.SIGN_IN_REQUIRED)
        val loginValue = YouTubeUploadAuth.loginCookieValue(current.cookie)
            ?: return YouTubeDeleteResult.Failed(YouTubeUploadFailure.SIGN_IN_REQUIRED)
        if (entityId.isBlank() || entityId.length > MAX_ENTITY_ID_LENGTH) {
            return YouTubeDeleteResult.Failed(YouTubeUploadFailure.UNKNOWN)
        }
        val body = buildJsonObject {
            put("context", current.innerTubeContext())
            put("entityId", entityId)
        }
        val reply = try {
            jsonExchange("music/delete_privately_owned_entity", current, loginValue, body)
        } catch (failure: YouTubeUploadException) {
            return YouTubeDeleteResult.Failed(failure.failure, failure.httpStatus)
        }
        return when {
            reply.status !in 200..299 -> YouTubeDeleteResult.Failed(failureFor(reply.status), reply.status)
            acceptsDeleteBody(reply.body) -> YouTubeDeleteResult.Deleted
            else -> YouTubeDeleteResult.Failed(YouTubeUploadFailure.UNKNOWN, reply.status)
        }
    }

    private suspend fun transfer(
        fileName: String,
        content: YouTubeUploadContent,
        current: YouTubeUploadSession,
        loginValue: String,
        onProgress: (Long) -> Unit,
    ): YouTubeUploadResult {
        val start = exchange(UPLOAD_ENDPOINT, current, loginValue, {
            parameter("authuser", AUTH_USER)
            header("X-Goog-Upload-Command", "start")
            header("X-Goog-Upload-Protocol", "resumable")
            header("X-Goog-Upload-Header-Content-Length", content.length.toString())
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("filename=" + fileName.encodeURLParameter())
        }) { response -> Reply(response.status.value, response.headers) }
        if (start.status == HTTP_CONFLICT) return YouTubeUploadResult.AlreadyUploaded
        if (start.status !in 200..299) return failed(start.status)

        // The session URL the service hands back is used only when it stays on the upload host, so the
        // account cookie and SAPISIDHASH never travel to an address the server merely named.
        val sessionUrl = start.headers[HEADER_UPLOAD_URL]?.let(::uploadEndpointOrNull)
        val uploadId = (start.headers[HEADER_UPLOAD_ID] ?: start.headers[HEADER_UPLOAD_ID_FALLBACK])
            ?.takeIf { it.isNotBlank() && it.length <= MAX_UPLOAD_ID_LENGTH }
        val finalize = when {
            sessionUrl != null -> exchange(sessionUrl, current, loginValue, {
                finalizeBody(content, onProgress)
            }) { response -> Reply(response.status.value, response.headers) }
            uploadId != null -> exchange(UPLOAD_ENDPOINT, current, loginValue, {
                parameter("authuser", AUTH_USER)
                parameter("upload_id", uploadId)
                parameter("upload_protocol", "resumable")
                finalizeBody(content, onProgress)
            }) { response -> Reply(response.status.value, response.headers) }
            else -> return YouTubeUploadResult.Failed(YouTubeUploadFailure.UNKNOWN, start.status)
        }
        return when (finalize.status) {
            in 200..299 -> YouTubeUploadResult.Uploaded
            HTTP_CONFLICT -> YouTubeUploadResult.AlreadyUploaded
            else -> failed(finalize.status)
        }
    }

    private suspend fun jsonExchange(
        path: String,
        current: YouTubeUploadSession,
        loginValue: String,
        body: JsonObject,
    ): Reply {
        val reply = try {
            withTimeoutOrNull(JSON_TIMEOUT_MS) {
                exchange("$YOUTUBE_MUSIC_API$path", current, loginValue, {
                    parameter("prettyPrint", "false")
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }) { response ->
                    Reply(response.status.value, response.headers, response.readBoundedText(MAX_JSON_BYTES))
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            throw YouTubeUploadException(YouTubeUploadFailure.UNKNOWN)
        }
        return reply ?: throw YouTubeUploadException(YouTubeUploadFailure.UNKNOWN)
    }

    private suspend fun <T> exchange(
        url: String,
        current: YouTubeUploadSession,
        loginValue: String,
        configure: HttpRequestBuilder.() -> Unit,
        read: suspend (HttpResponse) -> T,
    ): T = http.prepareRequest(url) {
        method = HttpMethod.Post
        header(HttpHeaders.UserAgent, WEB_REMIX_USER_AGENT)
        header("X-Goog-Api-Format-Version", "1")
        header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
        header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
        header("X-Origin", YOUTUBE_MUSIC_ORIGIN)
        header(HttpHeaders.Referrer, "$YOUTUBE_MUSIC_ORIGIN/")
        current.visitorData?.let { header("X-Goog-Visitor-Id", it) }
        header(HttpHeaders.Cookie, current.cookie)
        header(
            HttpHeaders.Authorization,
            YouTubeUploadAuth.authorization(loginValue, YOUTUBE_MUSIC_ORIGIN, nowEpochSeconds()),
        )
        header("X-Goog-AuthUser", AUTH_USER)
        configure()
    }.execute { response -> read(response) }

    private fun HttpRequestBuilder.finalizeBody(content: YouTubeUploadContent, onProgress: (Long) -> Unit) {
        header("X-Goog-Upload-Command", "upload, finalize")
        header("X-Goog-Upload-Offset", "0")
        setBody(UploadBody(content, onProgress))
    }

    private fun failed(status: Int): YouTubeUploadResult.Failed = YouTubeUploadResult.Failed(failureFor(status), status)

    private class Reply(val status: Int, val headers: Headers, val body: String = "")
}

private fun failureFor(status: Int): YouTubeUploadFailure =
    if (status == HTTP_UNAUTHORIZED) YouTubeUploadFailure.SIGN_IN_REQUIRED else YouTubeUploadFailure.HTTP

/** Only an upload-host HTTPS address is ever followed; anything else is ignored rather than trusted. */
private fun uploadEndpointOrNull(value: String): String? {
    val url = runCatching { Url(value) }.getOrNull() ?: return null
    val trusted = url.protocol == URLProtocol.HTTPS && url.host == UPLOAD_HOST &&
        url.user.isNullOrEmpty() && url.password.isNullOrEmpty()
    return value.takeIf { trusted }
}

/** A delete succeeds on an empty body or a JSON object with no error member. */
private fun acceptsDeleteBody(text: String): Boolean {
    if (text.isBlank()) return true
    val root = try {
        Json.parseToJsonElement(text)
    } catch (failure: Exception) {
        return false
    }
    return root is JsonObject && "error" !in root
}

/** Streams the content in bounded chunks and reports the bytes sent, so a host can show real progress. */
private class UploadBody(
    private val content: YouTubeUploadContent,
    private val onProgress: (Long) -> Unit,
) : OutgoingContent.WriteChannelContent() {
    override val contentType: ContentType = ContentType.Application.OctetStream
    override val contentLength: Long = content.length

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val buffer = ByteArray(CHUNK_BYTES)
        var sent = 0L
        while (true) {
            val count = content.read(buffer)
            if (count < 0) break
            check(count > 0) { "Upload source returned no bytes" }
            channel.writeFully(buffer, 0, count)
            sent += count
            onProgress(sent)
        }
        check(sent == contentLength) { "Upload source length changed" }
        channel.flush()
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
    }
}
