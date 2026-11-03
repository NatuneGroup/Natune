package moe.rukamori.archivetune.desktop

import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import moe.rukamori.archivetune.canvas.CanvasSource
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkLimits
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkReader
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkResult
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkSource
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.ui.menu.CanvasSaveMenuHost
import moe.rukamori.archivetune.shared.ui.menu.CanvasSaveSource

/**
 * The save-canvas host the shared menu mounts: the canvas owner resolves each provider's animation url and this host
 * stores the picked file as the song's custom canvas. The url list is kept on the owner between the two calls, so a
 * pick never re-runs the provider sweep.
 */
internal class DesktopCanvasMenuHost(
    private val canvas: DesktopCanvasOwner,
    private val graph: DesktopSessionGraph,
) : CanvasSaveMenuHost {
    override suspend fun fetchSources(songId: String): List<CanvasSaveSource> {
        val item = resolveItem(songId) ?: return emptyList()
        return canvas.fetchSaveSources(item).map { (provider) -> CanvasSaveSource(provider.name, provider.saveLabel()) }
    }

    override suspend fun save(songId: String, source: CanvasSaveSource): Boolean =
        canvas.saveCanvas(songId, source.key)

    private suspend fun resolveItem(songId: String): ContentItem? =
        graph.queue.state.value.entries
            .firstOrNull { it.item.ref.id == songId && it.item.ref.kind == ContentKind.SONG }?.item
            ?: try {
                graph.library.findSong(songId, null)
            } catch (_: Exception) {
                null
            }
}

/** The label the picker shows for a source, as the baseline dialog named its rows. */
internal fun CanvasSource.saveLabel(): String = when (this) {
    CanvasSource.BETTER_LYRICS -> "Better Lyrics"
    CanvasSource.APPLE_MUSIC -> "Apple Music"
    CanvasSource.TIDAL -> "Tidal"
    CanvasSource.SPOTIFY -> "Spotify"
    CanvasSource.ALL -> "All sources"
}

/** A downloaded canvas file kept in memory until the repository copies it, as a picked file is a source. */
internal class DesktopBytesArtworkSource(
    private val bytes: ByteArray,
    override val fileExtension: String?,
) : CustomArtworkSource {
    override val mimeType: String? = null

    override suspend fun open(): CustomArtworkReader = StreamArtworkReader(ByteArrayInputStream(bytes))
}

private class StreamArtworkReader(private val stream: InputStream) : CustomArtworkReader {
    override suspend fun read(buffer: ByteArray): Int = stream.read(buffer)

    override suspend fun close() {
        stream.close()
    }
}

/** The path extension of a canvas url, lowercased and query-stripped; the repository decides whether it is accepted. */
internal fun canvasUrlExtension(url: String): String? =
    url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        .lowercase(Locale.ROOT).takeIf(String::isNotBlank)

/** Downloads one canvas url within the repository's bound. A larger or empty body refuses whole, as artwork reads do. */
internal suspend fun downloadDesktopCanvas(graph: DesktopSessionGraph, url: String): ByteArray? =
    graph.http.prepareGet(url).execute { response ->
        if (!response.status.isSuccess()) return@execute null
        response.readCanvasBytes(CustomArtworkLimits.MAX_CANVAS_BYTES.toInt())
    }

private suspend fun HttpResponse.readCanvasBytes(maxBytes: Int): ByteArray? {
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
        chunk.copyInto(bytes, offset, 0, chunk.size)
        offset += chunk.size
    }
    return bytes
}
