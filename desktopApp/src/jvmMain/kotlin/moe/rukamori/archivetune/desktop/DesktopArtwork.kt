package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkDirectory
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkFiles
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkKeys
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkLimits
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkOverrides
import moe.rukamori.archivetune.shared.domain.repository.CustomArtworkRepository
import moe.rukamori.archivetune.shared.ui.player.rememberCoverImage
import org.jetbrains.skia.Image as SkiaImage

/**
 * Fetches and decodes the artwork the desktop shows: a public http or https image, a local file image, or Android's
 * `tgart:` Telegram model (catalogue cover, then TDLib thumbnail). Network images carry no cookie and no account
 * header, so a thumbnail never carries a credential. A missing, oversized, slow or undecodable image reads as
 * no image, and its frame keeps the placeholder. Decoded images sit in a bounded least-recently-used cache.
 *
 * A custom cover the user picked replaces the artwork at its URL on every slot, as on Android. It is read from the
 * override folder instead of the network, and its versioned file name keys the cache, so a new pick never shows the
 * image it replaced.
 */
internal class DesktopArtworkLoader(
    private val http: HttpClient,
    private val customArtwork: CustomArtworkRepository,
    private val customFiles: CustomArtworkFiles,
    private val telegramFile: suspend (Int) -> String?,
) {
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, ImageBitmap>(CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > CACHE_ENTRIES
    }
    private val permits = Semaphore(MAX_CONCURRENT_FETCHES)
    private val telegramCoverCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The stored custom covers, so a slot recomposes when one is set or removed. */
    val overrides: StateFlow<CustomArtworkOverrides> get() = customArtwork.overrides

    /** What a slot for [uri] shows: the custom cover file that replaces it, or [uri] itself. */
    fun displayUri(uri: String?, overrides: CustomArtworkOverrides = customArtwork.overrides.value): String? {
        val key = CustomArtworkKeys.coverKey(uri) ?: return uri
        val name = overrides.covers[key] ?: return uri
        return customFiles.uriOf(CustomArtworkDirectory.COVERS, name)
    }

    /** The image already decoded for [uri]. It costs no network work and is null when nothing is held. */
    fun cached(uri: String?): ImageBitmap? {
        val target = displayUri(uri) ?: return null
        return synchronized(lock) { cache[target] }
    }

    /** Loads [uri] once, within the time, size and concurrency bounds. Null means no image can be shown. */
    suspend fun load(uri: String?): ImageBitmap? {
        val target = displayUri(uri) ?: return null
        val custom = target != uri
        val kind = when {
            custom || target.startsWith("file:") -> ArtworkKind.LOCAL_FILE
            target.startsWith("tgart:") -> ArtworkKind.TELEGRAM
            isPublicWebImage(target) -> ArtworkKind.REMOTE
            else -> return null
        }
        synchronized(lock) { cache[target] }?.let { return it }
        val bitmap = try {
            withTimeout(FETCH_TIMEOUT_MS) {
                permits.withPermit {
                    when (kind) {
                        ArtworkKind.LOCAL_FILE -> readImageFile(target)
                        ArtworkKind.REMOTE -> fetchAndDecode(target)
                        ArtworkKind.TELEGRAM -> fetchTelegram(target)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (bitmap != null) synchronized(lock) { cache[target] = bitmap }
        return bitmap
    }

    /**
     * Reads a custom cover from its override file. A picked photo is often larger than a thumbnail, so it is decoded
     * subsampled and then scaled to the same edge bound the remote images keep, instead of being refused.
     */
    private suspend fun readImageFile(fileUri: String): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val file = Paths.get(URI(fileUri))
            if (!Files.isRegularFile(file) || Files.size(file) > CustomArtworkLimits.MAX_COVER_BYTES) return@withContext null
            val input = ImageIO.createImageInputStream(file.toFile()) ?: return@withContext null
            val decoded = input.use { stream ->
                val readers = ImageIO.getImageReaders(stream)
                if (!readers.hasNext()) return@use null
                val reader = readers.next()
                try {
                    reader.input = stream
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0) return@use null
                    val step = max(1, max(width, height) / MAX_IMAGE_EDGE)
                    reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) })
                } finally {
                    reader.dispose()
                }
            } ?: return@withContext null
            scaledToEdge(decoded).toComposeImageBitmap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun scaledToEdge(image: BufferedImage): BufferedImage {
        val longest = max(image.width, image.height)
        if (longest <= MAX_IMAGE_EDGE) return image
        val scale = MAX_IMAGE_EDGE.toDouble() / longest
        val width = max(1, (image.width * scale).roundToInt())
        val height = max(1, (image.height * scale).roundToInt())
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(image, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return scaled
    }

    private suspend fun fetchAndDecode(uri: String): ImageBitmap? {
        val bytes = http.prepareGet(uri).execute { response ->
            if (!response.status.isSuccess()) return@execute null
            response.readBoundedBytes(MAX_IMAGE_BYTES)
        } ?: return null
        return withContext(Dispatchers.Default) { decode(bytes) }
    }

    /** Android's `tgart:` fetcher: an iTunes catalogue cover first, then the embedded TDLib thumbnail. */
    private suspend fun fetchTelegram(uri: String): ImageBitmap? {
        val location = try {
            URI(uri)
        } catch (_: Exception) {
            return null
        }
        val fileId = location.authority?.toIntOrNull()?.takeIf { it > 0 } ?: 0
        val query = location.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            key to URLDecoder.decode(value, Charsets.UTF_8)
        }
        val title = query["t"].orEmpty()
        val artist = query["a"].orEmpty().takeIf(String::isNotBlank)
        if (title.isNotBlank()) {
            telegramCoverUrl(title, artist)?.let { remote -> fetchAndDecode(remote) }?.let { return it }
        }
        val path = if (fileId > 0) telegramFile(fileId) else null
        return path?.let { Paths.get(it).toUri().toString() }?.let { readImageFile(it) }
    }

    /** The Android iTunes lookup: one result, 100px artwork widened to 600px, hits and misses both cached. */
    private suspend fun telegramCoverUrl(title: String, artist: String?): String? {
        val cleaned = cleanTelegramTitle(title)
        if (cleaned.isBlank()) return null
        val key = "${cleaned.lowercase(java.util.Locale.US)}|${artist?.lowercase(java.util.Locale.US).orEmpty()}"
        telegramCoverCache[key]?.let { return it.takeIf(String::isNotBlank) }
        val term = listOfNotNull(artist?.takeIf(String::isNotBlank), cleaned).joinToString(" ")
        val url = "https://itunes.apple.com/search?term=" + URLEncoder.encode(term, "UTF-8") +
            "&entity=song&limit=1&media=music"
        val artwork = http.prepareGet(url) {
            header("User-Agent", "ArchiveTune")
        }.execute { response ->
            if (!response.status.isSuccess()) return@execute null
            val text = response.readBoundedBytes(MAX_TELEGRAM_LOOKUP_BYTES)?.decodeToString() ?: return@execute null
            val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return@execute null
            val first = (root["results"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
            (first?.get("artworkUrl100") as? JsonPrimitive)?.content
                ?.replace(Regex("/\\d+x\\d+bb\\.jpg$"), "/600x600bb.jpg")
        }
        if (telegramCoverCache.size >= MAX_TELEGRAM_LOOKUP_CACHE) telegramCoverCache.clear()
        telegramCoverCache[key] = artwork.orEmpty()
        return artwork
    }

    private fun cleanTelegramTitle(raw: String): String = raw
        .replace(Regex("\\((?:official|lyric|audio|video|hd|hq|visualizer)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("^\\s*\\d+\\s*[.\\-]\\s*"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Refuses an image whose edge is out of bounds before its pixels are kept, so one image cannot dominate memory. */
    private fun decode(bytes: ByteArray): ImageBitmap? {
        val image = try {
            SkiaImage.makeFromEncoded(bytes)
        } catch (_: Exception) {
            return null
        }
        if (image.width !in 1..MAX_IMAGE_EDGE || image.height !in 1..MAX_IMAGE_EDGE) {
            image.close()
            return null
        }
        return image.toComposeImageBitmap()
    }

    private enum class ArtworkKind { REMOTE, LOCAL_FILE, TELEGRAM }

    private companion object {
        const val CACHE_ENTRIES = 32
        const val MAX_CONCURRENT_FETCHES = 4
        const val FETCH_TIMEOUT_MS = 15_000L
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_IMAGE_EDGE = 1024
        const val MAX_TELEGRAM_LOOKUP_BYTES = 64 * 1024
        const val MAX_TELEGRAM_LOOKUP_CACHE = 512
    }
}

/** Android's `tgart://<fileId>?t=<title>&a=<artist>` model. */
internal fun telegramArtworkUri(fileId: Int, title: String?, artist: String?): String? {
    if (fileId <= 0 && title.isNullOrBlank()) return null
    val encode = { value: String -> URLEncoder.encode(value, "UTF-8").replace("+", "%20") }
    return buildString {
        append("tgart://").append(fileId.coerceAtLeast(0))
        val query = buildList {
            title?.takeIf(String::isNotBlank)?.let { add("t=" + encode(it)) }
            artist?.takeIf(String::isNotBlank)?.let { add("a=" + encode(it)) }
        }
        if (query.isNotEmpty()) append('?').append(query.joinToString("&"))
    }
}

/** Only public http and https locations are requested. A location with user information or without a host is refused. */
private fun isPublicWebImage(uri: String): Boolean {
    val location = try {
        URI(uri)
    } catch (_: Exception) {
        return false
    }
    val scheme = location.scheme?.lowercase()
    return (scheme == "http" || scheme == "https") && location.host != null && location.rawUserInfo == null
}

/**
 * Reads at most [maxBytes] of the body. A larger body is refused whole. The caller reads inside `execute`, so the
 * connection is released when that block ends, whatever the outcome.
 */
private suspend fun HttpResponse.readBoundedBytes(maxBytes: Int): ByteArray? {
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

/**
 * One remote image in a fixed frame. The held image is shown at once when it exists. Otherwise the frame shows the
 * placeholder until the load decodes, and it keeps the placeholder when the load fails. A list row or avatar never
 * shows an image from a previous uri while the new one loads, because a recycled slot would show another item's
 * image. The player cover keeps its image instead; see DesktopQueueArtwork.
 */
@Composable
internal fun DesktopRemoteArtwork(uri: String?, loader: DesktopArtworkLoader, modifier: Modifier) {
    val overrides by loader.overrides.collectAsState()
    val shown = loader.displayUri(uri, overrides)
    val image by produceState<ImageBitmap?>(initialValue = loader.cached(uri), key1 = uri, key2 = shown) {
        val held = loader.cached(uri)
        value = held
        if (held == null) value = loader.load(uri)
    }
    val loaded = image
    if (loaded == null) {
        DesktopArtworkPlaceholder(modifier)
    } else {
        Image(bitmap = loaded, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    }
}

/**
 * The player cover: the artwork of the occurrence the slot names, or of the current occurrence when it names none.
 * It keeps the cover on screen while the next image loads, so a track change does not blank the frame.
 */
@Composable
internal fun DesktopQueueArtwork(occurrenceId: String?, graph: DesktopSessionGraph, modifier: Modifier) {
    val queue by graph.queue.state.collectAsState()
    val target = occurrenceId ?: queue.currentOccurrenceId
    val uri = queue.entries.firstOrNull { it.occurrenceId == target }?.item?.artworkUri
    val loader = graph.artworkLoader
    val overrides by loader.overrides.collectAsState()
    val shown = loader.displayUri(uri, overrides)
    val image = rememberCoverImage(key = shown, cached = { loader.cached(uri) }, load = { loader.load(uri) })
    if (image == null) DesktopArtworkPlaceholder(modifier)
    else Image(bitmap = image, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}
