package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun DownloadsSettings(
    controller: SettingsController,
    poolConfigured: Boolean,
    clearDownloads: suspend () -> Unit,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    var clear by remember { mutableStateOf(false) }
    var reorder by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val rows = listOf(
        SettingsRow.Action("clear_all_downloads", Res.string.m6_clear_downloads, { clear = true }),
        SettingsRow.Action("export_downloaded_songs", Res.string.m6_export_downloads, { navigate("settings/storage/export_songs") }),
        SettingsRow.Toggle("auto_download_like", Res.string.m6_download_like, AutoDownloadOnLikeKey, false),
        SettingsRow.Action("download_source", Res.string.m6_download_sources, { reorder = true }),
        SettingsRow.Toggle("external_downloader", Res.string.m6_external_downloader, ExternalDownloaderEnabledKey, false),
        SettingsRow.Text("external_downloader_package", Res.string.m6_external_downloader_package, ExternalDownloaderPackageKey,
            enabled = preferences[ExternalDownloaderEnabledKey, false]),
    )
    SettingsPage(rows, preferences, controller, scrollTo, onFailure = onFailure, modifier = modifier)
    if (clear) SettingsConfirmActionDialog(Res.string.m6_clear_downloads, clearDownloads, { clear = false }, onFailure)
    if (reorder) {
        var order by remember(reorder) { mutableStateOf(preferences.downloadSourcePreferenceOrder()) }
        var saving by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) reorder = false },
            title = { Text(stringResource(Res.string.m6_download_sources)) },
            text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(order, key = { _, id -> id }) { index, id ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(when (id) {
                                "QOBUZ" -> Res.string.m6_source_qobuz
                                "QOBUZ_BACKUP" -> Res.string.m6_source_qobuz_backup
                                "TIDAL" -> Res.string.m6_source_tidal
                                "DEEZER" -> Res.string.m6_source_deezer
                                "JIOSAAVN" -> Res.string.m6_source_jio
                                "YOUTUBE_MUSIC" -> Res.string.m6_source_youtube
                                else -> Res.string.m6_quality_auto
                            }))
                            if (!poolConfigured && id in setOf("QOBUZ", "TIDAL", "DEEZER"))
                                Text(stringResource(Res.string.m6_download_requires_pool))
                        }
                        TextButton(enabled = !saving && index > 0, onClick = {
                            order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                        }) { Text(stringResource(Res.string.m6_move_up)) }
                        TextButton(enabled = !saving && index < order.lastIndex, onClick = {
                            order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                        }) { Text(stringResource(Res.string.m6_move_down)) }
                    }
                }
            } },
            confirmButton = { TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    try { controller.edit { setDownloadSourcePreferenceOrder(order) }; reorder = false }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { onFailure() }
                    finally { saving = false }
                }
            }) { Text(stringResource(Res.string.m6_save)) } },
            dismissButton = { TextButton(enabled = !saving, onClick = { reorder = false }) { Text(stringResource(Res.string.m6_cancel)) } },
        )
    }
}
