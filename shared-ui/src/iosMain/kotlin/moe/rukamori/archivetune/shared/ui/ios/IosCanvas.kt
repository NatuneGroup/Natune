@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import moe.rukamori.archivetune.shared.data.platform.ios.IosCanvasCacheStore
import moe.rukamori.archivetune.shared.data.platform.ios.iosCanvasConnectivity
import moe.rukamori.archivetune.shared.data.platform.ios.iosNetworkAvailable
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkDirectory
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkKeys
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkLimits
import moe.rukamori.archivetune.shared.domain.playback.PlaybackClock
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.settings.CanvasConnectivity
import moe.rukamori.archivetune.shared.domain.settings.CanvasHealthProvider
import moe.rukamori.archivetune.shared.domain.settings.CanvasWifiOnlyKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SpotifyAccessTokenExpiresAtKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyAccessTokenKey
import moe.rukamori.archivetune.shared.domain.settings.canvasLoopPositionMs
import moe.rukamori.archivetune.shared.domain.settings.canvasLoopUrlAllowed
import moe.rukamori.archivetune.shared.domain.settings.canvasPlaybackEnabled
import moe.rukamori.archivetune.shared.domain.settings.canvasPreferenceProbe
import moe.rukamori.archivetune.shared.domain.settings.canvasRemoteAllowed
import moe.rukamori.archivetune.shared.domain.settings.canvasResolveProviderOrder
import moe.rukamori.archivetune.shared.domain.settings.lowDataMode
import moe.rukamori.archivetune.shared.domain.songmetadata.canonicalSong
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeControls
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeState
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.content_canvas_failed
import moe.rukamori.archivetune.shared.ui.resources.content_canvas_network_unknown
import moe.rukamori.archivetune.shared.ui.resources.content_canvas_not_found
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image as SkiaImage
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.CoreGraphics.CGImageRelease
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode
import platform.Foundation.NSLock
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.currentLocale
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.free
import platform.posix.realpath

/** The canvas the current occurrence resolved to: the loop URI, whether it is a still, and which provider answered. */
internal data class IosCanvasTarget(
    val occurrenceId: String,
    val uri: String,
    val image: Boolean,
    val provider: CanvasHealthProvider? = null,
)

/**
 * The iOS canvas owner: the same resolver the desktop keeps, over the same providers and the same stored-choice and
 * connectivity gates. A custom canvas the user picked wins first; otherwise the remote chain answers the loop for
 * the current occurrence, each provider tried in order behind the measured-link rule [iosCanvasConnectivity]
 * reports. Nothing is resolved while the player surface is hidden or the stored choice refuses it.
 */
internal class IosCanvasOwner(private val graph: IosSessionGraph) {
    private val lifetime = SupervisorJob(graph.scope.coroutineContext[Job])
    internal val scope = CoroutineScope(graph.scope.coroutineContext + lifetime)
    private val visible = MutableStateFlow(false)
    val target = MutableStateFlow<IosCanvasTarget?>(null)
    var notice: String? by mutableStateOf(null)
        private set
    private var closed = false
    private val providers = IosCanvasProviders(
        http = graph.http,
        preferences = { graph.settings.snapshots.value },
        persistSpotifyToken = { accessToken, expiresAtMs ->
            graph.settings.edit {
                this[SpotifyAccessTokenKey] = accessToken
                this[SpotifyAccessTokenExpiresAtKey] = expiresAtMs
            }
        },
        report = graph::report,
    )
    private val online = MutableStateFlow(iosNetworkAvailable())
    private val policy: (CanvasHealthProvider) -> Unit = { provider ->
        check(!closed && visible.value && remoteAllowed(provider)) { "Canvas request denied by current preferences/network" }
    }

