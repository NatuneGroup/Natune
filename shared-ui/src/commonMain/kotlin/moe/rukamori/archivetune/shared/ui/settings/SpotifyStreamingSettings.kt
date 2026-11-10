package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.SpotifyPremiumPolicy
import moe.rukamori.archivetune.shared.domain.source.SpotifyProduct
import moe.rukamori.archivetune.shared.domain.source.SpotifySignInRefusal
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamAccountBinding
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamEngine
import moe.rukamori.archivetune.shared.domain.source.streamAccountBinding
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
    librespotSignInAvailable: Boolean = false,
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
    // The account section manages the session for the stored engine's binding: the dedicated
    // streaming sign-in for Unofficial, the catalog-bound Librespot grant for Librespot.
    val storedEngine = preferences.spotifyStreamEngine()
    val binding = storedEngine.streamAccountBinding()
    val sessionPresent = preferences.spotifyStreamSessionPresent(storedEngine)
    val signedIn = sessionPresent
    val accountLabel = when (binding) {
        SpotifyStreamAccountBinding.LIBRARY ->
            preferences[SpotifyLibrespotAccountNameKey]?.takeIf { it.isNotBlank() }
                ?: preferences[SpotifyLibrespotUserIdKey]?.takeIf { it.isNotBlank() }
                ?: "?"
        SpotifyStreamAccountBinding.DEDICATED ->
            preferences[SpotifyStreamAccountNameKey]?.takeIf { it.isNotBlank() }
                ?: preferences[SpotifyStreamUserIdKey]?.takeIf { it.isNotBlank() }
                ?: "?"
    }
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
                    controller.edit {
                        if (binding == SpotifyStreamAccountBinding.LIBRARY) {
                            clearSpotifyLibrespotSession()
                        } else {
                            clearSpotifyStreamingSession()
                        }
                    }
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
                // Librespot signs the catalog account in through the browser OAuth flow; the row
                // needs a host that implements it and a catalog session to bind against.
                enabled = binding == SpotifyStreamAccountBinding.DEDICATED ||
                    (librespotSignInAvailable && preferences.spotifyLibrarySignedIn()),
            ))
        }
        add(SettingsRow.Header("spotify_stream_playback", Res.string.m6_integration_spotify_streaming))
        // The enable switch lives on Sources with the other source switches, next to the row that opens this page.
        add(SettingsRow.Choice(
            "spotify_stream_engine", Res.string.m6_spotify_stream_engine,
            SpotifyStreamEngineKey, SpotifyStreamEngine.Default.name, streamEngineOptions(preferences),
            write = { setSpotifyStreamEngine(SpotifyStreamEngine.fromPreference(it)) },
        ))
        add(SettingsRow.Status(
            "spotify_stream_binding", Res.string.m6_spotify_stream_binding,
            stringResource(
                if (binding == SpotifyStreamAccountBinding.LIBRARY) {
                    Res.string.m6_spotify_stream_binding_library
                } else {
                    Res.string.m6_spotify_stream_binding_dedicated
                },
            ),
        ))
        add(SettingsRow.Status(
            "spotify_stream_plan", Res.string.m6_spotify_stream_plan,
            stringResource(planResource(preferences.spotifyStreamAccountProduct(storedEngine))),
        ))
        if (storedEngine == SpotifyStreamEngine.LIBRESPOT &&
            preferences.spotifyStreamEngineEffective() != storedEngine
        ) {
            add(SettingsRow.Status(
                "spotify_stream_engine_degraded", Res.string.m6_spotify_stream_engine_degraded_title,
                stringResource(Res.string.m6_spotify_stream_engine_degraded), fullText = true,
            ))
        }
        add(SettingsRow.Choice(
            "spotify_stream_method", Res.string.m6_spotify_stream_method,
            SpotifyStreamResolveMethodKey, SpotifyStreamResolveMethod.Default.name, resolveMethodOptions,
            enabled = preferences.spotifyStreamEngineEffective() == SpotifyStreamEngine.UNOFFICIAL,
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

private fun streamEngineOptions(preferences: PreferenceSnapshot): List<SettingsRow.Option> {
    val librespotAllowed = preferences.spotifyLibrarySignedIn() &&
        SpotifyPremiumPolicy.librespotAllowed(preferences.spotifyLibraryProduct())
    return listOf(
        SettingsRow.Option(
            SpotifyStreamEngine.UNOFFICIAL.name, Res.string.m6_spotify_stream_engine_unofficial,
            description = Res.string.m6_spotify_stream_engine_unofficial_desc,
        ),
        SettingsRow.Option(
            SpotifyStreamEngine.LIBRESPOT.name, Res.string.m6_spotify_stream_engine_librespot,
            enabled = librespotAllowed,
            description = if (librespotAllowed) {
                Res.string.m6_spotify_stream_engine_librespot_desc
            } else {
                Res.string.m6_spotify_stream_engine_librespot_needs_premium
            },
        ),
    )
}

private fun planResource(product: SpotifyProduct): StringResource = when (product) {
    SpotifyProduct.PREMIUM -> Res.string.m6_spotify_stream_plan_premium
    SpotifyProduct.FREE -> Res.string.m6_spotify_stream_plan_free
    SpotifyProduct.UNKNOWN -> Res.string.m6_spotify_stream_plan_unknown
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
    SpotifySignInRefusal.DIFFERENT_FROM_CATALOG -> Res.string.m6_spotify_stream_error_different_account
    SpotifySignInRefusal.NOT_PREMIUM -> Res.string.m6_spotify_stream_error_not_premium
}
