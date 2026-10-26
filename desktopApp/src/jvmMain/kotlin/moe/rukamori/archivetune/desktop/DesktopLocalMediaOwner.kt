package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.RandomAccessFile
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.rukamori.archivetune.shared.data.localmedia.JvmLocalMediaScanner
import moe.rukamori.archivetune.shared.domain.mediainfo.MediaHeaderParser
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.domain.settings.LocalSongsExcludedFoldersKey
import moe.rukamori.archivetune.shared.domain.settings.LocalSongsIncludedFoldersKey
import moe.rukamori.archivetune.shared.domain.settings.LocalSongsMinDurationSecondsKey
import moe.rukamori.archivetune.shared.domain.settings.LocalSongsSortDescendingKey
import moe.rukamori.archivetune.shared.domain.settings.LocalSongsSortTypeKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.ui.screens.LocalSongSort
import moe.rukamori.archivetune.shared.ui.screens.LocalSongUi

/** Header bytes read for container detection and duration; enough for FLAC/MP3/MP4/OGG/WAV specs. */
private const val TAG_HEADER_BYTES = 256 * 1024

/**
 * The local-file scan owner behind `local_songs`. Scanning reads the folders the user picked —
 * persisted in the same preference keys the baseline uses — and each file's own tags (ID3v2,
 * Vorbis comment, MP4 `ilst`, RIFF INFO) for title/artist/album plus the media header for the
 * duration the minimum-duration filter applies. Rows are real local occurrences: their ref is
 * `provider="local"`, `id=` the absolute path, which the desktop locator resolver opens as a
 * [PlaybackLocator.LocalFile]. Nothing here fakes an Android MediaStore scan: an unchosen folder
 * set scans as empty and reports so.
 */
