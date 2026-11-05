package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import java.awt.EventQueue
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.playback.NativeVideoArtwork
import moe.rukamori.archivetune.desktop.playback.VideoArtworkState
import moe.rukamori.archivetune.desktop.playback.openWithOwnership
import moe.rukamori.archivetune.desktop.provider.DESKTOP_VIDEO_MAX_HEIGHT
import moe.rukamori.archivetune.desktop.provider.DesktopVideoArtworkResult
import moe.rukamori.archivetune.desktop.provider.DesktopYouTubeResolver
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackClock
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.settings.EnableVideoPlaybackKey
import moe.rukamori.archivetune.shared.domain.settings.VideoAspectRatioKey
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Image as SkiaImage

/**
 * Finds the video stream behind a music video for the player artwork, as Android's inline video player does when
 * "Enable video playback" is on. An id YouTube serves as a song keeps its cover for the session, and a failed lookup
 * is not asked again for a while, so a song without video costs one request.
 */
internal class DesktopVideoArtwork(
    private val scope: CoroutineScope,
    private val player: MusicPlayerController,
    private val resolver: DesktopYouTubeResolver,
    private val report: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** What the stats-for-nerds overlay shows for a running feed: decode shape, presented pace and recovery count. */
    data class VideoFeedStats(
        val width: Int = 0,
        val height: Int = 0,
        val streamHeight: Int = 0,
        val state: VideoArtworkState? = null,
        val presentedFrames: Long = 0,
        val presentedFps: Float = 0f,
        val recoveries: Int = 0,
        val lastError: String = "",
    )

    /** One reader and its newest frame, shared by every surface that shows [videoId]. */
    class Feed(val videoId: String) {
        val frame = mutableStateOf<SkiaImage?>(null)
        val stats = mutableStateOf(VideoFeedStats())
        internal var users = 0
        internal var job: Job? = null
    }

    private val feeds = HashMap<String, Feed>()
    private sealed interface Answer {
        val atMs: Long

        class Found(val stream: DesktopVideoArtworkResult.Stream, override val atMs: Long) : Answer
        class NoVideo(override val atMs: Long) : Answer
        class Failed(override val atMs: Long) : Answer
    }

    private val lock = Mutex()
    private val answers = object : LinkedHashMap<String, Answer>(ANSWER_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>?): Boolean = size > ANSWER_ENTRIES
    }

    @Volatile
    private var engineMissingReported = false

    /** Called from composition. The first user starts the reader; [release] by the last one stops it. */
    fun acquire(videoId: String): Feed = synchronized(feeds) {
        val feed = feeds.getOrPut(videoId) { Feed(videoId) }
        feed.users++
        if (feed.job == null) feed.job = scope.launch(UiThread) { run(feed) }
        feed
    }

    fun release(feed: Feed) {
        val stop = synchronized(feeds) {
            feed.users--
            if (feed.users > 0) return
            feeds.remove(feed.videoId, feed)
            feed.job.also { feed.job = null }
        }
        stop?.cancel()
    }

    /**
     * Copies frames into a Skia bitmap the native reader writes directly and gives the reader the audio clock, so the
     * picture follows the engine. It runs on the AWT event thread, which also draws the window, so an image is never
     * closed while it is drawn. While the clock holds still it polls slowly.
     */
    private suspend fun run(feed: Feed) {
        var reader = open(feed.videoId) ?: return
        var bitmap: Bitmap? = null
        var pixels: Pixmap? = null
        var capacity = 0L
        var sequence = 0L
        var recoveries = 0
        try {
            reader.setActive(true)
            var presented = 0L
            var windowStartMs = nowMs()
            var windowFrames = 0
            var fps = 0f
            while (currentCoroutineContext().isActive) {
                val current = player.snapshot.value
                val advancing = current != null && PlaybackClock.isInterpolating(current)
                delay(if (advancing || sequence == 0L) FRAME_POLL_MS else PAUSED_POLL_MS)
                if (current != null) {
                    reader.setClock(
                        PlaybackClock.positionMs(current, SystemMonotonicClock.nowNanos()),
                        advancing,
                        current.playbackSpeed,
                    )
                }
                val (info, copied) = reader.copyFrame(sequence, pixels?.addr ?: 0L, capacity) ?: break
                if (info.state == VideoArtworkState.FAILED) {
                    val lastError = reader.lastError().take(MAX_ERROR_CHARS)
                    report("video artwork stopped: $lastError")
                    feed.stats.value = feed.stats.value.copy(state = VideoArtworkState.FAILED, lastError = lastError)
                    reader.close()
                    if (recoveries >= MAX_RECOVERIES) break
                    recoveries++
                    reader = open(feed.videoId, refresh = true) ?: break
                    reader.setActive(true)
                    sequence = 0L
                    continue
                }
                if (copied < 0 || (info.width > 0 && bitmap == null)) {
                    pixels?.close()
                    bitmap?.close()
                    pixels = null
                    val allocated = Bitmap()
                    bitmap = allocated
                    if (!allocated.allocPixels(ImageInfo(info.width, info.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE))) break
                    pixels = allocated.peekPixels() ?: break
                    capacity = info.width.toLong() * info.height * BYTES_PER_PIXEL
                    sequence = 0L
                    continue
                }
                if (copied == 1) {
                    val written = bitmap ?: continue
                    sequence = info.sequence
                    written.notifyPixelsChanged()
                    val previous = feed.frame.value
                    feed.frame.value = SkiaImage.makeFromBitmap(written)
                    previous?.close()
                    presented++
                    windowFrames++
                    val elapsed = nowMs() - windowStartMs
                    if (elapsed >= 1000L) {
                        fps = windowFrames * 1000f / elapsed
                        windowFrames = 0
                        windowStartMs = nowMs()
                    }
                    feed.stats.value = feed.stats.value.copy(
                        width = info.width,
                        height = info.height,
                        streamHeight = streamHeight(feed.videoId),
                        state = info.state,
                        presentedFrames = presented,
                        presentedFps = fps,
                        recoveries = recoveries,
                    )
                }
            }
        } finally {
            reader.close()
            val shown = feed.frame.value
            feed.frame.value = null
            shown?.close()
            pixels?.close()
            bitmap?.close()
        }
    }

    /**
     * Opens an inactive reader for [videoId], or null when it is not a music video, no stream resolved or the engine
     * library is missing. [refresh] asks YouTube again, for a stream whose address stopped working.
     */
    suspend fun open(videoId: String, refresh: Boolean = false): NativeVideoArtwork? {
        val stream = withContext(Dispatchers.IO) { stream(videoId, refresh) } ?: return null
        return openWithOwnership {
            try {
                NativeVideoArtwork.open(stream.url, stream.userAgent, DESKTOP_VIDEO_MAX_HEIGHT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                reportEngineMissing(failure)
                null
            } catch (failure: LinkageError) {
                reportEngineMissing(failure)
                null
            }
        }
    }

    /** The stream height the reader opened for [videoId], for the stats overlay, or 0 before resolution. */
    private fun streamHeight(videoId: String): Int =
        ((answers[videoId] as? Answer.Found)?.stream?.height) ?: 0

    private suspend fun stream(videoId: String, refresh: Boolean): DesktopVideoArtworkResult.Stream? {
        val now = nowMs()
        lock.withLock {
            when (val held = answers[videoId]) {
                is Answer.NoVideo -> return null
                is Answer.Failed -> if (now - held.atMs < FAILURE_RETRY_MS) return null
                is Answer.Found -> if (!refresh && now - held.atMs < STREAM_REUSE_MS) return held.stream
                null -> Unit
            }
        }
        val answer = try {
            when (val result = resolver.resolveVideoArtwork(videoId, DESKTOP_VIDEO_MAX_HEIGHT)) {
                is DesktopVideoArtworkResult.Stream -> Answer.Found(result, nowMs())
                DesktopVideoArtworkResult.NotAMusicVideo -> Answer.NoVideo(nowMs())
                null -> Answer.Failed(nowMs())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("video artwork not resolved: ${failure.javaClass.simpleName}")
            Answer.Failed(nowMs())
        }
        lock.withLock { answers[videoId] = answer }
        return (answer as? Answer.Found)?.stream
    }

    private fun reportEngineMissing(failure: Throwable) {
        if (engineMissingReported) return
        engineMissingReported = true
        report("video artwork unavailable: ${failure.javaClass.simpleName}")
    }

    private companion object {
        const val FRAME_POLL_MS = 16L
        const val PAUSED_POLL_MS = 250L
        const val MAX_RECOVERIES = 2
        const val MAX_ERROR_CHARS = 160
        const val BYTES_PER_PIXEL = 4L
        const val ANSWER_ENTRIES = 128
        const val FAILURE_RETRY_MS = 5 * 60_000L
        // YouTube stream addresses stay valid for hours; a shorter reuse keeps a reopened player off a stale one.
        const val STREAM_REUSE_MS = 30 * 60_000L
    }
}

/**
 * The player cover, with the music video drawn over it while video playback is on, the player is expanded and the
 * slot shows the occurrence that is playing. The cover stays visible until the first frame, and again when the video
 * cannot play, so a failure never leaves an empty frame.
 */
@Composable
internal fun DesktopPlayerArtwork(occurrenceId: String?, graph: DesktopSessionGraph, visible: Boolean, modifier: Modifier) {
    val saved by graph.settings.snapshots.collectAsState()
    val queue by graph.queue.state.collectAsState()
    val snapshot by graph.player.snapshot.collectAsState()
    val target = occurrenceId ?: queue.currentOccurrenceId
    val ref = queue.entries.firstOrNull { it.occurrenceId == target }?.item?.ref
    val videoId = ref?.takeIf {
        it.provider == ContentProvider.YOUTUBE.id && it.kind == ContentKind.SONG && VIDEO_ID.matches(it.id)
    }?.id
    val showVideo = visible && saved?.get(EnableVideoPlaybackKey) == true && videoId != null &&
        target != null && snapshot?.occurrenceId == target
    var fullscreen by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    val windowShown = LocalDesktopWindowShown.current
    LaunchedEffect(windowShown) {
        // A hidden or minimized window takes its fullscreen video with it, so the video does not reappear on its own.
        if (!windowShown) fullscreen = false
    }
    Box(modifier) {
        DesktopQueueArtwork(occurrenceId, graph, Modifier.fillMaxSize())
        if (showVideo && videoId != null) {
            DesktopVideoSurface(videoId, graph, VideoFit.of(saved?.get(VideoAspectRatioKey)), Modifier.matchParentSize())
            Row(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { showStats = !showStats }) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = DesktopStrings["video_stats_toggle"],
                        tint = if (showStats) Color.White else Color.White.copy(alpha = 0.6f),
                    )
                }
                IconButton(onClick = { fullscreen = true }) {
                    Icon(
                        Icons.Filled.Fullscreen,
                        contentDescription = DesktopStrings["video_fullscreen"],
                        tint = Color.White,
                    )
                }
            }
            if (showStats) {
                val feed = remember(videoId) { graph.videoArtwork.acquire(videoId) }
                DisposableEffect(feed) { onDispose { graph.videoArtwork.release(feed) } }
                DesktopVideoStatsPanel(feed, Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
        }
    }
    // Gated on the shown state rather than visible: the fullscreen window covers this one, which reads as occluded.
    if (fullscreen && videoId != null && windowShown) {
        DesktopVideoFullscreen(videoId, graph, VideoFit.of(saved?.get(VideoAspectRatioKey))) { fullscreen = false }
    }
}

