package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.SpotifySignInRefusal
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings for the experimental Spotify streaming account, which is separate from the catalog account. The host runs
 * the isolated sign-in, decides it with [SpotifyStreamingPolicy] and reports a refusal through [signInRefusal]; nothing
 * is stored for a refused sign-in. [onSignOut] clears the host's in-memory session first, and the page then commits the
 * stored streaming session away in one edit. [onCancelSignIn] drops an in-progress sign-in; the host discards any result
 * that lands after it. Without it the in-progress row stays a disabled status. [hostNote] is a host limit shown under
 * the warning, such as a host that signs the account in but cannot play its audio.
 */
@Composable
fun SpotifyStreamingSettings(
    controller: SettingsController,
    signInInProgress: Boolean,
    signInRefusal: SpotifySignInRefusal?,
    onSignIn: () -> Unit,
    onDismissSignInRefusal: () -> Unit,
    onSignOut: suspend () -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    resumed: Boolean = true,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onCancelSignIn: (() -> Unit)? = null,
    hostNote: String? = null,
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
    val signedIn = preferences.spotifyStreamingSignedIn()
    val accountLabel = preferences[SpotifyStreamAccountNameKey]?.takeIf { it.isNotBlank() }
        ?: preferences[SpotifyStreamUserIdKey]?.takeIf { it.isNotBlank() }
        ?: "?"
    val rows = buildList<SettingsRow> {
        add(SettingsRow.Status(
            "spotify_stream_warning", Res.string.m6_spotify_stream_warning_title,
            stringResource(Res.string.m6_spotify_stream_warning_body), fullText = true,
        ))
        if (hostNote != null) {
            add(SettingsRow.Status("spotify_stream_host_note", Res.string.m6_integration_spotify_streaming, hostNote, fullText = true))
        }
        add(SettingsRow.Header("spotify_stream_account", Res.string.m6_spotify_stream_account))
        if (signedIn) {
            add(SettingsRow.Status("spotify_stream_signed_in", Res.string.m6_spotify_stream_signed_in_label, accountLabel))
            add(SettingsRow.Action(
                "spotify_stream_signed_in_desc", Res.string.m6_spotify_stream_signed_in_desc, run = {}, enabled = false,
            ))
            add(SettingsRow.Action("spotify_stream_sign_out", Res.string.m6_spotify_stream_sign_out, run = {
                hostAction {
                    onSignOut()
                    controller.edit { clearSpotifyStreamingSession() }
                }
            }))
        } else if (signInInProgress) {
            add(SettingsRow.Action("spotify_stream_verifying", Res.string.m6_spotify_stream_verifying, run = {}, enabled = false))
            if (onCancelSignIn != null) {
                add(SettingsRow.Action(
                    "spotify_stream_cancel_sign_in", Res.string.m6_provider_sign_in_cancel,
                    run = { onCancelSignIn.invoke() },
                ))
            }
        } else {
            add(SettingsRow.Action(
                "spotify_stream_sign_in", Res.string.m6_spotify_stream_sign_in, run = onSignIn,
                description = Res.string.m6_spotify_stream_sign_in_desc,
            ))
        }
        add(SettingsRow.Header("spotify_stream_playback", Res.string.m6_integration_spotify_streaming))
        add(SettingsRow.Toggle(
            "spotify_stream_enabled", Res.string.m6_spotify_stream_enabled, SpotifyStreamEnabledKey, false,
            description = if (signedIn) Res.string.m6_spotify_stream_enabled_desc else Res.string.m6_spotify_stream_needs_account,
            enabled = signedIn || preferences[SpotifyStreamEnabledKey, false],
            read = { it.spotifyStreamingEnabledForSettings() },
            write = { setSpotifyStreamingEnabled(it) },
        ))
        add(SettingsRow.Choice(
            "spotify_stream_method", Res.string.m6_spotify_stream_method,
            SpotifyStreamResolveMethodKey, SpotifyStreamResolveMethod.Default.name, resolveMethodOptions,
            write = { setSpotifyStreamResolveMethod(SpotifyStreamResolveMethod.fromPreference(it)) },
        ))
        add(SettingsRow.Status(
            "spotify_stream_method_note", Res.string.m6_spotify_stream_method_note_title,
            stringResource(Res.string.m6_spotify_stream_method_note), fullText = true,
        ))
    }
    SettingsPage(
        rows = rows,
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        resumed = resumed,
        motionEnabled = motionEnabled,
        onFailure = onFailure,
        modifier = modifier,
    )
    signInRefusal?.let { refusal ->
        AlertDialog(
            onDismissRequest = onDismissSignInRefusal,
            title = { Text(stringResource(Res.string.m6_spotify_stream_sign_in_failed)) },
            text = { Text(stringResource(refusal.messageResource())) },
            confirmButton = { TextButton(onClick = onDismissSignInRefusal) { Text(stringResource(Res.string.m6_done)) } },
        )
    }
}

private val resolveMethodOptions = listOf(
    SettingsRow.Option(SpotifyStreamResolveMethod.STOPIFY.name, Res.string.m6_spotify_stream_method_stopify),
    SettingsRow.Option(SpotifyStreamResolveMethod.ECHO.name, Res.string.m6_spotify_stream_method_echo),
)

private fun SpotifySignInRefusal.messageResource(): StringResource = when (this) {
    SpotifySignInRefusal.SAME_AS_CATALOG -> Res.string.m6_spotify_stream_error_same_account
    SpotifySignInRefusal.CATALOG_UNVERIFIABLE -> Res.string.m6_spotify_stream_error_catalog_unverifiable
    SpotifySignInRefusal.INVALID_SESSION -> Res.string.m6_spotify_stream_error_session
    SpotifySignInRefusal.PROFILE_UNAVAILABLE -> Res.string.m6_spotify_stream_error_profile
}
