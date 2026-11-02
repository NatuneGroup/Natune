package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.integration.DesktopFilePickers
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.data.download.DownloadExportNaming
import moe.rukamori.archivetune.shared.data.persistence.PortableStoredSongRow
import moe.rukamori.archivetune.shared.data.persistence.storedOccurrence
import moe.rukamori.archivetune.shared.domain.settings.LastFmProvider
import moe.rukamori.archivetune.shared.domain.settings.PlayerCustomBlurKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerCustomBrightnessKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerCustomContrastKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerCustomImageUriKey
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.YouTubeHiddenPlaylistsRoute

/** Settings routes the Android app registers that open pages of their own rather than a list of rows. */
internal val DESKTOP_EXTRA_SETTINGS_ROUTES = setOf(
    YouTubeHiddenPlaylistsRoute, "customize_background", "settings/customize_background", DesktopYouTubeOAuthRoute,
    "settings/lastfm/login", "settings/lastfm/librefm-login", "settings/storage/export_songs",
)

@Composable
internal fun DesktopExtraSettingsPage(
    path: String,
    graph: DesktopSessionGraph,
    controller: SettingsController,
    navigation: ShellNavigation,
    show: (String) -> Unit,
    onFailure: () -> Unit,
) {
    when (path) {
        YouTubeHiddenPlaylistsRoute -> DesktopHiddenPlaylistsPage(graph, show, onFailure)
        "customize_background", "settings/customize_background" -> DesktopCustomBackgroundPage(controller, graph, onFailure)
        DesktopYouTubeOAuthRoute -> DesktopYouTubeOAuthPage(graph.http, controller, navigation, graph::report)
        "settings/lastfm/login" -> DesktopLastFmLoginPage(graph.lastFm, LastFmProvider.LASTFM, navigation, graph::report)
        "settings/lastfm/librefm-login" ->
            DesktopLastFmLoginPage(graph.lastFm, LastFmProvider.LIBREFM, navigation, graph::report)
        "settings/storage/export_songs" -> DesktopExportSongsPage(graph, show, onFailure)
    }
}

/** Local playlists the Android app hid from its Library, with Unhide. */
@Composable
private fun DesktopHiddenPlaylistsPage(graph: DesktopSessionGraph, show: (String) -> Unit, onFailure: () -> Unit) {
    val hidden by remember { graph.library.observeHiddenPlaylists() }.collectAsState(null)
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Text(
            DesktopSettingsText["hidden_playlists_title"],
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )
        val rows = hidden
        when {
            rows == null -> CircularProgressIndicator(Modifier.padding(16.dp))
            rows.isEmpty() -> Text(DesktopSettingsText["hidden_playlists_empty"], Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.title) },
                        trailingContent = {
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        graph.library.setPlaylistHidden(playlist.id, false)
                                        show(DesktopSettingsText["hidden_playlists_unhidden"])
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        onFailure()
                                    }
                                }
                            }) { Text(DesktopSettingsText["hidden_playlists_unhide"]) }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The player's custom background: one local image, kept by its file URI, with the Android page's blur, contrast and
 * brightness ranges. The player reads the same keys.
 */
@Composable
private fun DesktopCustomBackgroundPage(controller: SettingsController, graph: DesktopSessionGraph, onFailure: () -> Unit) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) {
        CircularProgressIndicator()
        return
    }
    val scope = rememberCoroutineScope()
    fun write(change: suspend () -> Unit) {
        scope.launch {
            try {
                change()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }
    val uri = preferences[PlayerCustomImageUriKey, ""]
    val readable = remember(uri) { uri.isBlank() || customImageReadable(uri) }
    var blur by remember(preferences[PlayerCustomBlurKey, 0f]) { mutableStateOf(preferences[PlayerCustomBlurKey, 0f]) }
    var contrast by remember(preferences[PlayerCustomContrastKey, 1f]) { mutableStateOf(preferences[PlayerCustomContrastKey, 1f]) }
    var brightness by remember(preferences[PlayerCustomBrightnessKey, 1f]) {
        mutableStateOf(preferences[PlayerCustomBrightnessKey, 1f])
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(DesktopSettingsText["custom_background_title"], style = MaterialTheme.typography.headlineSmall)
        if (uri.isBlank()) {
            Text(DesktopSettingsText["custom_background_none"], style = MaterialTheme.typography.bodyMedium)
        } else {
            Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(16f / 9f)) {
                DesktopRemoteArtwork(uri, graph.artworkLoader, Modifier.fillMaxSize())
            }
            if (!readable) Text(DesktopSettingsText["custom_background_missing"], color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                write {
                    val picked = DesktopFilePickers().use { it.files(multiple = false).firstOrNull() }
                    if (picked != null) controller.edit { this[PlayerCustomImageUriKey] = picked.toUri().toString() }
                }
            }) { Text(DesktopSettingsText["custom_background_choose"]) }
            if (uri.isNotBlank()) {
                OutlinedButton(onClick = { write { controller.edit { this[PlayerCustomImageUriKey] = null } } }) {
                    Text(DesktopSettingsText["custom_background_remove"])
                }
            }
        }
        LabeledSlider("custom_background_blur", blur, 0f..50f, { blur = it }) {
            write { controller.edit { this[PlayerCustomBlurKey] = blur } }
        }
        LabeledSlider("custom_background_contrast", contrast, 0.5f..2f, { contrast = it }) {
            write { controller.edit { this[PlayerCustomContrastKey] = contrast } }
        }
        LabeledSlider("custom_background_brightness", brightness, 0.5f..2f, { brightness = it }) {
            write { controller.edit { this[PlayerCustomBrightnessKey] = brightness } }
        }
        TextButton(onClick = {
            write {
                controller.edit {
                    this[PlayerCustomBlurKey] = null
                    this[PlayerCustomContrastKey] = null
                    this[PlayerCustomBrightnessKey] = null
                }
            }
        }) { Text(DesktopSettingsText["custom_background_reset"]) }
    }
}

