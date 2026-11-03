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
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * The save-canvas host the shared menu mounts: the canvas owner resolves each provider's animation url and this host
 * stores the picked file as the song's custom canvas. The url list is kept on the owner between the two calls, so a
 * pick never re-runs the provider sweep.
 */
internal class DesktopCanvasMenuHost(
    private val canvas: DesktopCanvasOwner,
    private val item: () -> ContentItem?,
) : CanvasSaveMenuHost {
    override suspend fun fetchSources(songId: String): List<CanvasSaveSource> {
        // The menu's own item is the metadata source: a song that is not queued or stored still resolves.
        val held = item()?.takeIf { it.ref.id == songId && it.ref.kind == ContentKind.SONG } ?: return emptyList()
        return canvas.fetchSaveSources(held).map { (provider) ->
            CanvasSaveSource(provider.name, getString(provider.saveLabel()))
        }
    }

    override suspend fun save(songId: String, source: CanvasSaveSource): Boolean =
        canvas.saveCanvas(songId, source.key)
}

/** The label the picker shows for a source, as the baseline dialog named its rows. */
internal fun CanvasSource.saveLabel(): StringResource = when (this) {
    CanvasSource.BETTER_LYRICS -> Res.string.m6_canvas_better_lyrics
    CanvasSource.APPLE_MUSIC -> Res.string.m6_canvas_apple_music
    CanvasSource.TIDAL -> Res.string.m6_canvas_tidal
    CanvasSource.SPOTIFY -> Res.string.m6_canvas_spotify
    CanvasSource.ALL -> Res.string.m6_canvas_source_all
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
