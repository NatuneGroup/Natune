package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue

/**
 * The catalog account only. Streaming sign-in is a separate account with its own page, so its state
 * never appears here.
 */
data class SpotifyCatalogAccountState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val accountName: String = "",
    val playlistCount: Int = 0,
)

/**
 * Integration hub. Sub-pages are navigation rows. Switches and the ListenBrainz token persist here.
 * Sign-in, playlist reload, logout, pool-state mirroring and the cross-service import belong to the
 * host, so this page never reports a success it did not observe.
 */
@Composable
fun IntegrationSettings(
    controller: SettingsController,
    poolConfigured: Boolean,
    spotifyAccount: SpotifyCatalogAccountState,
    spotifyError: String?,
    navigate: (String) -> Unit,
    onConnectSpotify: () -> Unit,
    onReloadSpotifyPlaylists: suspend () -> Unit,
    onLogoutSpotify: suspend () -> Unit,
    onDismissSpotifyError: () -> Unit,
    onPoolAccountsChanged: suspend (Boolean) -> Unit,
    onOpenCrossServiceImport: () -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()

    fun hostAction(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
        }
    }

    val spotifyRows = spotifyCatalogRows(
        account = spotifyAccount,
        onConnect = onConnectSpotify,
        onReload = { hostAction(onReloadSpotifyPlaylists) },
        onLogout = { hostAction(onLogoutSpotify) },
    )
    val rows = listOf<SettingsRow>(
        SettingsRow.Action(
            "ai_integration", Res.string.m6_integration_ai, run = { navigate("settings/ai_integration") },
            description = Res.string.m6_integration_ai_desc,
        ),
        SettingsRow.Header("discord_presence", Res.string.m6_integration_general),
        SettingsRow.Action("discord_account", Res.string.m6_integration_discord, run = { navigate("settings/discord") }),
        SettingsRow.Header("music_sources", Res.string.m6_integration_music_sources),
        // The switch reads as off whenever no pool is configured, and it cannot be turned on then.
        SettingsRow.Toggle(
            "use_pool_accounts", Res.string.m6_integration_use_pool_accounts, UsePoolAccountsKey, true,
            description = if (poolConfigured) Res.string.m6_integration_pool_enabled_desc
            else Res.string.m6_integration_pool_unavailable_desc,
            enabled = poolConfigured,
            read = { poolConfigured && it[UsePoolAccountsKey, true] },
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
    ) + spotifyRows + listOf<SettingsRow>(
        SettingsRow.Header("lastfm_scrobbling", Res.string.m6_integration_scrobbling),
        SettingsRow.Action("lastfm_account", Res.string.m6_integration_lastfm, run = { navigate("settings/lastfm") }),
        SettingsRow.Toggle(
            "listenbrainz", Res.string.m6_integration_listenbrainz, ListenBrainzEnabledKey, false,
            description = Res.string.m6_integration_listenbrainz_desc,
        ),
        // The token is a credential, so the editor masks it and the vault route keeps it out of ordinary storage.
        SettingsRow.Text(
            "listenbrainz_token", Res.string.m6_integration_listenbrainz_token_edit, ListenBrainzTokenKey,
            masked = true, valid = { it.isNotEmpty() },
            titleFor = { stored ->
                if (stored[ListenBrainzTokenKey, ""].isBlank()) Res.string.m6_integration_listenbrainz_token_set
                else Res.string.m6_integration_listenbrainz_token_edit
            },
        ),
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
        onCommitted = { change ->
            if (UsePoolAccountsKey in change.changedKeys) {
                onPoolAccountsChanged(change.after[UsePoolAccountsKey, true])
            }
        },
    )
    spotifyError?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissSpotifyError,
            title = { Text(stringResource(Res.string.m6_integration_import_failed)) },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = onDismissSpotifyError) { Text(stringResource(Res.string.m6_integration_ok)) }
            },
        )
    }
}

/**
 * Signed out shows only the connect entry. Signed in shows the account, the two catalog switches, reload
 * and logout, and the switches and actions are held while the library is loading.
 */
@Composable
private fun spotifyCatalogRows(
    account: SpotifyCatalogAccountState,
    onConnect: () -> Unit,
    onReload: () -> Unit,
    onLogout: () -> Unit,
): List<SettingsRow> {
    if (!account.isAuthenticated) {
        return listOf(
            SettingsRow.Header("external_sources", Res.string.m6_integration_external_sources),
            SettingsRow.Action(
                "spotify", Res.string.m6_integration_spotify_connect, run = onConnect, enabled = !account.isLoading,
                description = Res.string.m6_integration_spotify_not_connected,
            ),
        )
    }
    val accountLine = if (account.accountName.isNotBlank()) {
        stringResource(Res.string.m6_integration_spotify_connected_as, account.accountName)
    } else {
        stringResource(Res.string.m6_integration_spotify_account)
    }
    val stateLine = when {
        account.isLoading -> stringResource(Res.string.m6_integration_spotify_loading)
        account.playlistCount > 0 -> stringResource(Res.string.m6_integration_spotify_available, account.playlistCount)
        else -> stringResource(Res.string.m6_integration_spotify_no_playlists)
    }
    return listOf(
        SettingsRow.Header("external_sources", Res.string.m6_integration_external_sources),
        SettingsRow.Status(
            "spotify", Res.string.m6_integration_spotify_account, value = "$accountLine\n$stateLine", enabled = false,
        ),
        SettingsRow.Toggle(
            "spotify_show_playlists", Res.string.m6_integration_spotify_show_playlists, ShowSpotifyPlaylistsKey, false,
            description = Res.string.m6_integration_spotify_show_playlists_desc, enabled = !account.isLoading,
        ),
        SettingsRow.Toggle(
            "spotify_sync_history", Res.string.m6_integration_spotify_sync_history, SpotifyHistorySyncEnabledKey, false,
            description = Res.string.m6_integration_spotify_sync_history_desc, enabled = !account.isLoading,
        ),
        SettingsRow.Action(
            "spotify_reload", Res.string.m6_integration_spotify_reload, run = onReload, enabled = !account.isLoading,
            description = Res.string.m6_integration_spotify_reload_desc,
        ),
        SettingsRow.Action("spotify_logout", Res.string.m6_action_logout, run = onLogout, enabled = !account.isLoading),
    )
}
