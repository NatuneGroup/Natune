package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.ui.resources.*

/**
 * Integration hub. Every account, switch and token lives on the page of the service it belongs to, so this page
 * only navigates: Spotify's catalog account has its own page, the pool switch sits with the sources it gates and
 * ListenBrainz sits with the other scrobblers. Sign-in, playlist reload, logout and the cross-service import
 * belong to the host, so this page never reports a success it did not observe.
 */
@Composable
fun IntegrationSettings(
    controller: SettingsController,
    navigate: (String) -> Unit,
    onOpenCrossServiceImport: () -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val rows = listOf<SettingsRow>(
        SettingsRow.Action(
            "ai_integration", Res.string.m6_integration_ai, run = { navigate("settings/ai_integration") },
            description = Res.string.m6_integration_ai_desc,
        ),
        SettingsRow.Action(
            "device_connect", Res.string.m9_device_connect_title,
            run = { navigate("settings/integrations/device_connect") },
            description = Res.string.m9_device_connect_summary,
        ),
        SettingsRow.Header("discord_presence", Res.string.m6_integration_general),
        SettingsRow.Action("discord_account", Res.string.m6_integration_discord, run = { navigate("settings/discord") }),
        SettingsRow.Header("music_sources", Res.string.m6_integration_music_sources),
        // The catalog account only imports playlists and metadata; the source switch and the audio path stay on
        // Sources, and the streaming account keeps its own page.
        SettingsRow.Action(
            "spotify", Res.string.m6_source_spotify_catalog, run = { navigate("settings/spotify") },
            description = Res.string.m6_integration_spotify_catalog_desc,
        ),
        SettingsRow.Action("applemusic", Res.string.m6_integration_applemusic, run = { navigate("settings/applemusic") },
            description = Res.string.m6_integration_applemusic_desc),
        SettingsRow.Action("tidal", Res.string.m6_integration_tidal, run = { navigate("settings/tidal") },
            description = Res.string.m6_integration_tidal_desc),
        SettingsRow.Action("qobuz", Res.string.m6_integration_qobuz, run = { navigate("settings/qobuz") },
            description = Res.string.m6_integration_qobuz_desc),
        SettingsRow.Action("deezer", Res.string.m6_integration_deezer, run = { navigate("settings/deezer") },
            description = Res.string.m6_integration_deezer_desc),
        SettingsRow.Action("amazon", Res.string.m6_integration_amazon, run = { navigate("settings/amazon") },
            description = Res.string.m6_integration_amazon_desc),
        SettingsRow.Action("qqmusic", Res.string.m6_integration_qqmusic, run = { navigate("settings/qqmusic") },
            description = Res.string.m6_integration_qqmusic_desc),
        SettingsRow.Action("spotify_streaming", Res.string.m6_integration_spotify_streaming,
            run = { navigate("settings/spotify_streaming") }, description = Res.string.m6_integration_spotify_streaming_desc),
        SettingsRow.Action("telegram", Res.string.m6_integration_telegram, run = { navigate("settings/telegram") },
            description = Res.string.m6_integration_telegram_desc),
        SettingsRow.Header("lastfm_scrobbling", Res.string.m6_integration_scrobbling),
        SettingsRow.Action("lastfm_account", Res.string.m6_integration_lastfm, run = { navigate("settings/lastfm") }),
        SettingsRow.Header("cross_service_import", Res.string.m6_integration_cross_service_title),
        SettingsRow.Action(
            "cross_service_import_entry", Res.string.m6_integration_cross_service_entry,
            run = onOpenCrossServiceImport, description = Res.string.m6_integration_cross_service_entry_desc,
        ),
    )
    SettingsPage(
        rows = rows,
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        onFailure = onFailure,
        modifier = modifier,
    )
}
