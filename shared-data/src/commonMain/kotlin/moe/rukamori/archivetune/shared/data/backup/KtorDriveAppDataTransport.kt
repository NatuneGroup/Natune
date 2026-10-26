package moe.rukamori.archivetune.shared.data.backup

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import moe.rukamori.archivetune.shared.data.bytesource.Md5
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataBackupException
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataBackupFailure
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataBackupMetadata
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataLimits
import moe.rukamori.archivetune.shared.domain.backup.DriveAppDataTransport
import kotlin.coroutines.cancellation.CancellationException

/**
 * Google Drive appData transport over Ktor, following the GMS baseline protocol. The caller supplies a bearer
 * token for every call and this class never caches credentials. Redirects are not followed, and a resumable
 * session URL is accepted only from the Google upload host over HTTPS. Requests share the engine of [client].
 * [fileName] is the one backup file this host reads and replaces in the app data folder.
 */
class KtorDriveAppDataTransport(
    client: HttpClient,
    private val fileName: String = DriveAppDataLimits.FILE_NAME,
) : DriveAppDataTransport {
    init {
        // The name is placed into a Drive query and a JSON body unescaped, so only plain file-name characters pass.
        require(fileName.isNotEmpty() && fileName.all { it.isLetterOrDigit() || it in "._-" }) { "Unsafe backup file name" }
    }

    private val http = HttpClient(client.engine) {
        followRedirects = false
        expectSuccess = false
    }

    override suspend fun latest(token: String): DriveAppDataBackupMetadata? = guarded {
        val response = http.request(FILES_URL) {
            method = HttpMethod.Get
            bearer(token)
            parameter("spaces", APP_DATA_FOLDER)
            parameter("q", "name = '$fileName' and trashed = false")
            parameter("orderBy", "modifiedTime desc")
            parameter("pageSize", "1")
            parameter("fields", "files($METADATA_FIELDS)")
        }
        val body = response.bodyAsText()
        requireSuccess(response.status, body)
        val files = parseObject(body)["files"] as? JsonArray ?: return@guarded null
        val newest = files.firstOrNull() as? JsonObject ?: return@guarded null
        newest.toMetadata()
    }

    override suspend fun upload(
        token: String,
        existing: DriveAppDataBackupMetadata?,
        archive: ByteArray,
    ): DriveAppDataBackupMetadata = guarded {
        if (archive.isEmpty() || archive.size > DriveAppDataLimits.MAX_ARCHIVE_BYTES) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.STORAGE)
        }
        val expectedMd5 = Md5.hexDigest(archive)
        val sessionUrl = startResumableUpload(token, existing, archive.size)
        val response = http.request(sessionUrl) {
            method = HttpMethod.Put
            bearer(token)
            contentType(ContentType.parse(ZIP_MIME))
            setBody(archive)
        }
        val body = response.bodyAsText()
        requireSuccess(response.status, body)
        val uploaded = parseObject(body).toMetadata()
        if (uploaded.sizeBytes != archive.size.toLong() || !uploaded.md5Hex.equals(expectedMd5, ignoreCase = true)) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.INVALID)
        }
        uploaded
    }

    override suspend fun download(
        token: String,
        backup: DriveAppDataBackupMetadata,
        onProgress: (Int) -> Unit,
    ): ByteArray = guarded {
        val size = backup.sizeBytes
        if (size <= 0L || size > DriveAppDataLimits.MAX_ARCHIVE_BYTES.toLong()) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.STORAGE)
        }
        if (backup.md5Hex.isEmpty()) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.INVALID)
        }
        val id = safeFileId(backup.id)
        http.prepareGet("$FILES_URL/$id") {
            bearer(token)
            parameter("alt", "media")
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw failureFor(response.status, response.bodyAsText())
            }
            copyVerified(response, size, backup.md5Hex, onProgress)
        }
    }

    private suspend fun startResumableUpload(
        token: String,
        existing: DriveAppDataBackupMetadata?,
        size: Int,
    ): String {
        val target = if (existing == null) UPLOAD_URL else "$UPLOAD_URL/${safeFileId(existing.id)}"
        val metadata = if (existing == null) {
            """{"name":"$fileName","mimeType":"$ZIP_MIME","parents":["$APP_DATA_FOLDER"]}"""
        } else {
            """{"name":"$fileName","mimeType":"$ZIP_MIME"}"""
        }
        val response = http.request(target) {
            method = if (existing == null) HttpMethod.Post else HttpMethod.Patch
            bearer(token)
            header(UPLOAD_CONTENT_TYPE_HEADER, ZIP_MIME)
            header(UPLOAD_CONTENT_LENGTH_HEADER, size.toString())
            parameter("uploadType", "resumable")
            parameter("fields", METADATA_FIELDS)
            contentType(ContentType.parse(JSON_UTF8))
            setBody(metadata)
        }
        val body = response.bodyAsText()
        requireSuccess(response.status, body)
        val location = response.headers[HttpHeaders.Location]
            ?: throw DriveAppDataBackupException(DriveAppDataBackupFailure.UNKNOWN)
        return validatedSessionUrl(location)
    }

    /** Streams the body into a buffer of the advertised size and rejects any size or checksum mismatch. */
    private suspend fun copyVerified(
        response: HttpResponse,
        size: Long,
        md5Hex: String,
        onProgress: (Int) -> Unit,
    ): ByteArray {
        val expected = size.toInt()
        val target = ByteArray(expected)
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0
        var reported = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = channel.readAvailable(buffer)
            if (read < 0) break
            if (read == 0) {
                yield()
                continue
            }
            if (copied + read > expected) {
                throw DriveAppDataBackupException(DriveAppDataBackupFailure.INVALID)
            }
            buffer.copyInto(target, copied, 0, read)
            copied += read
            val percent = (copied.toLong() * 100L / size).toInt()
            if (percent != reported) {
                reported = percent
                onProgress(percent)
            }
        }
        if (copied != expected) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.INVALID)
        }
        if (!Md5.hexDigest(target).equals(md5Hex, ignoreCase = true)) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.INVALID)
        }
        return target
    }

    private fun validatedSessionUrl(location: String): String {
        val url = try {
            Url(location)
        } catch (malformedUrl: Exception) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.UNKNOWN, malformedUrl)
        }
        val accepted = url.protocol == URLProtocol.HTTPS &&
            url.host == UPLOAD_HOST &&
            url.user.isNullOrEmpty()
        if (!accepted) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.UNKNOWN)
        }
        return location
    }

    private fun requireSuccess(status: HttpStatusCode, body: String) {
        if (!status.isSuccess()) {
            throw failureFor(status, body)
        }
    }

    /** Maps Drive's HTTP status and error body onto the classified failures, in the baseline order. */
    private fun failureFor(status: HttpStatusCode, body: String): DriveAppDataBackupException {
        val failure = when {
            status == HttpStatusCode.Unauthorized -> DriveAppDataBackupFailure.AUTHORIZATION
            status == HttpStatusCode.NotFound -> DriveAppDataBackupFailure.MISSING
            body.contains("storageQuotaExceeded") -> DriveAppDataBackupFailure.QUOTA
            body.contains("accessNotConfigured") || body.contains("SERVICE_DISABLED") ->
                DriveAppDataBackupFailure.CONFIGURATION
            status.value == 429 || status.value >= 500 || body.contains("rateLimitExceeded") ->
                DriveAppDataBackupFailure.NETWORK
            status == HttpStatusCode.Forbidden -> DriveAppDataBackupFailure.AUTHORIZATION
            else -> DriveAppDataBackupFailure.UNKNOWN
        }
        return DriveAppDataBackupException(failure)
    }

    /** Transport errors become NETWORK; classified failures and cancellation pass through unchanged. */
    private suspend fun <T> guarded(block: suspend () -> T): T {
        try {
            return block()
        } catch (classified: DriveAppDataBackupException) {
            throw classified
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (transport: IOException) {
            throw DriveAppDataBackupException(DriveAppDataBackupFailure.NETWORK, transport)
        }
    }

    private fun parseObject(body: String): JsonObject =
        try {
            Json.parseToJsonElement(body) as? JsonObject ?: throw malformed()
        } catch (syntax: SerializationException) {
            throw malformed(syntax)
        }

    private fun JsonObject.toMetadata(): DriveAppDataBackupMetadata {
        val id = safeFileId((this["id"] as? JsonPrimitive)?.contentOrNull ?: throw malformed())
        val modifiedAt = (this["modifiedTime"] as? JsonPrimitive)?.contentOrNull
            ?.let(::parseRfc3339Millis)
            ?: throw malformed()
        val size = (this["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: throw malformed()
        val md5 = (this["md5Checksum"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return DriveAppDataBackupMetadata(
            id = id,
            modifiedAtMillis = modifiedAt,
            sizeBytes = size,
            md5Hex = md5,
        )
    }

    private fun safeFileId(id: String): String {
        if (!isSafeFileId(id)) throw malformed()
        return id
    }

    private fun isSafeFileId(id: String): Boolean =
        id.isNotEmpty() && id.length <= MAX_FILE_ID_LENGTH && id.all { character ->
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                character == '-' || character == '_'
        }

    private fun malformed(cause: Throwable? = null): DriveAppDataBackupException =
        DriveAppDataBackupException(DriveAppDataBackupFailure.INVALID, cause)

    private fun HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private companion object {
        const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        const val UPLOAD_HOST = "www.googleapis.com"
        const val APP_DATA_FOLDER = "appDataFolder"
        const val METADATA_FIELDS = "id,modifiedTime,size,md5Checksum"
        const val ZIP_MIME = "application/zip"
        const val JSON_UTF8 = "application/json; charset=utf-8"
        const val UPLOAD_CONTENT_TYPE_HEADER = "X-Upload-Content-Type"
        const val UPLOAD_CONTENT_LENGTH_HEADER = "X-Upload-Content-Length"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_FILE_ID_LENGTH = 128
    }
}

/**
 * Parses an RFC 3339 timestamp as Drive reports it (for example `2026-10-25T12:34:56.789Z` or with a numeric
 * offset) into epoch milliseconds. Returns null for anything else, so malformed metadata is never guessed.
 * Written by hand because the common module does not depend on the platform time parser.
 */
private fun parseRfc3339Millis(value: String): Long? {
    fun digitsAt(start: Int, count: Int): Int? {
        if (start < 0 || start + count > value.length) return null
        var number = 0
        for (position in start until start + count) {
            val digit = value[position]
            if (digit !in '0'..'9') return null
            number = number * 10 + (digit - '0')
        }
        return number
    }

    fun charAt(position: Int): Char? = value.getOrNull(position)

    val year = digitsAt(0, 4) ?: return null
    if (charAt(4) != '-') return null
    val month = digitsAt(5, 2) ?: return null
    if (charAt(7) != '-') return null
    val day = digitsAt(8, 2) ?: return null
    if (charAt(10) != 'T' && charAt(10) != 't') return null
    val hour = digitsAt(11, 2) ?: return null
    if (charAt(13) != ':') return null
    val minute = digitsAt(14, 2) ?: return null
    if (charAt(16) != ':') return null
    val second = digitsAt(17, 2) ?: return null

    var index = 19
    var millis = 0
    if (charAt(index) == '.') {
        index++
        var fractionDigits = 0
        while (index < value.length && value[index] in '0'..'9') {
            if (fractionDigits < 3) {
                millis = millis * 10 + (value[index] - '0')
                fractionDigits++
            }
            index++
        }
        while (fractionDigits < 3) {
            millis *= 10
            fractionDigits++
        }
    }

    val offsetSeconds = when (charAt(index)) {
        'Z', 'z' -> {
            index++
            0
        }
        '+', '-' -> {
            val sign = if (value[index] == '-') -1 else 1
            val offsetHours = digitsAt(index + 1, 2) ?: return null
            if (charAt(index + 3) != ':') return null
            val offsetMinutes = digitsAt(index + 4, 2) ?: return null
            index += 6
            sign * (offsetHours * 3_600 + offsetMinutes * 60)
        }
        else -> return null
    }
    if (index != value.length) return null
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null

    val days = daysFromCivil(year, month, day)
    val seconds = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetSeconds
    return seconds * 1_000L + millis
}

/** Days since 1970-01-01 in the proleptic Gregorian calendar (Howard Hinnant's civil-date algorithm). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val adjustedYear = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (adjustedYear >= 0) adjustedYear else adjustedYear - 399) / 400
    val yearOfEra = adjustedYear - era * 400
    val monthFromMarch = ((month + 9) % 12).toLong()
    val dayOfYear = (153 * monthFromMarch + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}