/**
 * The desktop stats-for-nerds: the decode shape and presented pace of [feed], as the Metrolist desktop overlay shows
 * them. Every number is read from the running reader, so a stalled stream shows up as state and a stopped fps, not
 * frozen values.
 */
@Composable
private fun DesktopVideoStatsPanel(feed: DesktopVideoArtwork.Feed, modifier: Modifier = Modifier) {
    val stats = feed.stats.value
    Column(
        modifier
            .widthIn(max = 340.dp)
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        statRow(DesktopStrings["video_stats_id"], feed.videoId)
        if (stats.width > 0) statRow(DesktopStrings["video_stats_resolution"], "${stats.width}x${stats.height}")
        if (stats.streamHeight > 0) statRow(DesktopStrings["video_stats_stream"], "${stats.streamHeight}p")
        statRow(DesktopStrings["video_stats_decoder"], stats.state?.name ?: DesktopStrings["video_stats_opening"])
        statRow(DesktopStrings["video_stats_fps"], "%.1f".format(stats.presentedFps))
        statRow(DesktopStrings["video_stats_frames"], stats.presentedFrames.toString())
        if (stats.recoveries > 0) statRow(DesktopStrings["video_stats_recoveries"], stats.recoveries.toString())
        if (stats.lastError.isNotBlank()) statRow(DesktopStrings["video_stats_error"], stats.lastError)
    }
}

