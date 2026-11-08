package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import moe.rukamori.archivetune.shared.data.platform.ios.iosCanvasConnectivity
import moe.rukamori.archivetune.shared.domain.customartwork.*
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.menu.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

private class IosCanvasSaveHost(private val graph: IosSessionGraph, private val item: ContentItem) : CanvasSaveMenuHost {
    private val urls = mutableMapOf<String, String>()
    private val providers = IosCanvasProviders(graph.http, { graph.settings.snapshots.value }, { token, expires ->
        graph.settings.edit { this[SpotifyAccessTokenKey] = token; this[SpotifyAccessTokenExpiresAtKey] = expires }
    }, graph::report)
    private fun check(provider: CanvasHealthProvider) {
        check(graph.settings.snapshots.value?.canvasRemoteAllowed(provider, iosCanvasConnectivity()) == true)
    }
    override suspend fun fetchSources(songId: String): List<CanvasSaveSource> {
        require(songId == item.ref.id)
        urls.clear()
        for (provider in canvasResolveProviderOrder()) {
            try {
                check(provider)
                val result = providers.fetch(provider, item.title, item.artists.firstOrNull()?.name ?: item.subtitle,
                    songId, iosCanvasStorefront(), ::check)
                val url = result?.preferredAnimationUrl ?: result?.preferredVerticalAnimationUrl ?: continue
                if (canvasLoopUrlAllowed(url)) urls[provider.name] = url
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
        }
        return urls.keys.map { key -> CanvasSaveSource(key, key.replace('_', ' ')) }
    }
    override suspend fun save(songId: String, source: CanvasSaveSource): Boolean {
        require(songId == item.ref.id)
        val url = urls[source.key] ?: return false
        val provider = CanvasHealthProvider.valueOf(source.key)
        check(provider)
        val bytes = graph.http.prepareGet(url).execute { response ->
            require(response.status.isSuccess())
            val channel = response.bodyAsChannel()
            val chunks = mutableListOf<ByteArray>(); val buffer = ByteArray(8192); var total = 0
            try { while (true) {
                currentCoroutineContext().ensureActive(); check(provider)
                val count = channel.readAvailable(buffer)
                if (count < 0) break
                if (count == 0) { yield(); continue }
                require(total <= CustomArtworkLimits.MAX_CANVAS_BYTES - count)
                chunks.add(buffer.copyOf(count)); total += count
            } } finally { channel.cancel(null) }
            require(total > 0)
            ByteArray(total).also { output -> var offset = 0; chunks.forEach { it.copyInto(output, offset); offset += it.size } }
        }
        check(provider)
        val result = graph.customArtwork.setCanvas(songId, object : CustomArtworkSource {
            override val mimeType: String? = null
            override val fileExtension: String? = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").takeIf { it.isNotBlank() }
            override suspend fun open(): CustomArtworkReader = object : CustomArtworkReader {
                var offset = 0
                override suspend fun read(buffer: ByteArray): Int {
                    if (offset >= bytes.size) return -1
                    val count = minOf(buffer.size, bytes.size - offset)
                    bytes.copyInto(buffer, 0, offset, offset + count); offset += count; return count
                }
                override suspend fun close() { }
            }
        })
        return result == CustomArtworkResult.SAVED
    }
}

@Composable
internal fun IosCanvasSaveDialog(menu: IosItemMenuState, graph: IosSessionGraph) {
    menu.canvasSaveTarget?.let { item ->
        val host = remember(graph, item) { IosCanvasSaveHost(graph, item) }
        val state = rememberCanvasSaveMenuState(item.ref.id, host) { result -> menu.canvasResult = result; menu.canvasSaveTarget = null }
        var started by remember(state) { mutableStateOf(false) }
        LaunchedEffect(state) { state.request(); started = true }
        CanvasSourcePicker(state)
        LaunchedEffect(started, state.fetching, state.saving, state.sources) {
            if (started && !state.fetching && !state.saving && state.sources == null) menu.canvasSaveTarget = null
        }
    }
    menu.canvasResult?.let { message -> AlertDialog(onDismissRequest = { menu.canvasResult = null },
        text = { Text(stringResource(message)) }, confirmButton = { TextButton(onClick = { menu.canvasResult = null }) {
            Text(stringResource(Res.string.m6_save))
        } }) }
}