    init {
        scope.launch {
            graph.customArtwork.ensureLoaded()
            combine(
                graph.queue.state, graph.settings.snapshots, graph.customArtwork.overrides, online, visible,
            ) { queue, preferences, overrides, onlineNow, showing ->
                CanvasInput(
                    queue.entries.firstOrNull { it.occurrenceId == queue.currentOccurrenceId },
                    preferences, overrides.canvases, onlineNow, showing,
                )
            }.distinctUntilChanged { old, new ->
                old.row == new.row && old.canvases == new.canvases && old.online == new.online &&
                    old.showing == new.showing &&
                    canvasPreferenceProbe(old.preferences) == canvasPreferenceProbe(new.preferences)
            }.collectLatest { input ->
                target.value = null
                notice = null
                val row = input.row ?: return@collectLatest
                val preferences = input.preferences ?: return@collectLatest
                if (!input.showing || !preferences.canvasPlaybackEnabled() || row.item.ref.kind != ContentKind.SONG) {
                    return@collectLatest
                }
                try {
                    val local = graph.customArtwork.canvasUri(row.item.ref.id)
                    if (local != null) {
                        val path = withContext(Dispatchers.Default) { validatedIosCanvasFile(local) }
                        val extension = path.substringAfterLast('.').lowercase()
                        target.value = IosCanvasTarget(
                            row.occurrenceId, "file://$path",
                            CustomArtworkKeys.isImageCanvasExtension(extension) && extension != "gif",
                        )
                        return@collectLatest
                    }
                    // The coarse gate mirrors the desktop's before the measured link existed; on iOS the WWAN flag
                    // is the same measurement the policy asks for, so a Wi-Fi link is never refused for a
                    // cellular-only reason.
                    val connectivity = iosCanvasConnectivity()
                    if (!connectivity.online ||
                        (preferences[CanvasWifiOnlyKey, false] && !connectivity.wifi) ||
                        (preferences.lowDataMode() && connectivity.metered)
                    ) {
                        notice = "content_canvas_network_unknown"
                        return@collectLatest
                    }
                    val canonical = row.item.canonicalSong()
                    val artist = canonical.artists.firstOrNull()?.name
                        ?: canonical.subtitle.takeIf(String::isNotBlank)
                        ?: return@collectLatest
                    val result = withTimeoutOrNull(CANVAS_RESOLVE_TIMEOUT_MS) {
                        resolve(row.item.ref.id, canonical.title, artist)
                    }
                    target.value = result?.let { (uri, provider) ->
                        IosCanvasTarget(row.occurrenceId, uri, image = false, provider = provider)
                    }
                    if (result == null) notice = "content_canvas_not_found"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    notice = "content_canvas_failed"
                    graph.report("canvas not resolved: ${failure::class.simpleName}")
                }
            }
        }
        // Reachability is a local flag read, not traffic; it is refreshed only while the canvas surface shows.
        scope.launch {
            visible.collectLatest { showing ->
                while (showing) {
                    online.value = iosNetworkAvailable()
                    delay(REACHABILITY_POLL_MS)
                }
            }
        }
    }

    private suspend fun resolve(id: String, title: String, artist: String): Pair<String, CanvasHealthProvider>? {
        val storefront = iosCanvasStorefront()
        for (provider in canvasResolveProviderOrder()) {
            if (!remoteAllowed(provider)) continue
            policy(provider)
            val artwork = providers.fetch(provider, title, artist, id, storefront, policy)
            policy(provider)
            val url = artwork?.preferredAnimationUrl ?: artwork?.preferredVerticalAnimationUrl
            if (url != null && canvasLoopUrlAllowed(url)) return url to provider
        }
        return null
    }

    /** The provider gate the settings page shows: the same stored choice and connectivity rule per provider. */
    internal fun remoteAllowed(provider: CanvasHealthProvider): Boolean {
        val preferences = graph.settings.snapshots.value ?: return false
        return preferences.canvasRemoteAllowed(provider, iosCanvasConnectivity())
    }

    fun setVisible(showing: Boolean) {
        visible.value = showing
    }

    internal fun failed(expected: IosCanvasTarget) {
        if (target.value != expected) return
        target.value = null
        notice = "content_canvas_failed"
    }