@Composable
private fun LabeledSlider(
    labelKey: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column {
        Text("${DesktopSettingsText[labelKey]}: ${"%.2f".format(value)}", style = MaterialTheme.typography.bodyLarge)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, onValueChangeFinished = onDone)
    }
}

private fun customImageReadable(uri: String): Boolean =
    runCatching { Files.isReadable(Paths.get(URI(uri))) }.getOrDefault(false)

/** The download list with search, selection, export to a folder and delete, like the Android page. */
@Composable
private fun DesktopExportSongsPage(graph: DesktopSessionGraph, show: (String) -> Unit, onFailure: () -> Unit) {
    val rows by remember { graph.downloads.storedRows() }.collectAsState(null)
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var progress by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val songs = rows.orEmpty().map { ExportableSong.of(it) }
    val visible = songs.filter { it.matches(query) }
    val chosen = songs.filter { it.id in selected }

    fun export() {
        scope.launch {
            try {
                val folder = DesktopFilePickers().use { it.folder() } ?: return@launch
                var done = 0
                chosen.forEachIndexed { index, song ->
                    progress = DesktopSettingsText.format("export_songs_progress", index + 1, chosen.size)
                    val source = AudioSourceType.entries.firstOrNull { it.name == song.row.source } ?: return@forEachIndexed
                    val file = graph.downloads.fileOf(song.row.provider, song.row.mediaId, source, song.row.quality.orEmpty())
                        ?: return@forEachIndexed
                    withContext(Dispatchers.IO) { copyExport(file, folder, song.baseName) }
                    done += 1
                }
                show(DesktopSettingsText.format("export_songs_done", done, chosen.size))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("download export failed: ${failure.javaClass.simpleName}")
                onFailure()
            } finally {
                progress = null
            }
        }
    }

    fun delete() {
        scope.launch {
            val ids = chosen.map { it.id }
            ids.forEach { id -> graph.downloads.remove(id) }
            selected = emptySet()
            show(DesktopSettingsText.format("export_songs_deleted", ids.size))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(DesktopSettingsText.format("export_songs_delete_confirm", chosen.size)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; delete() }) { Text(DesktopSettingsText["export_songs_delete"]) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(DesktopStrings["cancel"]) } },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            DesktopSettingsText["export_songs_title"],
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(DesktopSettingsText["export_songs_search"]) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(DesktopSettingsText.format("export_songs_selected", chosen.size), Modifier.weight(1f))
            TextButton(onClick = { selected = selected + visible.map { it.id } }) { Text(DesktopSettingsText["export_songs_select_all"]) }
            TextButton(onClick = { selected = emptySet() }) { Text(DesktopSettingsText["export_songs_clear_selection"]) }
            OutlinedButton(onClick = { confirmDelete = true }, enabled = chosen.isNotEmpty() && progress == null) {
                Text(DesktopSettingsText["export_songs_delete"])
            }
            Button(onClick = ::export, enabled = chosen.isNotEmpty() && progress == null) {
                Text(DesktopSettingsText["export_songs_export"])
            }
        }
        progress?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        when {
            rows == null -> CircularProgressIndicator()
            songs.isEmpty() -> Text(DesktopSettingsText["export_songs_empty"])
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { song ->
                    fun toggle() { selected = if (song.id in selected) selected - song.id else selected + song.id }
                    ListItem(
                        modifier = Modifier.clickable { toggle() },
                        leadingContent = { Checkbox(checked = song.id in selected, onCheckedChange = { toggle() }) },
                        headlineContent = { Text(song.title) },
                        supportingContent = { if (song.artists.isNotBlank()) Text(song.artists) },
                    )
                }
            }
        }
    }
}

private class ExportableSong(val row: PortableStoredSongRow, val title: String, val artists: String) {
    /** The id [DesktopDownloadsOwner] removes a download by. */
    val id: String get() = "${row.provider}:${row.mediaId}"

    val baseName: String get() = DesktopSongExport.fileBaseName(title, artists)

    fun matches(query: String): Boolean =
        query.isBlank() || title.contains(query, ignoreCase = true) || artists.contains(query, ignoreCase = true)

    companion object {
        fun of(row: PortableStoredSongRow): ExportableSong {
            val item = runCatching { row.storedOccurrence().item }.getOrNull()
                ?: return ExportableSong(row, row.mediaId, "")
            val credits = item.artists.joinToString(", ") { it.name }
            return ExportableSong(row, item.title.ifBlank { row.mediaId }, credits.ifBlank { item.subtitle })
        }
    }
}

/** Copies one download beside the others under a readable, unique name with the extension its bytes indicate. */
private fun copyExport(file: Path, folder: Path, baseName: String) {
    val header = Files.newInputStream(file).use { input -> input.readNBytes(16) }
    val extension = DesktopSongExport.extensionOf(header)
    var target = folder.resolve("$baseName.$extension")
    var suffix = 2
    while (Files.exists(target)) {
        target = folder.resolve("$baseName ($suffix).$extension")
        suffix += 1
    }
    Files.copy(file, target)
}

/** Naming rules for exported downloads, apart from the page so they can be checked directly. */
internal object DesktopSongExport {
    fun fileBaseName(title: String, artists: String): String =
        DownloadExportNaming.fileBaseName(title, artists)

    /** Download files carry no extension, so the container is read from the first bytes. */
    fun extensionOf(header: ByteArray): String = DownloadExportNaming.extensionOf(header)
}
