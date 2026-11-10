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
import moe.rukamori.archivetune.shared.domain.source.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Presentation outcomes only: the real pool owner retains its persisted throttle and error state. */
enum class SourcePoolRefreshOutcome { REFRESHED, UP_TO_DATE, FAILED }
private enum class SourceDialog { ORDER, CHECK, POOL }

@Composable
fun SourceSettings(
    controller: SettingsController,
    spotifyStreamingReady: Boolean,
    poolConfigured: Boolean,
    refreshPool: suspend () -> SourcePoolRefreshOutcome,
    checkSource: suspend (AudioSourceType, PreferenceSnapshot) -> Boolean,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
    /** False on a host with no Spotify streaming transport: the source reads as not available there, never as ready. */
    spotifyStreamingPlatformSupported: Boolean = true,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SourceDialog?>(null) }
    val poolEnabled = poolConfigured && preferences[UsePoolAccountsKey, true]
    val spotifyReady = spotifyStreamingReady && spotifyStreamingPlatformSupported
    LaunchedEffect(poolEnabled) { if (!poolEnabled && dialog == SourceDialog.POOL) dialog = null }
    SettingsPage(sourceSettingsRows(preferences, spotifyStreamingReady, navigate,
        { dialog = SourceDialog.ORDER }, { dialog = SourceDialog.CHECK },
        if (poolEnabled) ({ dialog = SourceDialog.POOL }) else null, poolConfigured, spotifyStreamingPlatformSupported),
        preferences, controller, scrollTo, onFailure = onFailure, modifier = modifier,
        onCommitted = { change ->
            val poolSources = listOf(QobuzEnabledKey, DeezerEnabledKey, AppleMusicSourceEnabledKey, AmazonEnabledKey)
            if (poolConfigured && change.after[UsePoolAccountsKey, true] &&
                poolSources.any { it in change.changedKeys && change.after[it] == true }) {
                if (refreshPool() == SourcePoolRefreshOutcome.FAILED) onFailure()
            }
        })
    if (dialog == SourceDialog.ORDER) {
        var order by remember(dialog) { mutableStateOf(AudioSourceConfig.parseOrder(preferences[AudioSourceOrderKey])) }
        var saving by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) dialog = null },
            title = { Text(stringResource(Res.string.m6_preferred_sources)) },
            text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(order, key = { _, source -> source.name }) { index, source ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(source.settingsLabel()))
                            Text(stringResource(if (preferences.audioSourceEnabled(source, spotifyReady))
                                Res.string.m6_source_enabled else Res.string.m6_source_disabled))
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
                    try {
                        val change = controller.edit { setAudioSourcePriority(order) }
                        if (poolConfigured && change.after[UsePoolAccountsKey, true] &&
                            order.first() in setOf(AudioSourceType.QOBUZ, AudioSourceType.DEEZER, AudioSourceType.APPLE, AudioSourceType.AMAZON))
                            if (refreshPool() == SourcePoolRefreshOutcome.FAILED) onFailure()
                        dialog = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { onFailure() }
                    finally { saving = false }
                }
            }) { Text(stringResource(Res.string.m6_save)) } },
            dismissButton = { TextButton(enabled = !saving, onClick = { dialog = null }) { Text(stringResource(Res.string.m6_cancel)) } },
        )
    }
    val operation = dialog
    if (operation == SourceDialog.CHECK || operation == SourceDialog.POOL && poolEnabled) {
        val probe = remember(preferences, spotifyReady) { sourceProbePlan(preferences, spotifyReady) }
        var checks by remember(operation, probe) { mutableStateOf<Map<AudioSourceType, Boolean>?>(null) }
        var refresh by remember(operation) { mutableStateOf<SourcePoolRefreshOutcome?>(null) }
        var failed by remember(operation) { mutableStateOf(false) }
        // A pool refresh has no probe inputs; only the check dialog restarts when its source set changes.
        val probeKey = if (operation == SourceDialog.CHECK) probe else null
        LaunchedEffect(operation, probeKey) {
            failed = false
            try {
                if (operation == SourceDialog.POOL) refresh = refreshPool()
                else {
                    val results = linkedMapOf<AudioSourceType, Boolean>()
                    for (source in probe.order) {
                        if (probe.enabled[source] == true) results[source] = checkSource(source, preferences)
                    }
                    checks = results
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(if (operation == SourceDialog.CHECK) Res.string.m6_check_sources else Res.string.m6_refresh_pool)) },
            text = { Column {
                when {
                    failed || refresh == SourcePoolRefreshOutcome.FAILED -> Text(stringResource(Res.string.m6_ai_request_failed))
                    refresh != null -> Text(stringResource(if (refresh == SourcePoolRefreshOutcome.REFRESHED)
                        Res.string.m6_pool_refreshed else Res.string.m6_pool_up_to_date))
                    checks != null -> {
                        if (checks!!.isEmpty()) Text(stringResource(Res.string.m6_no_enabled_sources))
                        checks!!.forEach { (source, available) ->
                            Text(stringResource(source.settingsLabel()) + ": " +
                                stringResource(if (available) Res.string.m6_source_available else Res.string.m6_source_unavailable))
                        }
                    }
                    else -> CircularProgressIndicator()
                }
            } },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(Res.string.m6_done)) } },
        )
    }
}

