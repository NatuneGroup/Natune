/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
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
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.skia.Image as SkiaImage

/**
 * Fetches and decodes the artwork the desktop shows: a public http or https image, requested with no cookie and no
 * account header, so a thumbnail never carries a credential. A missing, oversized, slow or undecodable image reads as
 * no image, and its frame keeps the placeholder. Decoded images sit in a bounded least-recently-used cache.
 */
internal class DesktopArtworkLoader(private val http: HttpClient) {
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, ImageBitmap>(CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > CACHE_ENTRIES
    }
    private val permits = Semaphore(MAX_CONCURRENT_FETCHES)

    /** The image already decoded for [uri]. It costs no network work and is null when nothing is held. */
    fun cached(uri: String?): ImageBitmap? {
        if (uri == null) return null
        return synchronized(lock) { cache[uri] }
    }

    /** Loads [uri] once, within the time, size and concurrency bounds. Null means no image can be shown. */
    suspend fun load(uri: String?): ImageBitmap? {
        if (uri == null || !isPublicWebImage(uri)) return null
        cached(uri)?.let { return it }
        val bitmap = try {
            withTimeout(FETCH_TIMEOUT_MS) { permits.withPermit { fetchAndDecode(uri) } }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (bitmap != null) synchronized(lock) { cache[uri] = bitmap }
        return bitmap
    }

    private suspend fun fetchAndDecode(uri: String): ImageBitmap? {
        val bytes = http.prepareGet(uri).execute { response ->
            if (!response.status.isSuccess()) return@execute null
            response.readBoundedBytes(MAX_IMAGE_BYTES)
        } ?: return null
        return withContext(Dispatchers.Default) { decode(bytes) }
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
 * placeholder until the load decodes, and it keeps the placeholder when the load fails. A slot never shows an image
 * from a previous uri while the new one is loading.
 */
@Composable
internal fun DesktopRemoteArtwork(uri: String?, loader: DesktopArtworkLoader, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(initialValue = loader.cached(uri), key1 = uri) {
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

/** The player cover: the artwork of the occurrence the slot names, or of the current occurrence when it names none. */
@Composable
internal fun DesktopQueueArtwork(occurrenceId: String?, graph: DesktopSessionGraph, modifier: Modifier) {
    val queue by graph.queue.state.collectAsState()
    val target = occurrenceId ?: queue.currentOccurrenceId
    val uri = queue.entries.firstOrNull { it.occurrenceId == target }?.item?.artworkUri
    DesktopRemoteArtwork(uri, graph.artworkLoader, modifier)
}
