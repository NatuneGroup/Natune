package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.component.AuthWebViewLayout
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Which host-owned provider sign-in a consumer page shows. These are labels only; the host runs the destination. */
enum class ProviderSignInKind { DEEZER_WEB, TIDAL_WEB, QOBUZ_WEB, SPOTIFY_STREAMING, DISCORD, APPLE_MUSIC_WEB }

/**
 * Where a host-owned sign-in stands. The host reports each transition after the matching work has happened:
 * [SignedIn] only after the account is verified and committed, never on a click, a page load or a cookie that has not
 * been checked.
 */
sealed interface ProviderSignInState {
    /** Nothing is running. Start asks the host to open its destination. */
    data object Idle : ProviderSignInState

    /** The host is opening its browser or WebView destination. Cancel stays available. */
    data object Opening : ProviderSignInState

    /** The destination is open. The host reports a captured credential, a failure or a cancel. */
    data object Waiting : ProviderSignInState

    /** The host holds a captured credential and is checking it with the provider. */
    data object Verifying : ProviderSignInState

    /** The host verified and committed the account. [warning] names a follow-up that the user still has to do. */
    data class SignedIn(val accountName: String, val warning: ProviderSignInWarning? = null) : ProviderSignInState

    /** The sign-in ended without a saved account. Nothing was committed, so nothing is shown as connected. */
    data class Failed(val reason: ProviderSignInFailure) : ProviderSignInState
}

/** Why a sign-in ended without an account. The page shows the reported reason and never guesses one. */
enum class ProviderSignInFailure { UNAVAILABLE, DECLINED, REJECTED, TIMED_OUT, UNVERIFIED, NOT_SAVED }

/** A signed-in account that still needs a follow-up step before its source works. */
enum class ProviderSignInWarning { TIDAL_NOT_LOSSLESS, QOBUZ_APP_SECRET_MISSING }

/**
 * True while a host destination is open or a result is pending. The host intercepts the system back gesture in these
 * states and calls its cancel path first, so a late result cannot land on a page the user has already left.
 */
val ProviderSignInState.isInFlight: Boolean
    get() = this == ProviderSignInState.Opening ||
        this == ProviderSignInState.Waiting ||
        this == ProviderSignInState.Verifying

/**
 * Consumer page for a host-owned provider sign-in. It shows what [state] means, offers Start, Cancel and Done, and
 * embeds the host's [destination] (for example a WebView) while the sign-in is open. Browser and PKCE flows pass no
 * destination.
 *
 * [onStart] must return at once; the host opens the destination and reports progress through [state]. [onCancel] asks
 * the host to drop the destination and any captured credential, and the host discards a result that arrives after it.
 * [onDone] leaves the page once the account is committed. This module never reports a sign-in as complete unless the
 * host sets [ProviderSignInState.SignedIn]. Lifecycle, storage and the provider calls stay host-owned.
 */
@Composable
fun ProviderSignInPage(
    kind: ProviderSignInKind,
    state: ProviderSignInState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    destination: (@Composable () -> Unit)? = null,
) {
    val status = providerSignInStatus(state)
    val failed = state is ProviderSignInState.Failed
    val header: @Composable ColumnScope.() -> Unit = {
        Text(
            text = stringResource(kind.titleResource()),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(kind.subtitleResource()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodyLarge,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (state is ProviderSignInState.SignedIn && state.warning != null) {
            Text(
                text = stringResource(state.warning.messageResource()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            ProviderSignInState.Idle -> Button(onClick = onStart) {
                Text(stringResource(Res.string.m6_provider_sign_in_start))
            }
            is ProviderSignInState.Failed -> Button(onClick = onStart) {
                Text(stringResource(Res.string.m6_retry))
            }
            is ProviderSignInState.SignedIn -> Button(onClick = onDone) {
                Text(stringResource(Res.string.m6_done))
            }
            ProviderSignInState.Opening,
            ProviderSignInState.Waiting,
            ProviderSignInState.Verifying,
            -> TextButton(onClick = onCancel) {
                Text(stringResource(Res.string.m6_provider_sign_in_cancel))
            }
        }
    }
    if (destination != null && (state == ProviderSignInState.Opening || state == ProviderSignInState.Waiting)) {
        AuthWebViewLayout(
            modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            header = header,
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                destination()
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = header,
        )
    }
}

@Composable
private fun providerSignInStatus(state: ProviderSignInState): String? = when (state) {
    ProviderSignInState.Idle -> null
    ProviderSignInState.Opening -> stringResource(Res.string.m6_provider_sign_in_opening)
    ProviderSignInState.Waiting -> stringResource(Res.string.m6_provider_sign_in_waiting)
    ProviderSignInState.Verifying -> stringResource(Res.string.m6_provider_sign_in_verifying)
    is ProviderSignInState.SignedIn -> stringResource(Res.string.m6_provider_sign_in_signed_in, state.accountName)
    is ProviderSignInState.Failed -> stringResource(state.reason.messageResource())
}

private fun ProviderSignInKind.titleResource(): StringResource = when (this) {
    ProviderSignInKind.DEEZER_WEB -> Res.string.m6_provider_sign_in_deezer_title
    ProviderSignInKind.TIDAL_WEB -> Res.string.m6_provider_sign_in_tidal_title
    ProviderSignInKind.QOBUZ_WEB -> Res.string.m6_provider_sign_in_qobuz_title
    ProviderSignInKind.SPOTIFY_STREAMING -> Res.string.m6_provider_sign_in_spotify_title
    ProviderSignInKind.DISCORD -> Res.string.m6_provider_sign_in_discord_title
    ProviderSignInKind.APPLE_MUSIC_WEB -> Res.string.m6_provider_sign_in_apple_title
}

private fun ProviderSignInKind.subtitleResource(): StringResource = when (this) {
    ProviderSignInKind.DEEZER_WEB -> Res.string.m6_provider_sign_in_deezer_subtitle
    ProviderSignInKind.TIDAL_WEB -> Res.string.m6_provider_sign_in_tidal_subtitle
    ProviderSignInKind.QOBUZ_WEB -> Res.string.m6_provider_sign_in_qobuz_subtitle
    ProviderSignInKind.SPOTIFY_STREAMING -> Res.string.m6_provider_sign_in_spotify_subtitle
    ProviderSignInKind.DISCORD -> Res.string.m6_provider_sign_in_discord_subtitle
    ProviderSignInKind.APPLE_MUSIC_WEB -> Res.string.m6_provider_sign_in_apple_subtitle
}

private fun ProviderSignInFailure.messageResource(): StringResource = when (this) {
    ProviderSignInFailure.UNAVAILABLE -> Res.string.m6_provider_sign_in_failed_unavailable
    ProviderSignInFailure.DECLINED -> Res.string.m6_provider_sign_in_failed_declined
    ProviderSignInFailure.REJECTED -> Res.string.m6_provider_sign_in_failed_rejected
    ProviderSignInFailure.TIMED_OUT -> Res.string.m6_provider_sign_in_failed_timed_out
    ProviderSignInFailure.UNVERIFIED -> Res.string.m6_provider_sign_in_failed_unverified
    ProviderSignInFailure.NOT_SAVED -> Res.string.m6_provider_sign_in_failed_not_saved
}

private fun ProviderSignInWarning.messageResource(): StringResource = when (this) {
    ProviderSignInWarning.TIDAL_NOT_LOSSLESS -> Res.string.m6_provider_sign_in_warning_tidal
    ProviderSignInWarning.QOBUZ_APP_SECRET_MISSING -> Res.string.m6_provider_sign_in_warning_qobuz_secret
}
