package moe.rukamori.archivetune.desktop

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.awt.EventQueue
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.desktop.playback.NativeVideoArtwork
import moe.rukamori.archivetune.desktop.playback.VideoArtworkState
import moe.rukamori.archivetune.desktop.playback.openWithOwnership
import moe.rukamori.archivetune.shared.domain.playback.PlaybackClock
import moe.rukamori.archivetune.shared.domain.settings.canvasLoopPositionMs
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeControls
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeState
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Image as SkiaImage

/** Shared frame feeds prevent the cover and its backdrop from opening the same silent decoder twice. */
internal class DesktopCanvasFrames(private val graph: DesktopSessionGraph, private val owner: DesktopCanvasOwner) {
    class Feed(val target: DesktopCanvasTarget) {
        val frame = mutableStateOf<SkiaImage?>(null)
        var users = 0
        var job: Job? = null
    }
    private val feeds = HashMap<DesktopCanvasTarget, Feed>()

    fun acquire(target: DesktopCanvasTarget): Feed = synchronized(feeds) {
        val feed = feeds.getOrPut(target) { Feed(target) }
        feed.users++
        if (feed.job == null) feed.job = owner.scope.launch(CanvasUiThread) { run(feed) }
        feed
    }

    fun release(feed: Feed) {
        val stop = synchronized(feeds) {
            feed.users--
            if (feed.users > 0) return
            feeds.remove(feed.target, feed)
            feed.job.also { feed.job = null }
        }
        stop?.cancel()
    }

    private suspend fun run(feed: Feed) {
        val target = feed.target
        var bitmap: Bitmap? = null
        var pixels: Pixmap? = null
        var reader: NativeVideoArtwork? = null
        try {
            val uri = URI(target.uri)
            val input = if (uri.scheme == "file") "file:" + Paths.get(uri).toString() else target.uri
            reader = openWithOwnership { NativeVideoArtwork.open(input, null, 720) }
                ?: error("Canvas decoder unavailable")
            var sequence = 0L
            var capacity = 0L
            var durationMs: Long? = null
            val initial = graph.player.snapshot.value ?: return
            val anchor = PlaybackClock.positionMs(initial, SystemMonotonicClock.nowNanos())
            reader.setActive(true)
            while (currentCoroutineContext().isActive && owner.target.value == target) {
                target.provider?.let { check(owner.remoteAllowed(it)) }
                val playback = graph.player.snapshot.value?.takeIf { it.occurrenceId == target.occurrenceId } ?: break
                val advancing = PlaybackClock.isInterpolating(playback)
                val position = PlaybackClock.positionMs(playback, SystemMonotonicClock.nowNanos())
                reader.setClock(desktopCanvasPosition(position, anchor, durationMs), advancing, playback.playbackSpeed)
                val (info, copied) = reader.copyFrame(sequence, pixels?.addr ?: 0L, capacity) ?: break
                info.durationUs?.div(1000L)?.takeIf { it > 0 }?.let { durationMs = it }
                check(info.state != VideoArtworkState.FAILED) { "Canvas decode failed" }
                check(info.state != VideoArtworkState.ENDED || durationMs != null) { "Canvas duration unavailable" }
                if (copied < 0 || (info.width > 0 && bitmap == null)) {
                    require(info.width in 1..4096 && info.height in 1..4096 && info.width.toLong() * info.height <= 1920L * 1080)
                    pixels?.close()
                    bitmap?.close()
                    pixels = null
                    val allocated = Bitmap()
                    bitmap = allocated
                    check(allocated.allocPixels(ImageInfo(info.width, info.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)))
                    pixels = allocated.peekPixels() ?: error("Canvas frame unavailable")
                    capacity = info.width.toLong() * info.height * 4L
                    sequence = 0L
                } else if (copied == 1) {
                    sequence = info.sequence
                    val written = requireNotNull(bitmap)
                    written.notifyPixelsChanged()
                    val previous = feed.frame.value
                    feed.frame.value = SkiaImage.makeFromBitmap(written)
                    previous?.close()
                }
                delay(if (advancing || sequence == 0L) 16L else 250L)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("canvas stopped: ${failure.javaClass.simpleName}")
            owner.failed(target)
        } catch (_: LinkageError) {
            graph.report("canvas stopped: native decoder unavailable")
            owner.failed(target)
        } finally {
            reader?.close()
            val shown = feed.frame.value
            feed.frame.value = null
            shown?.close()
            pixels?.close()
            bitmap?.close()
        }
    }
}

/** A real audio position modulo a real container duration; no advancing stand-in playback clock. */
internal fun desktopCanvasPosition(audioMs: Long, anchorMs: Long, durationMs: Long?): Long =
    canvasLoopPositionMs(audioMs, anchorMs, durationMs)

@Composable
internal fun DesktopCanvasSurface(
    occurrenceId: String?, graph: DesktopSessionGraph, owner: DesktopCanvasOwner,
    frames: DesktopCanvasFrames, active: Boolean, modifier: Modifier,
) {
    val current by owner.target.collectAsState()
    val target = current?.takeIf { it.occurrenceId == occurrenceId }
    Box(modifier) {
        DesktopQueueArtwork(occurrenceId, graph, Modifier.fillMaxSize())
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
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawImageRect(
                                image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                                Rect.makeXYWH((size.width - width) / 2, (size.height - height) / 2, width, height),
                            )
                        }
                    }
                })
            }
        }
    }
}

@Composable
internal fun DesktopCanvasArtwork(
    occurrenceId: String?, graph: DesktopSessionGraph, owner: DesktopCanvasOwner, active: Boolean, modifier: Modifier,
) {
    val target by owner.target.collectAsState()
    Box(modifier) {
        if (target == null) DesktopPlayerArtwork(occurrenceId, graph, active, Modifier.fillMaxSize())
        else DesktopQueueArtwork(occurrenceId, graph, Modifier.fillMaxSize())
        owner.notice?.takeIf { active }?.let { key ->
            Text(
                DesktopStrings[key], style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }
    }
}

internal class DesktopCanvasControls(
    controls: PlayerNativeControls, owner: DesktopCanvasOwner, scope: CoroutineScope,
) : PlayerNativeControls by controls {
    override val state: StateFlow<PlayerNativeState> = combine(controls.state, owner.target) { native, target ->
        native.copy(canvasAvailable = target != null && target.occurrenceId == native.occurrenceId)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), controls.state.value)
}

private val CanvasUiThread = Executor { EventQueue.invokeLater(it) }.asCoroutineDispatcher()
