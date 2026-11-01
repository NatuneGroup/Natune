@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.useContents
import kotlin.math.max
import kotlin.math.min
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkLimits
import moe.rukamori.archivetune.shared.ui.player.rememberCoverImage
import org.jetbrains.skia.Image as SkiaImage
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation

/**
 * Fetches and decodes the artwork the iOS app shows: a public http or https image, requested with no cookie and no
 * account header, so a thumbnail never carries a credential. A missing, oversized, slow or undecodable image reads as
 * no image, and its frame keeps the placeholder. Decoded images sit in a bounded least-recently-used cache.
 */
internal class IosArtworkLoader(private val http: HttpClient) {
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, ImageBitmap>()
    private val permits = Semaphore(MAX_CONCURRENT_FETCHES)

    /**
     * The image already decoded for [uri], read during composition without a network request. While a load holds the
     * cache this answers null, and the slot then waits for [load] instead.
     */
    fun cached(uri: String?): ImageBitmap? {
        val key = uri ?: return null
        if (!lock.tryLock()) return null
        return try {
            cache[key]
        } finally {
            lock.unlock()
        }
    }

    /** Loads [uri] once, within the time, size and concurrency bounds. Null means no image can be shown. */
    suspend fun load(uri: String?): ImageBitmap? {
        val target = uri?.takeIf { isPublicWebImage(it) || it.startsWith("file:") } ?: return null
        lock.withLock { cache[target]?.let { held -> cache.remove(target); cache[target] = held; return held } }
        val bitmap = try {
            withTimeout(FETCH_TIMEOUT_MS) { permits.withPermit { fetchAndDecode(target) } }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        lock.withLock {
            cache[target] = bitmap
            while (cache.size > CACHE_ENTRIES) cache.remove(cache.keys.first())
        }
        return bitmap
    }

    private suspend fun fetchAndDecode(uri: String): ImageBitmap? {
        if (uri.startsWith("file:")) return readLocalImage(uri)
        val bytes = http.prepareGet(uri).execute { response ->
            if (!response.status.isSuccess()) return@execute null
            response.readBoundedBytes(MAX_IMAGE_BYTES)
        } ?: return null
        return withContext(Dispatchers.Default) { decode(bytes) }
    }

    /**
     * A file the app stored itself, such as the picked player background. A picked photo is often larger than a
     * thumbnail and can be HEIC, so UIKit decodes and downsamples it to the cache's edge bound before Skia keeps the
     * bounded copy, the way the desktop loader subsamples a picked cover instead of refusing it.
     */
    private suspend fun readLocalImage(uri: String): ImageBitmap? = withContext(Dispatchers.Default) {
        val url = NSURL.URLWithString(uri)?.takeIf { it.scheme == "file" } ?: return@withContext null
        val path = url.path ?: return@withContext null
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: return@withContext null
        val fileBytes = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: return@withContext null
        if (fileBytes <= 0L || fileBytes > CustomArtworkLimits.MAX_COVER_BYTES) return@withContext null
        val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
        val image = UIImage(data = data) ?: return@withContext null
        val (width, height) = image.size.useContents { width to height }
        if (width <= 0.0 || height <= 0.0) return@withContext null
        val scale = min(1.0, MAX_IMAGE_EDGE.toDouble() / max(width, height))
        val targetWidth = max(1.0, width * scale)
        val targetHeight = max(1.0, height * scale)
        // The renderer draws at the screen's scale by default, which would triple the kept pixels.
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { this.scale = 1.0 }
        val scaled = UIGraphicsImageRenderer(size = CGSizeMake(targetWidth, targetHeight), format = format)
            .imageWithActions { _ -> image.drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight)) }
        val png = UIImagePNGRepresentation(scaled) ?: return@withContext null
        val bytes = png.bytes?.readBytes(png.length.toInt()) ?: return@withContext null
        decode(bytes)
    }

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

    private companion object {
        const val CACHE_ENTRIES = 32
        const val MAX_CONCURRENT_FETCHES = 4
        const val FETCH_TIMEOUT_MS = 15_000L
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_IMAGE_EDGE = 1024
    }
}

/** Only public http and https locations are requested. A location with user information or without a host is refused. */
private fun isPublicWebImage(uri: String): Boolean {
    val location = try {
        Url(uri)
    } catch (_: Exception) {
        return false
    }
    val scheme = location.protocol.name.lowercase()
    return (scheme == "http" || scheme == "https") && location.host.isNotEmpty() && location.user == null
}

/** Reads at most [maxBytes] of the body. A larger body is refused whole. */
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
 * placeholder until the load decodes, and keeps it when the load fails. A list row or avatar never shows the previous
 * uri's image, because a recycled slot would show another item's image. The player cover keeps its image instead;
 * see IosQueueArtwork.
 */
@Composable
internal fun IosRemoteArtwork(uri: String?, loader: IosArtworkLoader, modifier: Modifier) {
    val image by produceState(initialValue = loader.cached(uri), key1 = uri) {
        val held = loader.cached(uri)
        value = held
        if (held == null) value = loader.load(uri)
    }
    val loaded = image
    if (loaded == null) {
        IosArtworkPlaceholder(modifier)
    } else {
        Image(bitmap = loaded, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    }
}

/**
 * The player cover: the artwork of the occurrence the slot names, or of the current occurrence when it names none.
 * It keeps the cover on screen while the next image loads, so a track change does not blank the frame.
 */
@Composable
internal fun IosQueueArtwork(occurrenceId: String?, graph: IosSessionGraph, modifier: Modifier) {
    val queue by graph.queue.state.collectAsState()
    val target = occurrenceId ?: queue.currentOccurrenceId
    val uri = queue.entries.firstOrNull { it.occurrenceId == target }?.item?.artworkUri
    val loader = graph.artworkLoader
    val image = rememberCoverImage(key = uri, cached = { loader.cached(uri) }, load = { loader.load(uri) })
    if (image == null) IosArtworkPlaceholder(modifier)
    else Image(bitmap = image, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}

/** The frame a slot keeps while its image is absent, loading or unreadable. */
@Composable
internal fun IosArtworkPlaceholder(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {}
}