    fun close() {
        closed = true
        target.value = null
        lifetime.cancel()
    }

    private data class CanvasInput(
        val row: ContentOccurrence?,
        val preferences: PreferenceSnapshot?,
        val canvases: Map<String, String>,
        val online: Boolean,
        val showing: Boolean,
    )

    private companion object {
        const val CANVAS_RESOLVE_TIMEOUT_MS = 45_000L
        const val REACHABILITY_POLL_MS = 2_000L
    }
}

/** The storefront the Apple/TIDAL lookups read: the region the system locale reports, or "us". */
private fun iosCanvasStorefront(): String =
    (NSLocale.currentLocale.objectForKey(NSLocaleCountryCode) as? String)
        ?.lowercase()?.takeIf { it.length == 2 } ?: "us"

/** The custom_canvas folder the file validation keeps a picked canvas inside, as a real path. */
private fun iosCanvasRoot(): String? {
    val manager = NSFileManager.defaultManager
    val root = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path
        ?: return null
    return "$root/${CustomArtworkDirectory.CANVASES.directoryName}"
}

private fun iosRealPath(path: String): String? = memScoped {
    val resolved = realpath(path, null) ?: return@memScoped null
    try {
        resolved.toKString()
    } finally {
        free(resolved)
    }
}

/**
 * A picked custom canvas must be a real regular file inside `custom_canvas`, within the size bound — the same
 * validation the desktop applies to the file the picker stored. Answers the real path, or throws like the
 * desktop validator so the caller falls back to the failed notice rather than playing an unsafe file.
 */
private fun validatedIosCanvasFile(uri: String): String {
    val url = NSURL.URLWithString(uri) ?: throw IllegalArgumentException("canvas file URI unreadable")
    require(url.scheme?.lowercase() == "file") { "canvas override is not a file" }
    val path = url.path ?: throw IllegalArgumentException("canvas file URI has no path")
    val realPath = iosRealPath(path) ?: throw IllegalArgumentException("canvas file is missing")
    val root = iosCanvasRoot() ?: throw IllegalArgumentException("canvas folder is missing")
    val realRoot = iosRealPath(root) ?: root
    require(realPath.startsWith("$realRoot/")) { "canvas file is outside its folder" }
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(realPath, null)
        ?: throw IllegalArgumentException("canvas file attributes unavailable")
    require(attributes[NSFileType] == NSFileTypeRegular) { "canvas file is not a regular file" }
    val bytes = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0L
    require(bytes in 1..CustomArtworkLimits.MAX_CANVAS_BYTES) { "canvas file is empty or too large" }
    return realPath
}

/**
 * Shared frame feeds prevent the cover and its backdrop from decoding the same loop twice. A remote loop is
 * downloaded once into the bounded canvas store and decoded from the local file by `AVAssetImageGenerator`,
 * the position inside the loop being the real elapsed audio time modulo the container duration.
 */