@Composable
private fun statRow(label: String, value: String) {
    Row {
        Text("$label: ", color = Color.White.copy(alpha = 0.66f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text(value, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

/**
 * Full-window video: the same shared feed drawn on a black field, with the stats toggle and a close button. It is a
 * second surface on the feed, so it never starts another reader for the same video.
 */
@Composable
private fun DesktopVideoFullscreen(videoId: String, graph: DesktopSessionGraph, fit: VideoFit, onClose: () -> Unit) {
    var showStats by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            DesktopVideoSurface(videoId, graph, fit, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { showStats = !showStats }) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = DesktopStrings["video_stats_toggle"],
                        tint = if (showStats) Color.White else Color.White.copy(alpha = 0.6f),
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = DesktopStrings["video_close"], tint = Color.White)
                }
            }
            if (showStats) {
                val feed = remember(videoId) { graph.videoArtwork.acquire(videoId) }
                DisposableEffect(feed) { onDispose { graph.videoArtwork.release(feed) } }
                DesktopVideoStatsPanel(feed, Modifier.align(Alignment.TopStart).padding(16.dp))
            }
        }
    }
}

/** Android's VideoAspectRatio values: FIT letterboxes, CROP fills and trims, STRETCH and FILL fill and distort. */
private enum class VideoFit {
    FIT, CROP, FILL;

