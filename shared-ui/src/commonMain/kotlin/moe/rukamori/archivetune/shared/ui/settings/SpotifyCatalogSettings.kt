package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The catalog account only. The streaming sign-in is a separate account with its own page, so its state never
 * appears here.
 */
data class SpotifyCatalogAccountState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val accountName: String = "",
    val playlistCount: Int = 0,
)

/**
 * The Spotify catalog page: sign-in, the two playlist switches, reload and logout. Everything here used to be
 * drawn inline on the Integrations hub; the source switch and the audio path stay on Sources, and the streaming
 * account keeps its own page.
 */
@Composable
fun SpotifyCatalogSettings(
    controller: SettingsController,
    account: SpotifyCatalogAccountState,
    error: String?,
    onConnect: () -> Unit,
    onReloadPlaylists: suspend () -> Unit,
    onLogout: suspend () -> Unit,
    onDismissError: () -> Unit,
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

    val rows = spotifyCatalogRows(
        account = account,
        onConnect = onConnect,
        onReload = { hostAction(onReloadPlaylists) },
        onLogout = { hostAction(onLogout) },
    )
    SettingsPage(
        rows = rows,
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        onFailure = onFailure,
        modifier = modifier,
    )
    error?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(Res.string.m6_integration_import_failed)) },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = onDismissError) { Text(stringResource(Res.string.m6_integration_ok)) }
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