internal class IosCanvasFrames(
    private val graph: IosSessionGraph,
    private val owner: IosCanvasOwner,
    private val store: IosCanvasCacheStore,
) {
    class Feed(val target: IosCanvasTarget) {
        val frame = mutableStateOf<ImageBitmap?>(null)
        var users = 0
        var job: Job? = null
    }

    private val feeds = HashMap<IosCanvasTarget, Feed>()
    private val feedsLock = NSLock()

    fun acquire(target: IosCanvasTarget): Feed {
        feedsLock.lock()
        try {
            val feed = feeds.getOrPut(target) { Feed(target) }
            feed.users++
            if (feed.job == null) feed.job = owner.scope.launch(Dispatchers.Default) { run(feed) }
            return feed
        } finally {
            feedsLock.unlock()
        }
    }

    fun release(feed: Feed) {
        feedsLock.lock()
        val stop = try {
            feed.users--
            if (feed.users > 0) {
                null
            } else {
                if (feeds[feed.target] == feed) feeds.remove(feed.target)
                feed.job.also { feed.job = null }
            }
        } finally {
            feedsLock.unlock()
        }
        stop?.cancel()
    }

    private suspend fun run(feed: Feed) {
        val target = feed.target
        try {
            val path = canvasLocalPath(target.uri) ?: error("canvas loop has no local file")
            val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(path), null)
            val generator = AVAssetImageGenerator(asset = asset)
            generator.appliesPreferredTrackTransform = true
            // The K/N AVFoundation binding hides the synchronous track list, so a video-less asset is refused
            // by its own decoder instead: a whole-timeline probe only yields a frame when there is video.
            generator.requestedTimeToleranceBefore = asset.duration
            generator.requestedTimeToleranceAfter = asset.duration
            feed.frame.value = iosCanvasFrameAt(generator, 0L) ?: error("canvas loop has no video track")
            generator.requestedTimeToleranceBefore = CMTimeMake(0, 1)
            generator.requestedTimeToleranceAfter = CMTimeMake(0, 1)
            val durationMs = CMTimeGetSeconds(asset.duration)
                .takeIf { it.isFinite() && it > 0.0 }
                ?.let { (it * 1000.0).toLong() }
            val initial = graph.player.snapshot.value ?: return
            val anchor = PlaybackClock.positionMs(initial, SystemMonotonicClock.nowNanos())
            while (currentCoroutineContext().isActive && owner.target.value == target) {
                target.provider?.let { check(owner.remoteAllowed(it)) }
                val playback = graph.player.snapshot.value?.takeIf { it.occurrenceId == target.occurrenceId }
                    ?: break
                val advancing = PlaybackClock.isInterpolating(playback)
                val position = PlaybackClock.positionMs(playback, SystemMonotonicClock.nowNanos())
                val bitmap = iosCanvasFrameAt(generator, canvasLoopPositionMs(position, anchor, durationMs))
                if (bitmap != null) feed.frame.value = bitmap
                // The generator decodes synchronously per call, so a faster cadence only burns CPU for the
                // same frame; a paused song holds the frame it has.
                delay(if (advancing) FRAME_DELAY_MS else PAUSED_DELAY_MS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("canvas stopped: ${failure::class.simpleName}")
            owner.failed(target)
        } finally {
            feed.frame.value = null
        }
    }

    /**
     * The local file the decoder reads: a `file:` URI's own path, or the bounded store — the remote loop is
     * downloaded once there and replayed from it until the store trims it, so the same song never re-downloads
     * while its file stays within the cache limit.
     */
    private suspend fun canvasLocalPath(uri: String): String? {
        val url = NSURL.URLWithString(uri) ?: return null
        return when (url.scheme?.lowercase()) {
            "file" -> url.path?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
            "http", "https" -> store.resolve(uri) ?: downloadCanvas(uri)
            else -> null
        }
    }

    private suspend fun downloadCanvas(uri: String): String? {
        val bytes = try {
            graph.http.get(uri).takeIf { it.status.isSuccess() }?.let { response ->
                response.bodyAsChannel().readCanvasBytes(CustomArtworkLimits.MAX_CANVAS_BYTES)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        return store.store(uri, bytes)
    }

    private companion object {
        const val FRAME_DELAY_MS = 100L
        const val PAUSED_DELAY_MS = 400L
    }
}

/** One decoded frame at [mediaMs] inside the loop, or null when the generator has nothing there. */
private fun iosCanvasFrameAt(generator: AVAssetImageGenerator, mediaMs: Long): ImageBitmap? = memScoped {
    val requested = CMTimeMakeWithSeconds(mediaMs / 1000.0, 600)
    val actual = alloc<CMTime>()
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    val image = generator.copyCGImageAtTime(requested, actual.ptr, error.ptr) ?: return@memScoped null
    try {
        val data = UIImagePNGRepresentation(UIImage.imageWithCGImage(image)) ?: return@memScoped null
        val bytes = if (data.length == 0uL) return@memScoped null
        else data.bytes?.readBytes(data.length.toInt()) ?: return@memScoped null
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    } finally {
        CGImageRelease(image)
    }
}

/** Reads at most [maxBytes] of a canvas loop body; a larger file is refused whole. */
private suspend fun io.ktor.utils.io.ByteReadChannel.readCanvasBytes(maxBytes: Long): ByteArray? {
    val chunks = ArrayList<ByteArray>()
    val buffer = ByteArray(8192)
    var size = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = readAvailable(buffer)
        if (count < 0) break
        if (count == 0) {
            yield()
            continue
        }
        if (size + count > maxBytes) return null
        chunks += buffer.copyOf(count)
        size += count
    }
    if (size == 0L) return null
    val bytes = ByteArray(size.toInt())
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(bytes, offset)
        offset += chunk.size
    }
    return bytes
}

/**
 * The canvas slot the player mounts behind the controls: the queue cover underneath, then the decoded loop or
 * still image on top when this occurrence resolved one. An inactive slot decodes nothing.
 */
@Composable
internal fun IosCanvasSurface(
    occurrenceId: String?,
    graph: IosSessionGraph,
    owner: IosCanvasOwner,
    frames: IosCanvasFrames,
    active: Boolean,
    modifier: Modifier,
) {
    val current by owner.target.collectAsState()
    val target = current?.takeIf { it.occurrenceId == occurrenceId }
    Box(modifier) {
        IosQueueArtwork(occurrenceId, graph, Modifier.fillMaxSize())
        if (active && target != null) {
            if (target.image) {
                val image by produceState<ImageBitmap?>(null, target) {
                    value = graph.artworkLoader.load(target.uri)
                    if (value == null) owner.failed(target)
                }
                image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            } else {
                val feed = remember(target) { frames.acquire(target) }
                DisposableEffect(feed) { onDispose { frames.release(feed) } }
                Spacer(Modifier.fillMaxSize().drawBehind {
                    val image = feed.frame.value ?: return@drawBehind
                    val scale = maxOf(size.width / image.width, size.height / image.height)
                    val width = image.width * scale
                    val height = image.height * scale
                    clipRect {
                        drawImage(
                            image = image,
                            srcSize = IntSize(image.width, image.height),
                            dstOffset = IntOffset(
                                ((size.width - width) / 2).toInt(),
                                ((size.height - height) / 2).toInt(),
                            ),
                            dstSize = IntSize(width.toInt(), height.toInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                })
            }
        }
    }
}

/**
 * The artwork slot the player styles show: the queue cover with a notice line when the canvas chain could not
 * show a loop, matching the desktop's notice keys one for one.
 */
@Composable
internal fun IosCanvasArtwork(
    occurrenceId: String?,
    graph: IosSessionGraph,
    owner: IosCanvasOwner,
    active: Boolean,
    modifier: Modifier,
) {
    owner.target.collectAsState()
    Box(modifier) {
        IosQueueArtwork(occurrenceId, graph, Modifier.fillMaxSize())
        owner.notice?.takeIf { active }?.let { key ->
            val text = when (key) {
                "content_canvas_network_unknown" -> stringResource(Res.string.content_canvas_network_unknown)
                "content_canvas_not_found" -> stringResource(Res.string.content_canvas_not_found)
                else -> stringResource(Res.string.content_canvas_failed)
            }
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }
    }
}

/** Reports canvas availability on the player state, so a style that shows a canvas knows one resolved. */
internal class IosCanvasControls(
    controls: PlayerNativeControls,
    owner: IosCanvasOwner,
    scope: CoroutineScope,
) : PlayerNativeControls by controls {
    override val state: StateFlow<PlayerNativeState> =
        combine(controls.state, owner.target) { native, target ->
            native.copy(canvasAvailable = target != null && target.occurrenceId == native.occurrenceId)
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), controls.state.value)
}

/** Builds the owner the iOS player bindings wire into the canvas slot. */
internal fun createIosCanvas(graph: IosSessionGraph): IosCanvasOwner = IosCanvasOwner(graph)
