@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.backup.readPortableFilePrefix
import moe.rukamori.archivetune.shared.data.download.DownloadExportNaming
import moe.rukamori.archivetune.shared.data.persistence.PortableStoredSongRow
import moe.rukamori.archivetune.shared.data.persistence.storedOccurrence
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_export_songs_clear_selection
import moe.rukamori.archivetune.shared.ui.resources.ios_export_songs_delete
import moe.rukamori.archivetune.shared.ui.resources.ios_export_songs_delete_confirm
import moe.rukamori.archivetune.shared.ui.resources.ios_export_songs_deleted
import moe.rukamori.archivetune.shared.ui.resources.ios_export_songs_done
import moe.rukamori.archivetune.shared.ui.resources.ios_export_songs_empty
import moe.rukamori.archivetune.shared.ui.resources.ios_export_songs_export
import moe.rukamori.archivetune.shared.ui.resources.ios_export_songs_progress
import moe.rukamori.archivetune.shared.ui.resources.ios_export_songs_search
import moe.rukamori.archivetune.shared.ui.resources.ios_export_songs_select_all
import moe.rukamori.archivetune.shared.ui.resources.ios_export_songs_selected
import moe.rukamori.archivetune.shared.ui.resources.ios_export_songs_title
import moe.rukamori.archivetune.shared.ui.resources.m6_cancel
import moe.rukamori.archivetune.shared.ui.settings.SettingsNotice
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSURL

/**
 * The download list with search, selection, export and delete, like the Android page. iOS has no folder picker, so
 * Export copies the chosen files beside the share sheet's temporary directory and shares them together: Save to
 * Files or AirDrop carries real documents with readable names and the extension their bytes indicate.
 */
@Composable
internal fun IosExportSongsPage(
    host: IosHost,
    graph: IosSessionGraph,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
) {
    val rows by remember { graph.downloads.storedRows() }.collectAsState(null)
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var progress by remember { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val songs = rows.orEmpty().map { IosExportableSong.of(it) }
    val visible = songs.filter { it.matches(query) }
    val chosen = songs.filter { it.id in selected }

    fun export() {
        scope.launch {
            exporting = true
            progress = 0
            // A request-owned temp directory the sheet deletes however it ends; the finally below covers failure.
            val directory = NSTemporaryDirectory() + "exported-songs-" + NSUUID().UUIDString
            val manager = NSFileManager.defaultManager
            try {
                manager.createDirectoryAtPath(directory, true, null, null)
                val urls = mutableListOf<NSURL>()
                chosen.forEachIndexed { index, song ->
                    progress = index + 1
                    val source = AudioSourceType.entries.firstOrNull { it.name == song.row.source }
                        ?: return@forEachIndexed
                    val file = graph.downloads.fileOf(song.row.provider, song.row.mediaId, source, song.row.quality.orEmpty())
                        ?: return@forEachIndexed
                    val extension = DownloadExportNaming.extensionOf(readPortableFilePrefix(file, 16))
                    val target = uniqueExportPath(directory, song.baseName, extension)
                    check(manager.copyItemAtPath(file, target, null)) { "export copy failed" }
                    urls += NSURL.fileURLWithPath(target)
                }
                // A dismissed sheet keeps the copies temporary and reports nothing; the page notices completion only.
                if (urls.isNotEmpty() && iosShareFileUrls(host, urls, cleanup = directory) == true) {
                    onNotice(SettingsNotice.Message(Res.string.ios_export_songs_done, urls.size))
                }
            } catch (cancelled: CancellationException) {
                manager.removeItemAtPath(directory, null)
                throw cancelled
            } catch (failure: Exception) {
                graph.report("download export failed: ${failure::class.simpleName}")
                onFailure()
            } finally {
                manager.removeItemAtPath(directory, null)
                exporting = false
            }
        }
    }

    fun delete() {
        scope.launch {
            try {
                val ids = chosen.map { it.id }
                ids.forEach { id -> graph.downloads.remove(id) }
                selected = emptySet()
                onNotice(SettingsNotice.Message(Res.string.ios_export_songs_deleted, ids.size))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }

    moe.rukamori.archivetune.shared.ui.settings.ExportSongsSettings(
        loading = rows == null,
        songs = songs.map { moe.rukamori.archivetune.shared.ui.settings.ExportSettingsSong(it.id, it.title, it.artists) },
        query = query, selected = selected, exporting = exporting, progress = progress,
        onQuery = { query = it }, onSelection = { selected = it }, onExport = ::export, onDelete = ::delete,
    )
}

private class IosExportableSong(val row: PortableStoredSongRow, val title: String, val artists: String) {
    /** The id [IosDownloadsOwner] removes a download by. */
    val id: String get() = "${row.provider}:${row.mediaId}"

    val baseName: String get() = DownloadExportNaming.fileBaseName(title, artists)

    fun matches(query: String): Boolean =
        query.isBlank() || title.contains(query, ignoreCase = true) || artists.contains(query, ignoreCase = true)

    companion object {
        fun of(row: PortableStoredSongRow): IosExportableSong {
            val item = runCatching { row.storedOccurrence().item }.getOrNull()
                ?: return IosExportableSong(row, row.mediaId, "")
            val credits = item.artists.joinToString(", ") { it.name }
            return IosExportableSong(row, item.title.ifBlank { row.mediaId }, credits.ifBlank { item.subtitle })
        }
    }
}

/** A free `name.ext` path in [directory], falling back to `name (n).ext` like the desktop export. */
private fun uniqueExportPath(directory: String, baseName: String, extension: String): String {
    val manager = NSFileManager.defaultManager
    var target = "$directory/$baseName.$extension"
    var suffix = 2
    while (manager.fileExistsAtPath(target)) {
        target = "$directory/$baseName ($suffix).$extension"
        suffix += 1
    }
    return target
}
