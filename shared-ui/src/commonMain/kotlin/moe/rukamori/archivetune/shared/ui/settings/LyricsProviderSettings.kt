/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
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
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixDiagnostics
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixStats
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

private enum class ProviderDialog { ORDER, STATS, ENDPOINTS }

/**
 * Pass real published PaxsenixDiagnostics::getStats/checkProviderPaths bindings built from the given
 * snapshot. Neither this page nor the common transport provisions a missing built-in bearer.
 */
@Composable
fun LyricsProviderSettings(
    controller: SettingsController,
    readStats: suspend (PreferenceSnapshot) -> PaxsenixStats,
    checkEndpoints: suspend (PreferenceSnapshot) -> List<PaxsenixDiagnostics.PathCheck>,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    var dialog by remember { mutableStateOf<ProviderDialog?>(null) }
    val scope = rememberCoroutineScope()
    val enabled = !preferences[PrioritizeWordSyncedLyricsKey, false]
    val paxVisible = preferences[EnablePaxsenixLyricsKey, true]
    LaunchedEffect(enabled, paxVisible) {
        if (!enabled || !paxVisible && dialog != ProviderDialog.ORDER) dialog = null
    }
    SettingsPage(
        lyricsProviderSettingsRows(preferences, { dialog = ProviderDialog.ORDER }, { dialog = ProviderDialog.STATS },
            { dialog = ProviderDialog.ENDPOINTS }),
        preferences, controller, scrollTo, onFailure = onFailure, modifier = modifier,
    )
    if (dialog == ProviderDialog.ORDER && enabled) {
        var order by remember(dialog) { mutableStateOf(preferences.lyricsProviderOrder()) }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(Res.string.m6_provider_order)) },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
                itemsIndexed(order, key = { _, provider -> provider.name }) { index, provider ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(stringResource(provider.label()), Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(enabled = index > 0, onClick = {
                            order = order.toMutableList().apply { add(index - 1, removeAt(index)) }
                        }) { Text(stringResource(Res.string.m6_move_up)) }
                        TextButton(enabled = index < order.lastIndex, onClick = {
                            order = order.toMutableList().apply { add(index + 1, removeAt(index)) }
                        }) { Text(stringResource(Res.string.m6_move_down)) }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { scope.launch {
                try {
                    controller.edit {
                        if (!this[PrioritizeWordSyncedLyricsKey, false]) setLyricsProviderOrder(order)
                    }
                    dialog = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { onFailure() }
            } }) { Text(stringResource(Res.string.m6_save)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(Res.string.m6_cancel)) } },
        )
    }
    val selected = dialog
    if (selected in setOf(ProviderDialog.STATS, ProviderDialog.ENDPOINTS) && enabled && paxVisible) {
        var stats by remember(selected, preferences[PaxsenixEndpointKey], preferences[PaxsenixApiKeyKey]) { mutableStateOf<PaxsenixStats?>(null) }
        var checks by remember(selected, preferences[PaxsenixEndpointKey], preferences[PaxsenixApiKeyKey]) {
            mutableStateOf<List<PaxsenixDiagnostics.PathCheck>?>(null)
        }
        var failed by remember(selected, preferences[PaxsenixEndpointKey], preferences[PaxsenixApiKeyKey]) { mutableStateOf(false) }
        LaunchedEffect(selected, preferences[PaxsenixEndpointKey], preferences[PaxsenixApiKeyKey]) {
            try {
                if (selected == ProviderDialog.STATS) stats = readStats(preferences)
                else checks = checkEndpoints(preferences)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(if (selected == ProviderDialog.STATS) Res.string.m6_provider_stats else Res.string.m6_check_endpoints)) },
            text = {
                val loaded = stats
                val paths = checks
                when {
                    failed -> Text(stringResource(Res.string.m6_ai_request_failed))
                    loaded != null -> Column {
                        Text(stringResource(Res.string.m6_pax_requests, loaded.totalRequests))
                        Text(stringResource(Res.string.m6_pax_success, loaded.overallSuccessRate))
                        Text(stringResource(Res.string.m6_pax_uptime, loaded.uptimeSeconds.toLong()))
                    }
                    paths != null -> LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        itemsIndexed(paths) { _, path ->
                            ListItem(headlineContent = { Text(path.provider) }, supportingContent = {
                                Text(stringResource(when (path.status) {
                                    PaxsenixDiagnostics.PathStatus.AVAILABLE -> Res.string.m6_endpoint_available
                                    PaxsenixDiagnostics.PathStatus.RETIRED -> Res.string.m6_endpoint_retired
                                    PaxsenixDiagnostics.PathStatus.UNREACHABLE -> Res.string.m6_endpoint_unreachable
                                }))
                            })
                        }
                    }
                    else -> CircularProgressIndicator()
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(Res.string.m6_done)) } },
        )
    }
}