internal class DesktopLocalMediaOwner(
    private val scanner: JvmLocalMediaScanner,
    private val settings: SettingsStore,
    private val queue: QueueController,
    private val scope: CoroutineScope,
    private val report: (String) -> Unit,
) {
    var rows by mutableStateOf<List<LocalSongUi>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var scannedCount by mutableStateOf<Int?>(null)
        private set
    var removedCount by mutableStateOf(0)
        private set
    var query by mutableStateOf("")
    var scannedOnce by mutableStateOf(false)
        private set

    private var scanJob: Job? = null

    /** The desktop needs no runtime grant to read files the user pointed it at. */
    val permissionGranted = true

    fun minimumDurationSeconds(): Int =
        settings.snapshots.value?.get(LocalSongsMinDurationSecondsKey) ?: 0

    fun includedFolders(): List<String> =
        settings.snapshots.value?.get(LocalSongsIncludedFoldersKey)?.toList()?.sorted() ?: emptyList()

    fun excludedFolders(): List<String> =
        settings.snapshots.value?.get(LocalSongsExcludedFoldersKey)?.toList()?.sorted() ?: emptyList()

    fun sort(): Pair<LocalSongSort, Boolean> {
        val snapshot = settings.snapshots.value
        val type = snapshot?.get(LocalSongsSortTypeKey)
            ?.let { name -> LocalSongSort.entries.firstOrNull { it.name == name } } ?: LocalSongSort.MODIFIED
        return type to (snapshot?.get(LocalSongsSortDescendingKey) ?: true)
    }

    fun setSort(sort: LocalSongSort, descending: Boolean) {
        scope.launch {
            try {
                settings.edit {
                    set(LocalSongsSortTypeKey, sort.name)
                    set(LocalSongsSortDescendingKey, descending)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("the local-song sort was not saved: ${failure.javaClass.simpleName}")
            }
        }
    }

    fun setMinimumDuration(seconds: Int) {
        scope.launch {
            try {
                settings.edit { set(LocalSongsMinDurationSecondsKey, seconds.coerceAtLeast(0)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("the local-song duration filter was not saved: ${failure.javaClass.simpleName}")
            }
        }
    }

    fun removeFolder(included: Boolean, folder: String) {
        val key = if (included) LocalSongsIncludedFoldersKey else LocalSongsExcludedFoldersKey
        scope.launch {
            try {
                settings.edit {
                    set(key, (get(key) ?: emptySet()) - folder)
                }
                scan()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("the folder was not removed: ${failure.javaClass.simpleName}")
            }
        }
    }

    /** The native directory picker adds [chosen] to the included or excluded set and rescans. */
    fun pickFolder(included: Boolean, title: String) {
        scope.launch {
            val chosen = chooseDirectory(title) ?: return@launch
            val key = if (included) LocalSongsIncludedFoldersKey else LocalSongsExcludedFoldersKey
            try {
                settings.edit {
                    set(key, (get(key) ?: emptySet()) + chosen)
                }
                scan()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("the folder was not added: ${failure.javaClass.simpleName}")
            }
        }
    }

    fun scan() {
        scanJob?.cancel()
        scanJob = scope.launch {
            scanning = true
            failed = false
            try {
                val roots = includedFolders().map { java.nio.file.Path.of(it) }
                    .filter { java.nio.file.Files.isDirectory(it) }
                val excluded = excludedFolders().map { java.nio.file.Path.of(it) }
                val files = scanner.scan(roots, excluded)
                val minimumMs = minimumDurationSeconds() * 1_000L
                val next = ArrayList<LocalSongUi>(files.size)
                var skipped = 0
                for (file in files) {
                    val display = file.path.toString()
                    val tags = LocalTagReader.read(display)
                    val technical = headerInfo(display, file.size)
                    // A file whose duration the header cannot prove stays, like a file the baseline
                    // could not parse: only a proven-shorter duration is filtered out.
                    val durationMs = technical?.durationMs
                    if (minimumMs > 0 && durationMs != null && durationMs < minimumMs) {
                        skipped++
                        continue
                    }
                    val name = File(display).nameWithoutExtension
                    val item = ContentItem(
                        ref = ContentRef(LOCAL_PROVIDER, ContentKind.SONG, display),
                        title = tags.title ?: name,
                        subtitle = tags.artist ?: "",
                        album = tags.album,
                        durationMs = technical?.durationMs,
                        artists = tags.artist?.let { listOf(ContentCredit(it)) } ?: emptyList(),
                    )
                    next += LocalSongUi(
                        row = ContentOccurrence("$LOCAL_PROVIDER:$display", item),
                        album = tags.album ?: "",
                        modifiedEpochMs = file.modifiedMs,
                    )
                }
                val previous = rows.map { it.row.item.ref.id }.toSet()
                removedCount = (previous - files.map { it.path.toString() }.toSet()).size + skipped
                rows = next
                scannedCount = files.size
                scannedOnce = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed = true
                report("the local-media scan failed: ${failure.javaClass.simpleName}")
            } finally {
                scanning = false
            }
        }
    }

    /** Resolves the file a local song ref names. A gone file stays gone rather than replaying a guess. */
    fun localPathOf(mediaId: String): String? =
        File(mediaId).takeIf { it.isAbsolute && it.isFile }?.absolutePath

    fun play(selected: ContentOccurrence, visibleQueue: List<ContentOccurrence>) {
        scope.launch {
            try {
                queue.replace(visibleQueue, selected.occurrenceId, playWhenReady = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("the local song was not played: ${failure.javaClass.simpleName}")
            }
        }
    }

    private fun headerInfo(path: String, totalBytes: Long) = try {
        RandomAccessFile(path, "r").use { raf ->
            val header = ByteArray(minOf(TAG_HEADER_BYTES.toLong(), totalBytes).toInt())
            val read = raf.read(header)
            if (read <= 0) null else MediaHeaderParser.parse(header.copyOf(read), totalBytes)
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun chooseDirectory(title: String): String? = suspendCancellableCoroutine { continuation ->
        SwingUtilities.invokeLater {
            val chooser = JFileChooser()
            chooser.dialogTitle = title
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            val result = chooser.showOpenDialog(null)
            continuation.resume(
                if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null,
            ) { }
        }
    }

    companion object {
        const val LOCAL_PROVIDER = "local"
    }
}