/**
 * The only preference values the source check reads: the order it walks and which sources it may probe. Keying
 * the dialog on this instead of the whole snapshot keeps an unrelated settings write from discarding collected
 * results and probing every enabled source again.
 */
internal data class SourceProbePlan(
    val order: List<AudioSourceType>,
    val enabled: Map<AudioSourceType, Boolean>,
)

internal fun sourceProbePlan(preferences: PreferenceSnapshot, spotifyReady: Boolean): SourceProbePlan =
    SourceProbePlan(
        order = AudioSourceConfig.parseOrder(preferences[AudioSourceOrderKey]),
        enabled = AudioSourceType.entries.associateWith { preferences.audioSourceEnabled(it, spotifyReady) },
    )

fun sourceSettingsRows(
    preferences: PreferenceSnapshot,
    spotifyStreamingReady: Boolean,
    navigate: (String) -> Unit,
    reorder: () -> Unit,
    checkSources: () -> Unit,
    refreshPool: (() -> Unit)?,
    poolConfigured: Boolean = false,
    spotifyStreamingPlatformSupported: Boolean = true,
): List<SettingsRow> = buildList {
    refreshPool?.let { add(SettingsRow.Action("youtube_music", Res.string.m6_refresh_pool, it)) }
    // The pool switch moved here from the Integrations hub: it only decides whether the shared accounts of the
    // sources listed below are used, and the list can no longer write it from two places.
    add(SettingsRow.Toggle(
        "use_pool_accounts", Res.string.m6_integration_use_pool_accounts, UsePoolAccountsKey, true,
        description = if (poolConfigured) Res.string.m6_integration_pool_enabled_desc
        else Res.string.m6_integration_pool_unavailable_desc,
        enabled = poolConfigured,
        read = { poolConfigured && it[UsePoolAccountsKey, true] },
    ))
    add(SettingsRow.Action("preferred_sources", Res.string.m6_preferred_sources, reorder))
    val catalogs = listOf(SettingsRow.Option("YOUTUBE", Res.string.m6_source_youtube),
        SettingsRow.Option("SPOTIFY", Res.string.m6_source_spotify_catalog), SettingsRow.Option("APPLE_MUSIC", Res.string.m6_apple_music))
    add(SettingsRow.Choice("default_metadata_source", Res.string.m6_metadata_source, DefaultMetadataSourceKey, "YOUTUBE", catalogs))
    add(SettingsRow.Choice("default_search_source", Res.string.m6_search_source, DefaultSearchSourceKey, "YOUTUBE", catalogs))
    add(SettingsRow.Action("spotify_catalog_source", Res.string.m6_source_spotify_catalog, { navigate("settings/spotify") }))
    add(SettingsRow.Toggle("youtube_source_enable", Res.string.m6_source_youtube, YouTubeSourceEnabledKey, true))
    add(SettingsRow.Choice("audio_quality", Res.string.m6_audio_quality, AudioQualityKey, "AUTO", listOf(
        SettingsRow.Option("AUTO", Res.string.m6_quality_auto), SettingsRow.Option("HIGH", Res.string.m6_high),
        SettingsRow.Option("HIGHEST", Res.string.m6_highest), SettingsRow.Option("LOW", Res.string.m6_low))))
    add(SettingsRow.Toggle("auto_choose_playback_client", Res.string.m6_auto_client, AutoChoosePlaybackClientKey, true))
    add(SettingsRow.Choice("player_stream_client", Res.string.m6_playback_client, PlayerStreamClientKey,
        PlaybackClientChoice.WEB_REMIX.preference,
        PlaybackClientChoice.entries.map { SettingsRow.Option(it.preference, it.settingsLabel()) },
        enabled = !preferences[AutoChoosePlaybackClientKey, true]))
    add(SettingsRow.Toggle("tidal_enable", Res.string.m6_source_tidal, TidalEnabledKey, true))
    add(SettingsRow.Toggle("tidal_account_first", Res.string.m6_tidal_account_first, TidalAccountFirstKey, true, enabled = preferences[TidalEnabledKey, true]))
    add(SettingsRow.Choice("tidal_audio_quality", Res.string.m6_audio_quality, TidalAudioQualityKey, "FLAC",
        qualityOptions("AAC_320", "FLAC", "HI_RES_LOSSLESS"), enabled = preferences[TidalEnabledKey, true]))
    add(SettingsRow.Toggle("tidal_animated_covers", Res.string.m6_animated_artwork, TidalAnimatedCoversEnabledKey, false, enabled = preferences[TidalEnabledKey, true]))
    // Instance management lives on the Tidal and Qobuz pages, so these rows open them instead of the hub.
    add(SettingsRow.Action("tidal_manage_instances", Res.string.m6_tidal_manage, { navigate("settings/tidal") }))
    add(SettingsRow.Toggle("qobuz_enable", Res.string.m6_source_qobuz, QobuzEnabledKey, false))
    // The Qobuz quality picker lives on the Qobuz page, with the account it belongs to.
    add(SettingsRow.Action("qobuz_manage_instances", Res.string.m6_qobuz_manage, { navigate("settings/qobuz") }))
    add(SettingsRow.Toggle("qobuz_backup_enable", Res.string.m6_source_qobuz_backup, QobuzBackupEnabledKey, false))
    add(SettingsRow.Toggle("applemusic_enable", Res.string.m6_apple_music, AppleMusicSourceEnabledKey, false))
    add(SettingsRow.Choice("applemusic_quality", Res.string.m6_audio_quality, AppleMusicQualityKey, "LOSSLESS", qualityOptions("AAC", "LOSSLESS", "HI_RES_LOSSLESS")))
    add(SettingsRow.Action("applemusic_settings", Res.string.m6_apple_music, { navigate("settings/applemusic") }))
    add(SettingsRow.Toggle("deezer_enable", Res.string.m6_source_deezer, DeezerEnabledKey, false))
    add(SettingsRow.Choice("deezer_audio_quality", Res.string.m6_audio_quality, DeezerAudioQualityKey, "FLAC",
        qualityOptions("FLAC", "MP3_320", "MP3_128"), enabled = preferences[DeezerEnabledKey, false]))
    add(SettingsRow.Toggle("amazon_enable", Res.string.m6_source_amazon, AmazonEnabledKey, false))
    add(SettingsRow.Action("amazon_settings", Res.string.m6_source_amazon, { navigate("settings/amazon") }))
    add(SettingsRow.Toggle("qq_music_enable", Res.string.m6_source_qq, QqMusicEnabledKey, false))
    add(SettingsRow.Action("qq_music_settings", Res.string.m6_source_qq, { navigate("settings/qqmusic") }))
    // A host with no Spotify streaming transport shows the row as not available and cannot switch it on. The stored
    // choice itself is left as it is, so a later host that has the transport reads the same saved value.
    val spotifyReady = spotifyStreamingReady && spotifyStreamingPlatformSupported
    add(SettingsRow.Toggle("spotify_stream_enable", Res.string.m6_source_spotify_stream, SpotifyStreamEnabledKey, false,
        description = if (spotifyStreamingPlatformSupported) null else Res.string.m6_spotify_stream_platform_unavailable,
        enabled = spotifyStreamingPlatformSupported && (spotifyReady || preferences[SpotifyStreamEnabledKey, false]),
        write = { selected -> require(!selected || spotifyReady); this[SpotifyStreamEnabledKey] = selected }))
    add(SettingsRow.Action("spotify_stream_settings", Res.string.m6_source_spotify_stream, { navigate("settings/spotify_streaming") }))
    add(SettingsRow.Toggle("jiosaavn_enable", Res.string.m6_source_jio, JioSaavnEnabledKey, false))
    // The quality picker lives on the JioSaavn page, with the account it belongs to.
    add(SettingsRow.Action("jiosaavn_settings", Res.string.m6_source_jio, { navigate("settings/jiosaavn") }))
    add(SettingsRow.Action("check_source", Res.string.m6_check_sources, checkSources))
}

