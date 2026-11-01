@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramClient
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile

/**
 * The `tgart:` artwork chain on iOS, the same shape the desktop loader's `fetchTelegram` keeps: the URI carries a
 * Telegram thumbnail file id plus a cleaned title/artist pair. An iTunes catalogue cover is tried first, looked up
 * once per (title, artist) and cached, then the session's own thumbnail file is downloaded through the client and
 * read from its local path. Every step answers null rather than throwing so the caller falls back cleanly.
 */
internal class IosTelegramArtwork(private val http: HttpClient) {
    private val coverLock = Mutex()
    private val coverCache = LinkedHashMap<String, String>()

    /** The image bytes a `tgart:` URI resolves to: the catalogue cover when found, else the thumbnail file. */
    suspend fun fetchBytes(uri: String, telegram: IosTelegramClient?): ByteArray? {
        val model = telegramArtworkModel(uri) ?: return null
        if (model.title.isNotBlank()) {
            coverUrl(model.title, model.artist)?.let { fetchBounded(it, MAX_IMAGE_BYTES) }?.let { return it }
        }
        if (model.fileId > 0) {
            telegram?.downloadFileBlocking(model.fileId)?.let { path ->
                return iosImageFileBytes("file://$path", MAX_COVER_BYTES)
            }
        }
        return null
    }

    /** The iTunes lookup: one result, 100px artwork widened to 600px, hits and misses both cached and bounded. */
    private suspend fun coverUrl(title: String, artist: String?): String? {
        val cleaned = cleanTelegramTitle(title)
        if (cleaned.isBlank()) return null
        val key = "${cleaned.lowercase()}|${artist?.lowercase().orEmpty()}"
        coverLock.withLock { coverCache[key]?.let { return it.takeIf(String::isNotBlank) } }
        val term = listOfNotNull(artist?.takeIf(String::isNotBlank), cleaned).joinToString(" ")
        val url = "https://itunes.apple.com/search?term=${term.encodeFormUrlComponent()}" +
            "&entity=song&limit=1&media=music"
        val artwork = http.prepareGet(url) {
            header("User-Agent", "ArchiveTune")
        }.execute { response ->
            if (!response.status.isSuccess()) return@execute null
            val text = response.readBounded(MAX_TELEGRAM_LOOKUP_BYTES)?.decodeToString() ?: return@execute null
            val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return@execute null
            val first = (root["results"] as? JsonArray)?.firstOrNull() as? JsonObject
            (first?.get("artworkUrl100") as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.replace(Regex("/\\d+x\\d+bb\\.jpg$"), "/600x600bb.jpg")
        }
        coverLock.withLock {
            if (coverCache.size >= MAX_TELEGRAM_LOOKUP_CACHE) coverCache.clear()
            coverCache[key] = artwork.orEmpty()
        }
        return artwork
    }

    private suspend fun fetchBounded(uri: String, maxBytes: Int): ByteArray? =
        http.prepareGet(uri).execute { response ->
            if (!response.status.isSuccess()) return@execute null
            response.readBounded(maxBytes)
        }

    private data class TelegramArtworkModel(val fileId: Int, val title: String, val artist: String?)

    /** The file id is the authority; the title and artist ride the query, percent-decoded. */
    private fun telegramArtworkModel(uri: String): TelegramArtworkModel? {
        if (!uri.startsWith(TGART_SCHEME)) return null
        val rest = uri.removePrefix(TGART_SCHEME)
        val fileId = rest.substringBefore('?').substringBefore('/').toIntOrNull()?.takeIf { it > 0 } ?: 0
        val query = rest.substringAfter('?', "").split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "")
            if (key.isEmpty()) return@mapNotNull null
            key to part.substringAfter('=', "").percentDecode()
        }.toMap()
        return TelegramArtworkModel(
            fileId = fileId,
            title = query["t"].orEmpty(),
            artist = query["a"]?.takeIf(String::isNotBlank),
        )
    }

    private companion object {
        const val TGART_SCHEME = "tgart://"
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_COVER_BYTES = 20L * 1024L * 1024L
        const val MAX_TELEGRAM_LOOKUP_BYTES = 64 * 1024
        const val MAX_TELEGRAM_LOOKUP_CACHE = 512
    }
}

/** The cleaning the catalogue lookup asks for: no "(Official Video)" noise, bracket tags or track numbers. */
private fun cleanTelegramTitle(raw: String): String = raw
    .replace(Regex("\\((?:official|lyric|audio|video|hd|hq|visualizer)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("\\[[^\\]]*\\]"), " ")
    .replace(Regex("^\\s*\\d+\\s*[.\\-]\\s*"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

/** The URLDecoder reading of a query value: `%XX` bytes as UTF-8, `+` as space, stray `%` kept literal. */
private fun String.percentDecode(): String {
    val bytes = ArrayList<Byte>(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '%' && index + 3 <= length) {
            val hex = substring(index + 1, index + 3).toIntOrNull(16)
            if (hex != null) {
                bytes += hex.toByte()
                index += 3
                continue
            }
        }
        if (char == '+') {
            bytes += ' '.code.toByte()
        } else {
            char.toString().encodeToByteArray().forEach { bytes += it }
        }
        index++
    }
    return bytes.toByteArray().decodeToString()
}

/** The form-urlencoded term the iTunes query takes: the URLEncoder encoding, spaces as `+`. */
private fun String.encodeFormUrlComponent(): String = buildString {
    for (byte in encodeToByteArray()) {
        val char = byte.toInt().toChar()
        when {
            char.isFormUrlUnreserved() -> append(char)
            char == ' ' -> append('+')
            else -> append('%').append((byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase())
        }
    }
}

private fun Char.isFormUrlUnreserved(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '.' || this == '-' || this == '*' || this == '_'

/** Reads a `file:` image inside the cover bound, for the Telegram thumbnail path or a local artwork file. */
internal fun iosImageFileBytes(fileUri: String, maxBytes: Long): ByteArray? {
    val url = NSURL.URLWithString(fileUri) ?: return null
    if (url.scheme?.lowercase() != "file") return null
    val path = url.path?.takeIf { it.isNotBlank() && it.indexOf('\u0000') < 0 } ?: return null
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    if (data.length.toLong() > maxBytes) return null
    return if (data.length == 0uL) byteArrayOf() else data.bytes?.readBytes(data.length.toInt())
}

/** Reads at most [maxBytes] of the body. A larger body is refused whole. */
private suspend fun HttpResponse.readBounded(maxBytes: Int): ByteArray? {
    val channel = bodyAsChannel()
    val chunks = ArrayList<ByteArray>()
    val buffer = ByteArray(8192)
    var size = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = channel.readAvailable(buffer)
        if (count < 0) break
        if (count == 0) {
            yield()
            continue
        }
        if (count > maxBytes - size) return null
        chunks += buffer.copyOf(count)
        size += count
    }
    if (size == 0) return null
    val bytes = ByteArray(size)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(bytes, offset)
        offset += chunk.size
    }
    return bytes
}
