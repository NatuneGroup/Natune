/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Canvas settings. The shared policy derives every gate from the settings, the host's [connectivity] (null until
 * measured) and the host's [providerProbes]. The page never probes: it asks [refreshHealth] for exactly the providers
 * whose gate is open, and the host reports each result through [providerProbes]. [cacheBytes] is null until the host
 * has measured the cache, so an unmeasured cache is never shown as zero. A committed limit change calls
 * [applyCacheLimit]; a confirmed clear calls [clearCache]. Both throw on failure, which reaches [onFailure].
 */
@Composable
fun CanvasSettings(
    controller: SettingsController,
    connectivity: CanvasConnectivity?,
    providerProbes: CanvasProviderProbes,
    refreshHealth: suspend (Set<CanvasHealthProvider>) -> Unit,
    cacheBytes: Long?,
    applyCacheLimit: suspend (Int) -> Unit,
    clearCache: suspend () -> Unit,
    onFailure: () -> Unit,
    resumed: Boolean = true,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()
    val latestRefresh by rememberUpdatedState(refreshHealth)
    var refreshing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val providersToProbe = preferences.canvasProvidersToProbe(connectivity)
    suspend fun probe(providers: Set<CanvasHealthProvider>) {
        try { latestRefresh(providers) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { onFailure() }
        finally { refreshing = false }
    }
    // The baseline re-ran the provider checks whenever a gate opened or closed. Changing the set cancels the old run.
    LaunchedEffect(providersToProbe) {
        if (providersToProbe.isNotEmpty()) {
            refreshing = true
            probe(providersToProbe)
        }
    }
    val health = preferences.canvasHealthStates(connectivity, providerProbes)
    val betterLyricsHealth = health.getValue(CanvasHealthProvider.BETTER_LYRICS).healthText()
    val appleMusicHealth = health.getValue(CanvasHealthProvider.APPLE_MUSIC).healthText()
    val tidalHealth = health.getValue(CanvasHealthProvider.TIDAL).healthText()
    val spotifyHealth = health.getValue(CanvasHealthProvider.SPOTIFY).healthText()
    val refreshAllowed = preferences.canvasNetworkAllowed(connectivity) &&
        providersToProbe.isNotEmpty() && health.values.none { it == CanvasHealthState.CHECKING }
    val usage = cacheBytes?.let { stringResource(Res.string.m6_cache_bytes, it.coerceAtLeast(0L)) }
        ?: stringResource(Res.string.m6_canvas_cache_unmeasured)
    val rows = buildList<SettingsRow> {
        add(SettingsRow.Toggle(
            "canvas_enable", Res.string.m6_canvas_enable, ArchiveTuneCanvasKey, false,
            description = Res.string.m6_canvas_enable_desc,
        ))
        // A legacy stored BOTH is not an option, so the choice reads as its default ALL, matching the shared source policy.
        add(SettingsRow.Choice(
            "canvas_source", Res.string.m6_canvas_source, CanvasSourceKey, "ALL", canvasSourceOptions,
        ))
        add(SettingsRow.Toggle(
            "canvas_wifi_only", Res.string.m6_canvas_wifi_only, CanvasWifiOnlyKey, false,
            description = Res.string.m6_canvas_wifi_only_desc,
        ))
        add(SettingsRow.Header("canvas_provider_health", Res.string.m6_canvas_provider_health))
        add(SettingsRow.Status("canvas_health_better_lyrics", Res.string.m6_canvas_better_lyrics, betterLyricsHealth))
        add(SettingsRow.Status("canvas_health_apple_music", Res.string.m6_canvas_apple_music, appleMusicHealth))
        add(SettingsRow.Status("canvas_health_tidal", Res.string.m6_canvas_tidal, tidalHealth))
        add(SettingsRow.Status("canvas_health_spotify", Res.string.m6_canvas_spotify, spotifyHealth))
        add(SettingsRow.Action("canvas_refresh_health", Res.string.m6_refresh, run = {
            refreshing = true
            scope.launch { probe(providersToProbe) }
        }, enabled = refreshAllowed && !refreshing))
        add(SettingsRow.Header("canvas_cache", Res.string.m6_canvas_cache))
        add(SettingsRow.Status("canvas_cache_used", Res.string.m6_canvas_cache_used, usage))
        add(SettingsRow.IntChoice(
            "max_cache_size", Res.string.m6_canvas_cache_limit, MaxCanvasCacheSizeKey, 256,
            cacheCapacityOptions(canvas = true),
        ))
        add(SettingsRow.Action(
            "clear_canvas_cache", Res.string.m6_clear_canvas_cache, run = { confirmClear = true },
            description = Res.string.m6_canvas_cache_desc,
        ))
    }
    SettingsPage(
        rows = rows,
        preferences = preferences,
        controller = controller,
        resumed = resumed,
        motionEnabled = motionEnabled,
        onFailure = onFailure,
        modifier = modifier,
        onCommitted = { change ->
            if (MaxCanvasCacheSizeKey in change.changedKeys) applyCacheLimit(change.after[MaxCanvasCacheSizeKey, 256])
        },
    )
    if (confirmClear) SettingsConfirmActionDialog(
        title = Res.string.m6_clear_canvas_cache,
        onConfirm = clearCache,
        onDismiss = { confirmClear = false },
        onFailure = onFailure,
    )
}

private val canvasSourceOptions = listOf(
    SettingsRow.Option("BETTER_LYRICS", Res.string.m6_canvas_better_lyrics),
    SettingsRow.Option("APPLE_MUSIC", Res.string.m6_canvas_apple_music),
    SettingsRow.Option("TIDAL", Res.string.m6_canvas_tidal),
    SettingsRow.Option("SPOTIFY", Res.string.m6_canvas_spotify),
    SettingsRow.Option("ALL", Res.string.m6_canvas_source_all),
)

@Composable
private fun CanvasHealthState.healthText(): String = stringResource(labelResource())

private fun CanvasHealthState.labelResource(): StringResource = when (this) {
    CanvasHealthState.NOT_CHECKED -> Res.string.m6_canvas_health_not_checked
    CanvasHealthState.CHECKING -> Res.string.m6_canvas_health_checking
    CanvasHealthState.AVAILABLE -> Res.string.m6_canvas_health_available
    CanvasHealthState.UNAVAILABLE -> Res.string.m6_canvas_health_unavailable
    CanvasHealthState.NOT_CONNECTED -> Res.string.m6_canvas_health_not_connected
    CanvasHealthState.NOT_SELECTED -> Res.string.m6_canvas_health_not_selected
    CanvasHealthState.DISABLED -> Res.string.m6_canvas_health_disabled
    CanvasHealthState.OFFLINE -> Res.string.m6_canvas_health_offline
    CanvasHealthState.WIFI_REQUIRED -> Res.string.m6_canvas_health_wifi_required
    CanvasHealthState.LOW_DATA_MODE -> Res.string.m6_canvas_health_low_data
}