internal fun AudioSourceType.settingsLabel(): StringResource = when (this) {
    AudioSourceType.TIDAL -> Res.string.m6_source_tidal
    AudioSourceType.QOBUZ -> Res.string.m6_source_qobuz
    AudioSourceType.QOBUZ_BACKUP -> Res.string.m6_source_qobuz_backup
    AudioSourceType.DEEZER -> Res.string.m6_source_deezer
    AudioSourceType.APPLE -> Res.string.m6_apple_music
    AudioSourceType.AMAZON -> Res.string.m6_source_amazon
    AudioSourceType.MODULES -> Res.string.source_modules
    AudioSourceType.QQ -> Res.string.m6_source_qq
    AudioSourceType.SPOTIFY -> Res.string.m6_source_spotify_stream
    AudioSourceType.JIOSAAVN -> Res.string.m6_source_jio
    AudioSourceType.YOUTUBE -> Res.string.m6_source_youtube
}

internal fun qualityOptions(vararg names: String): List<SettingsRow.Option> = names.map {
    SettingsRow.Option(it, when (it) {
        "FLAC", "LOSSLESS" -> Res.string.m6_lossless
        "HI_RES", "HI_RES_LOSSLESS" -> Res.string.m6_hi_res
        "MAX" -> Res.string.m6_highest
        "AAC" -> Res.string.m6_aac
        "AAC_320", "MP3_320", "QUALITY_320" -> Res.string.m6_quality_320
        "MP3_128" -> Res.string.m6_quality_128
        "QUALITY_160" -> Res.string.m6_quality_160
        "QUALITY_96" -> Res.string.m6_quality_96
        else -> error("Unsupported quality identity")
    })
}

/** The name one playback client is listed under; exhaustive, so a new choice cannot be offered without its label. */
internal fun PlaybackClientChoice.settingsLabel(): StringResource = when (this) {
    PlaybackClientChoice.ANDROID_VR -> Res.string.m6_client_android_vr
    PlaybackClientChoice.WEB_REMIX -> Res.string.m6_client_web_remix
    PlaybackClientChoice.IOS -> Res.string.m6_client_ios
    PlaybackClientChoice.TVHTML5 -> Res.string.m6_client_tv
    PlaybackClientChoice.ANDROID_MUSIC -> Res.string.m6_client_android_music
}
