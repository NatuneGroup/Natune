package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Last.fm, Libre.fm and custom-endpoint scrobbling. Sign-in and queue delivery are host work: the page routes to
 * [onConnect] and [onFlushPendingScrobbles], and it commits only the identity and service choices it owns.
 * Scrobbling needs a signed-in session and a usable service, so the dependent switches stay disabled until both hold.
 */
@Composable
fun LastFmSettingsPage(
    controller: SettingsController,
    defaultApiKey: String,
    defaultSecret: String,
    pendingScrobbles: Int,
    onConnect: (LastFmProvider) -> Unit,
    onLogout: suspend () -> Unit,
    onFlushPendingScrobbles: suspend () -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()
    var editingService by remember { mutableStateOf(false) }
    val loggedIn = preferences.lastFmLoggedIn()
    val canEnable = preferences.lastFmCanEnableScrobbling(defaultApiKey, defaultSecret)
    val scrobbling = preferences[EnableLastFMScrobblingKey, false]
    val rows = listOf(
        SettingsRow.Action(
            "lastfm_connect_button", Res.string.m6_lastfm_connect_button,
            { onConnect(LastFmProvider.LASTFM) }, description = Res.string.m6_lastfm_connect_button_description,
        ),
        SettingsRow.Action(
            "lastfm_connect_librefm_button", Res.string.m6_lastfm_connect_librefm_button,
            { onConnect(LastFmProvider.LIBREFM) }, description = Res.string.m6_lastfm_connect_librefm_button_description,
        ),
        SettingsRow.Action(
            "lastfm_connect_custom_button", Res.string.m6_lastfm_connect_custom_button,
            { editingService = true }, description = Res.string.m6_lastfm_connect_custom_button_description,
        ),
        SettingsRow.Toggle(
            "enable_scrobbling", Res.string.m6_enable_scrobbling, EnableLastFMScrobblingKey, false,
            enabled = canEnable, write = { setLastFmScrobbling(it) },
        ),
        SettingsRow.Toggle(
            "lastfm_now_playing", Res.string.m6_lastfm_now_playing, LastFMUseNowPlaying, false,
            enabled = canEnable && scrobbling, write = { setLastFmNowPlaying(it) },
        ),
        SettingsRow.Toggle(
            "lastfm_send_loves", Res.string.m6_lastfm_send_loves, LastFmSendLovesKey, false,
            description = Res.string.m6_lastfm_send_loves_desc, enabled = loggedIn,
        ),
        SettingsRow.Toggle(
            "lastfm_prefer_yt_thumbnails", Res.string.m6_lastfm_prefer_yt_thumbnails, LastFmPreferYtThumbnailsKey, false,
            description = Res.string.m6_lastfm_prefer_yt_thumbnails_desc,
        ),
        SettingsRow.Number(
            "scrobble_min_track_duration", Res.string.m6_scrobble_min_track_duration, ScrobbleMinSongDurationKey,
            LastFmDefaults.DEFAULT_SCROBBLE_MIN_SONG_DURATION, LastFmDefaults.MIN_SONG_DURATION_RANGE,
            unit = SettingsUnit.SECONDS,
        ),
        SettingsRow.Decimal(
            "scrobble_delay_percent", Res.string.m6_scrobble_delay_percent, ScrobbleDelayPercentKey,
            LastFmDefaults.DEFAULT_SCROBBLE_DELAY_PERCENT, LastFmDefaults.DELAY_PERCENT_RANGE,
            unit = SettingsUnit.PERCENT,
        ),
        SettingsRow.Number(
            "scrobble_delay_minutes", Res.string.m6_scrobble_delay_minutes, ScrobbleDelaySecondsKey,
            LastFmDefaults.DEFAULT_SCROBBLE_DELAY_SECONDS, LastFmDefaults.DELAY_SECONDS_RANGE,
            unit = SettingsUnit.SECONDS,
        ),
    )
    Column(modifier.fillMaxSize()) {
        if (loggedIn) ListItem(
            headlineContent = { Text(preferences[LastFMUsernameKey].orEmpty()) },
            trailingContent = {
                OutlinedButton(onClick = {
                    scope.runHostAction(onFailure) {
                        onLogout()
                        controller.edit { clearLastFmSession() }
                    }
                }) { Text(stringResource(Res.string.m6_action_logout)) }
            },
        )
        if (pendingScrobbles > 0) ListItem(
            headlineContent = { Text(stringResource(Res.string.m6_lastfm_pending_scrobbles_title)) },
            supportingContent = { Text(stringResource(
                if (pendingScrobbles == 1) Res.string.m6_lastfm_pending_one else Res.string.m6_lastfm_pending_other,
                pendingScrobbles,
            )) },
            trailingContent = {
                OutlinedButton(enabled = loggedIn, onClick = {
                    scope.runHostAction(onFailure, onFlushPendingScrobbles)
                }) { Text(stringResource(Res.string.m6_lastfm_pending_scrobbles_send_now)) }
            },
        )
        SettingsPage(rows, preferences, controller, scrollTo, onFailure = onFailure, modifier = Modifier.weight(1f))
    }
    if (editingService) {
        val stored = preferences.lastFmCredentialsFor(LastFmProvider.CUSTOM)
        var endpoint by remember { mutableStateOf(preferences[LastFMCustomEndpointKey].orEmpty()) }
        var apiKey by remember { mutableStateOf(stored.apiKey) }
        var secret by remember { mutableStateOf(stored.secret) }
        var saving by remember { mutableStateOf(false) }
        val endpointInvalid = endpoint.isNotBlank() && normalizeLastFmEndpointOrNull(endpoint) == null
        val endpointValid = normalizeLastFmEndpointOrNull(endpoint) != null
        AlertDialog(
            onDismissRequest = { if (!saving) editingService = false },
            title = { Text(stringResource(Res.string.m6_lastfm_custom_endpoint)) },
            text = { Column {
                Text(stringResource(Res.string.m6_lastfm_api_credentials_hint))
                OutlinedTextField(
                    value = endpoint, onValueChange = { endpoint = it }, singleLine = true,
                    isError = endpointInvalid,
                    label = { Text(stringResource(Res.string.m6_lastfm_custom_endpoint)) },
                    supportingText = { if (endpointInvalid) Text(stringResource(Res.string.m6_lastfm_endpoint_invalid)) },
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it }, singleLine = true,
                    label = { Text(stringResource(Res.string.m6_lastfm_api_key_override)) },
                )
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it }, singleLine = true,
                    label = { Text(stringResource(Res.string.m6_lastfm_secret_override)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
            } },
            confirmButton = { TextButton(enabled = endpointValid && !saving, onClick = {
                scope.runHostAction(onFailure) {
                    saving = true
                    try {
                        controller.edit { saveLastFmServiceConfig(LastFmProvider.CUSTOM, endpoint, apiKey, secret) }
                        editingService = false
                    } finally {
                        saving = false
                    }
                }
            }) { Text(stringResource(Res.string.m6_save)) } },
            dismissButton = { TextButton(enabled = !saving, onClick = { editingService = false }) {
                Text(stringResource(Res.string.m6_cancel))
            } },
        )
    }
}

/** Runs a host-owned suspend action; cancellation propagates, any other failure reaches the host's failure surface. */
private fun CoroutineScope.runHostAction(onFailure: () -> Unit, action: suspend () -> Unit) {
    launch {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onFailure()
        }
    }
}