    companion object {
        fun of(stored: String?): VideoFit = when (stored) {
            "CROP" -> CROP
            "STRETCH", "FILL" -> FILL
            else -> FIT
        }
    }
}

/**
 * Draws the shared feed of [videoId]. The player can compose its artwork twice at once (the blurred background and
 * the cover), so both surfaces read one reader instead of downloading and decoding the video twice.
 */
@Composable
private fun DesktopVideoSurface(videoId: String, graph: DesktopSessionGraph, fit: VideoFit, modifier: Modifier) {
    val feed = remember(videoId) { graph.videoArtwork.acquire(videoId) }
    DisposableEffect(feed) { onDispose { graph.videoArtwork.release(feed) } }
    Spacer(
        modifier.drawBehind {
            val image = feed.frame.value ?: return@drawBehind
            drawRect(Color.Black)
            val target = videoRect(image.width.toFloat(), image.height.toFloat(), size, fit)
            clipRect {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawImageRect(
                        image,
                        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                        target,
                    )
                }
            }
        },
    )
}

private fun videoRect(width: Float, height: Float, bounds: Size, fit: VideoFit): Rect {
    if (width <= 0f || height <= 0f || fit == VideoFit.FILL) return Rect.makeWH(bounds.width, bounds.height)
    val scale = when (fit) {
        VideoFit.FIT -> minOf(bounds.width / width, bounds.height / height)
        else -> maxOf(bounds.width / width, bounds.height / height)
    }
    val drawnWidth = width * scale
    val drawnHeight = height * scale
    return Rect.makeXYWH((bounds.width - drawnWidth) / 2f, (bounds.height - drawnHeight) / 2f, drawnWidth, drawnHeight)
}

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

private val UiThread = Executor { EventQueue.invokeLater(it) }.asCoroutineDispatcher()
