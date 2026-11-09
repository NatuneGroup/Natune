/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.SilentHandler
import moe.rukamori.archivetune.utils.ArtworkThumbnailDecode
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.widget.AlbumArtWidget
import moe.rukamori.archivetune.widget.ListeningInsightsWidget
import moe.rukamori.archivetune.widget.LoadWidgetInsightsUseCase
import moe.rukamori.archivetune.widget.MusicWidget
import moe.rukamori.archivetune.widget.MusicWidgetKeys
import moe.rukamori.archivetune.widget.NowPlayingCardWidget
import moe.rukamori.archivetune.widget.PlaybackCapsuleWidget
import moe.rukamori.archivetune.widget.PlaybackCommandWidget
import moe.rukamori.archivetune.widget.PlaybackDeckWidget
import moe.rukamori.archivetune.widget.PlaybackSpotlightWidget
import moe.rukamori.archivetune.widget.WidgetInsightsSnapshot
import moe.rukamori.archivetune.widget.toWidgetPreferenceValue
import java.io.File
import java.security.MessageDigest

internal class MusicServiceWidgetUpdater(
    private val service: MusicService,
    private val scope: CoroutineScope,
    private val loadWidgetInsights: LoadWidgetInsightsUseCase,
) {
    // Always read through the service: the active player may be promoted (crossfade).
    private val player: Player
        get() = service.player

    private val widgetManager = GlanceAppWidgetManager(service)
    private var stateJob: Job? = null
    private var progressJob: Job? = null

    // Dominant colours, keyed by artwork file identity. Bounded: a session can play hundreds of
    // tracks and a colour is four bytes.
    private val dominantColorMemo = object : LinkedHashMap<String, Int>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean = size > 16
    }

    // Widgets are not visible with the screen off, so the progress timer stops with the screen and starts again on
    // ACTION_SCREEN_ON. Nothing ticks while the screen is off.
    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON) updateProgressTracking() else progressJob?.cancel()
            }
        }
    private var screenReceiverRegistered = false

    init {
        registerScreenReceiver()
    }

    /** Called when the service is destroyed: the progress timer and the screen receiver end with it. */
    fun release() {
        progressJob?.cancel()
        progressJob = null
        if (screenReceiverRegistered) {
            runCatching { service.unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
    }

    fun update() {
        stateJob?.cancel()
        stateJob =
            scope.launch(SilentHandler) {
                pushState()
            }
    }

    fun updateProgressTracking() {
        progressJob?.cancel()
        if (player.isPlaying && player.duration > 0 && isScreenOn()) {
            progressJob =
                scope.launch(SilentHandler) {
                    val installedTargets = findInstalledTargets(progressWidgets)
                    if (installedTargets.isEmpty()) return@launch

                    // The loop also ends itself when the screen is off, so a missed broadcast cannot keep it ticking.
                    while (isActive && player.isPlaying && isScreenOn()) {
                        delay(WIDGET_PROGRESS_UPDATE_INTERVAL_MILLIS)
                        if (player.isPlaying && isScreenOn()) {
                            updateProgress(installedTargets, player.playbackProgress())
                        }
                    }
                }
        }
    }

    private fun isScreenOn(): Boolean = service.getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun registerScreenReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        ContextCompat.registerReceiver(service, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
    }

    private suspend fun pushState() {
        val installedTargets = findInstalledTargets(playbackWidgets)
        if (installedTargets.isEmpty()) return

        val mediaItem = player.currentMediaItem
        val meta = service.mediaSession.player.mediaMetadata
        val artFile = meta?.artworkUri?.let { cacheAlbumArt(it) }
        val dominantColor = artFile?.let { extractDominantColor(it) }
        val snapshot =
            WidgetSnapshot(
                title = meta?.title?.toString() ?: service.getString(R.string.no_track_playing),
                artist = meta?.artist?.toString().orEmpty(),
                isPlaying = player.isPlaying,
                isAvailable = mediaItem != null,
                playbackPosition = player.playbackProgress(),
                artPath = artFile?.absolutePath,
                dominantColor = dominantColor,
                insights = WidgetInsightsSnapshot.Empty,
            )

        installedTargets.forEach { target ->
            updateWidget(target, snapshot)
        }
    }

    private suspend fun updateProgress(
        installedTargets: List<InstalledWidgetTarget>,
        progress: Float,
    ) {
        installedTargets.forEach { installedTarget ->
            installedTarget.ids.forEach { id ->
                updateAppWidgetState(service, PreferencesGlanceStateDefinition, id) { prefs ->
                    prefs.toMutableWidgetPreferences().apply {
                        this[MusicWidgetKeys.PLAYBACK_POSITION] = progress
                    }
                }
                installedTarget.target.widget.update(service, id)
            }
        }
    }

    private suspend fun updateWidget(
        installedTarget: InstalledWidgetTarget,
        snapshot: WidgetSnapshot,
    ) {
        val targetSnapshot =
            if (installedTarget.target.requiresInsights) {
                snapshot.copy(insights = loadInsightsSnapshot())
            } else {
                snapshot
            }

        installedTarget.ids.forEach { id ->
            updateAppWidgetState(service, PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutableWidgetPreferences().apply {
                    writeSnapshot(targetSnapshot)
                }
            }
            installedTarget.target.widget.update(service, id)
        }
    }

    private suspend fun findInstalledTargets(targets: List<WidgetTarget>): List<InstalledWidgetTarget> =
        targets.mapNotNull { target ->
            widgetManager
                .getGlanceIds(target.widgetClass)
                .takeIf { ids -> ids.isNotEmpty() }
                ?.let { ids -> InstalledWidgetTarget(target, ids) }
        }

    private fun Preferences.toMutableWidgetPreferences(): MutablePreferences =
        mutablePreferencesOf().also { mutable ->
            this[MusicWidgetKeys.TRACK_TITLE]?.let { mutable[MusicWidgetKeys.TRACK_TITLE] = it }
            this[MusicWidgetKeys.TRACK_ARTIST]?.let { mutable[MusicWidgetKeys.TRACK_ARTIST] = it }
            this[MusicWidgetKeys.ART_PATH]?.let { mutable[MusicWidgetKeys.ART_PATH] = it }
            this[MusicWidgetKeys.IS_PLAYING]?.let { mutable[MusicWidgetKeys.IS_PLAYING] = it }
            this[MusicWidgetKeys.IS_AVAILABLE]?.let { mutable[MusicWidgetKeys.IS_AVAILABLE] = it }
            this[MusicWidgetKeys.DOMINANT_COLOR]?.let { mutable[MusicWidgetKeys.DOMINANT_COLOR] = it }
            this[MusicWidgetKeys.PLAYBACK_POSITION]?.let { mutable[MusicWidgetKeys.PLAYBACK_POSITION] = it }
            this[MusicWidgetKeys.LISTENING_TIME]?.let { mutable[MusicWidgetKeys.LISTENING_TIME] = it }
            this[MusicWidgetKeys.TOTAL_PLAYS]?.let { mutable[MusicWidgetKeys.TOTAL_PLAYS] = it }
            this[MusicWidgetKeys.RECENT_SONGS]?.let { mutable[MusicWidgetKeys.RECENT_SONGS] = it }
            this[MusicWidgetKeys.GENRES]?.let { mutable[MusicWidgetKeys.GENRES] = it }
            this[MusicWidgetKeys.RECOMMENDATIONS]?.let { mutable[MusicWidgetKeys.RECOMMENDATIONS] = it }
            this[MusicWidgetKeys.TOP_SONG_SUMMARY]?.let { mutable[MusicWidgetKeys.TOP_SONG_SUMMARY] = it }
        }

    private fun MutablePreferences.writeSnapshot(snapshot: WidgetSnapshot) {
        this[MusicWidgetKeys.TRACK_TITLE] = snapshot.title
        this[MusicWidgetKeys.TRACK_ARTIST] = snapshot.artist
        this[MusicWidgetKeys.IS_PLAYING] = snapshot.isPlaying
        this[MusicWidgetKeys.IS_AVAILABLE] = snapshot.isAvailable
        this[MusicWidgetKeys.PLAYBACK_POSITION] = snapshot.playbackPosition

        val artPath = snapshot.artPath
        if (artPath != null) {
            this[MusicWidgetKeys.ART_PATH] = artPath
        } else {
            remove(MusicWidgetKeys.ART_PATH)
        }

        val dominantColor = snapshot.dominantColor
        if (dominantColor != null) {
            this[MusicWidgetKeys.DOMINANT_COLOR] = dominantColor
        } else {
            remove(MusicWidgetKeys.DOMINANT_COLOR)
        }

        writeInsights(snapshot.insights)
    }

    private fun MutablePreferences.writeInsights(insights: WidgetInsightsSnapshot) {
        if (insights.listeningTime.isNotBlank()) {
            this[MusicWidgetKeys.LISTENING_TIME] = insights.listeningTime
        } else {
            remove(MusicWidgetKeys.LISTENING_TIME)
        }
        if (insights.totalPlays.isNotBlank()) {
            this[MusicWidgetKeys.TOTAL_PLAYS] = insights.totalPlays
        } else {
            remove(MusicWidgetKeys.TOTAL_PLAYS)
        }
        writeList(MusicWidgetKeys.RECENT_SONGS, insights.recentSongs)
        writeList(MusicWidgetKeys.GENRES, insights.genres)
        writeList(MusicWidgetKeys.RECOMMENDATIONS, insights.recommendations)

        val topSongSummary = insights.topSongSummary
        if (!topSongSummary.isNullOrBlank()) {
            this[MusicWidgetKeys.TOP_SONG_SUMMARY] = topSongSummary
        } else {
            remove(MusicWidgetKeys.TOP_SONG_SUMMARY)
        }
    }

    private fun MutablePreferences.writeList(
        key: Preferences.Key<String>,
        values: List<String>,
    ) {
        if (values.isEmpty()) {
            remove(key)
        } else {
            this[key] = values.toWidgetPreferenceValue()
        }
    }

    private suspend fun loadInsightsSnapshot(): WidgetInsightsSnapshot =
        try {
            loadWidgetInsights()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportException(error)
            WidgetInsightsSnapshot.Empty
        }

    private suspend fun cacheAlbumArt(uri: Uri): File? =
        withContext(Dispatchers.IO) {
            val directory = service.cacheDir
            val dest = File(directory, WidgetArtCachePolicy.fileNameFor(uri.toString()))
            if (dest.isFile && dest.length() > 0L) {
                pruneWidgetArtCache(directory, keep = dest)
                return@withContext dest
            }

            val cached =
                when (uri.scheme) {
                    "content", "file" -> cacheStreamedArtwork(uri, dest)
                    "https", "http" -> cacheRemoteArtwork(uri, dest)
                    else -> null
                } ?: return@withContext null

            pruneWidgetArtCache(directory, keep = cached)
            cached
        }

    private fun cacheStreamedArtwork(
        uri: Uri,
        dest: File,
    ): File? =
        try {
            val temporary = temporaryArtworkFile(dest)
            try {
                val source = service.contentResolver.openInputStream(uri) ?: return null
                source.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                if (temporary.length() <= 0L) return null
                if (!temporary.renameTo(dest)) return null
                dest
            } finally {
                temporary.delete()
            }
        } catch (_: Exception) {
            null
        }

    private suspend fun cacheRemoteArtwork(
        uri: Uri,
        dest: File,
    ): File? =
        try {
            val loader = service.applicationContext.imageLoader
            val request =
                ImageRequest
                    .Builder(service.applicationContext)
                    .data(uri.toString())
                    .size(512, 512)
                    .allowHardware(false)
                    .build()
            val result = loader.execute(request)
            if (result !is SuccessResult) return null

            val bitmap = result.image.toBitmap()
            val temporary = temporaryArtworkFile(dest)
            try {
                val compressed =
                    temporary.outputStream().use { output ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, output)
                    }
                if (!compressed) return null
                if (temporary.length() <= 0L) return null
                if (!temporary.renameTo(dest)) return null
                dest
            } finally {
                temporary.delete()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private fun temporaryArtworkFile(dest: File): File =
        File.createTempFile("${dest.name}.", ".tmp", dest.parentFile)

    private fun pruneWidgetArtCache(
        directory: File,
        keep: File,
    ) {
        val files = directory.listFiles()?.filter(File::isFile) ?: return
        val evicted =
            WidgetArtCachePolicy.filesToEvict(
                files =
                    files.map { file ->
                        WidgetArtCacheFile(
                            fileName = file.name,
                            lastModifiedEpochMs = file.lastModified(),
                            sizeBytes = file.length(),
                        )
                    },
                keepFileName = keep.name,
            )
        files.filter { it.name in evicted }.forEach { runCatching { it.delete() } }
    }

    private suspend fun extractDominantColor(file: File): Int? =
        withContext(Dispatchers.Default) {
            // A state push happens on every track change and again on play/pause; the same cached
            // artwork file can only yield the same colour, so remember it by file identity.
            val cacheKey = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
            synchronized(dominantColorMemo) { dominantColorMemo[cacheKey] }?.let { return@withContext it }
            try {
                val bounds =
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        // Read the file's real pixel size; density metadata must not scale the
                        // bounds and change the sample decision.
                        inScaled = false
                    }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

                val bitmap =
                    BitmapFactory.decodeFile(
                        file.absolutePath,
                        BitmapFactory.Options().apply {
                            // A widget palette only needs colours: a full-resolution cover is a
                            // multi-megabyte decode for no visible gain.
                            inSampleSize =
                                ArtworkThumbnailDecode.sampleSize(
                                    sourceWidth = bounds.outWidth,
                                    sourceHeight = bounds.outHeight,
                                    maxDimensionPx = ArtworkThumbnailDecode.PALETTE_MAX_DIMENSION_PX,
                                )
                            inScaled = false
                        },
                    ) ?: return@withContext null
                val palette = Palette.from(bitmap).generate()
                val color =
                    palette.getDarkVibrantColor(
                        palette.getDominantColor(android.graphics.Color.DKGRAY),
                    )
                synchronized(dominantColorMemo) { dominantColorMemo[cacheKey] = color }
                color
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }

    private fun Player.playbackProgress(): Float =
        if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

    private data class WidgetSnapshot(
        val title: String,
        val artist: String,
        val isPlaying: Boolean,
        val isAvailable: Boolean,
        val playbackPosition: Float,
        val artPath: String?,
        val dominantColor: Int?,
        val insights: WidgetInsightsSnapshot,
    )

    private data class WidgetTarget(
        val widgetClass: Class<out GlanceAppWidget>,
        val widget: GlanceAppWidget,
        val requiresInsights: Boolean = false,
    )

    private data class InstalledWidgetTarget(
        val target: WidgetTarget,
        val ids: List<GlanceId>,
    )

    private companion object {
        const val WIDGET_PROGRESS_UPDATE_INTERVAL_MILLIS = 30_000L

        val playbackWidgets =
            listOf(
                WidgetTarget(MusicWidget::class.java, MusicWidget()),
                WidgetTarget(NowPlayingCardWidget::class.java, NowPlayingCardWidget()),
                WidgetTarget(PlaybackDeckWidget::class.java, PlaybackDeckWidget()),
                WidgetTarget(AlbumArtWidget::class.java, AlbumArtWidget()),
                WidgetTarget(PlaybackCapsuleWidget::class.java, PlaybackCapsuleWidget()),
                WidgetTarget(PlaybackSpotlightWidget::class.java, PlaybackSpotlightWidget()),
                WidgetTarget(PlaybackCommandWidget::class.java, PlaybackCommandWidget()),
                WidgetTarget(
                    widgetClass = ListeningInsightsWidget::class.java,
                    widget = ListeningInsightsWidget(),
                    requiresInsights = true,
                ),
            )

        val progressWidgets =
            listOf(
                WidgetTarget(MusicWidget::class.java, MusicWidget()),
                WidgetTarget(NowPlayingCardWidget::class.java, NowPlayingCardWidget()),
                WidgetTarget(PlaybackDeckWidget::class.java, PlaybackDeckWidget()),
                WidgetTarget(PlaybackCapsuleWidget::class.java, PlaybackCapsuleWidget()),
                WidgetTarget(PlaybackSpotlightWidget::class.java, PlaybackSpotlightWidget()),
                WidgetTarget(PlaybackCommandWidget::class.java, PlaybackCommandWidget()),
            )
    }
}

internal data class WidgetArtCacheFile(
    val fileName: String,
    val lastModifiedEpochMs: Long,
    val sizeBytes: Long,
)

/**
 * Identity-safe and bounded file cache for the artwork the widgets render.
 *
 * The file name is the SHA-256 of the artwork URI: the old `Integer.toHexString` of
 * `String.hashCode` mapped distinct URIs to one file and could silently show the wrong art.
 * Each successful write evicts the oldest entries over the entry/byte budget, never the artwork
 * being written, and only files this cache owns. The directory itself and unrelated cache files
 * are never touched, so the only files removed are the excess widget artwork entries.
 */
internal object WidgetArtCachePolicy {
    const val MAX_ENTRIES = 32
    const val MAX_BYTES = 16L * 1024 * 1024

    private val currentFileName = Regex("widget_art_[0-9a-f]{64}\\.jpg")
    private val legacyFileName = Regex("widget_art_[0-9a-f]{1,8}\\.jpg")

    fun fileNameFor(artworkUri: String): String = "widget_art_${digest(artworkUri)}.jpg"

    fun filesToEvict(
        files: Collection<WidgetArtCacheFile>,
        keepFileName: String?,
    ): Set<String> {
        val ordered =
            files
                .filter { currentFileName.matches(it.fileName) || legacyFileName.matches(it.fileName) }
                .sortedWith(
                    compareByDescending<WidgetArtCacheFile> { it.lastModifiedEpochMs }
                        .thenBy { it.fileName },
                )

        val excess = mutableListOf<String>()
        var retainedEntries = 0
        var retainedBytes = 0L
        ordered.forEach { file ->
            val keep = file.fileName == keepFileName
            val usable = file.sizeBytes > 0L
            val fitsEntryLimit = keep || retainedEntries < MAX_ENTRIES
            val fitsByteLimit = keep || retainedBytes + file.sizeBytes <= MAX_BYTES
            if (!keep && (!usable || !fitsEntryLimit || !fitsByteLimit)) {
                excess += file.fileName
            } else {
                retainedEntries += 1
                retainedBytes += file.sizeBytes.coerceAtLeast(0L)
            }
        }
        return excess.toCollection(LinkedHashSet())
    }

    private fun digest(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
