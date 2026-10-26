package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** What the host's QR sign-in is doing. The host runs the poll loop and reports only these states. */
sealed interface QqLoginUiState {
    /** A sign-in code is being requested. */
    data object Requesting : QqLoginUiState

    /** A code is on screen and waiting to be scanned. The host decodes the code image. */
    data class Ready(val code: ImageBitmap) : QqLoginUiState

    /** The code was scanned and the sign-in is being confirmed on the phone. */
    data object Scanned : QqLoginUiState

    /** The attempt ended; a new code can be requested with the retry action. */
    data class Failed(val reason: QqLoginFailure) : QqLoginUiState
}

/**
 * Why a sign-in attempt ended. The page localizes each reason. The service's own wording is never shown, because it is
 * not translated and can carry account detail.
 */
enum class QqLoginFailure { UNAVAILABLE, TIMED_OUT, DECLINED, INCOMPLETE }

/**
 * QQ Music settings: the account card (QR sign-in, or the signed-in account), the enabled switch and the preferred tier.
 *
 * The host runs the QR sign-in and reports its progress through [loginState]. [onStartSignIn] must return at once and
 * begin or restart an attempt. The page asks for one when the sign-in card appears and again on retry. [onCancelSignIn]
 * stops the attempt when the card leaves composition. The host begins its attempt with [accounts] before it polls and
 * commits a finished sign-in with [ProviderAccountLifecycle.commitQqMusic]. [onSignOut] clears the host's in-memory
 * session first, and [accounts] then signs the account out.
 */
@Composable
fun QqMusicSettings(
    controller: SettingsController,
    accounts: ProviderAccountLifecycle,
    loginState: QqLoginUiState,
    onStartSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: suspend () -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    resumed: Boolean = true,
    motionEnabled: Boolean = true,
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
    // The attempt belongs to the card: it starts when the card appears and stops when it leaves, so a finished or
    // abandoned sign-in never keeps polling in the background.
    if (!preferences.qqMusicSignedIn()) {
        DisposableEffect(Unit) {
            onStartSignIn()
            onDispose { onCancelSignIn() }
        }
    }
    val rows: List<SettingsRow> = listOf(
        SettingsRow.Header("qq_music_group", Res.string.m6_source_qq),
        SettingsRow.Toggle(
            "qq_music_enabled", Res.string.m6_qq_enabled, QqMusicEnabledKey, false,
            description = Res.string.m6_qq_enabled_desc,
        ),
        SettingsRow.Choice(
            "qq_music_quality", Res.string.m6_qq_quality,
            QqMusicAudioQualityKey, QqAudioQuality.Default.name, qualityOptions,
            write = { setQqMusicAudioQuality(QqAudioQuality.fromPreference(it)) },
        ),
    )
    SettingsPage(
        rows = rows,
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        resumed = resumed,
        motionEnabled = motionEnabled,
        onFailure = onFailure,
        modifier = modifier,
        leadingItems = {
            item(key = "qq_music_account") {
                QqAccountCard(
                    signedInAs = preferences.qqMusicAccountLabel(),
                    loginState = loginState,
                    onRetry = onStartSignIn,
                    onSignOut = {
                        hostAction {
                            onSignOut()
                            accounts.signOut(ProviderAccountLifecycle.QQ)
                        }
                    },
                    modifier = Modifier
                        .testTag("setting:qq_music_account")
                        .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
                )
            }
        },
        leadingItemCount = 1,
    )
}

/** The account card: the signed-in account and the way out, or the QR sign-in while no account is connected. */
@Composable
private fun QqAccountCard(
    signedInAs: String?,
    loginState: QqLoginUiState,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (signedInAs != null) {
                Text(
                    text = stringResource(Res.string.m6_qq_signed_in, signedInAs),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.m6_qq_signed_in_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(4.dp))
                TextButton(onClick = onSignOut) { Text(stringResource(Res.string.m6_qq_sign_out)) }
            } else {
                QqLoginContent(loginState = loginState, onRetry = onRetry)
                Text(
                    text = stringResource(Res.string.m6_qq_login_limits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The body of the sign-in card for each host-reported state. A failure offers a new code, never a silent retry. */
@Composable
private fun ColumnScope.QqLoginContent(loginState: QqLoginUiState, onRetry: () -> Unit) {
    when (loginState) {
        QqLoginUiState.Requesting -> Text(
            text = stringResource(Res.string.m6_qq_login_requesting),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        is QqLoginUiState.Ready -> {
            Text(
                text = stringResource(Res.string.m6_qq_login_scan),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.size(10.dp))
            Image(
                bitmap = loginState.code,
                contentDescription = stringResource(Res.string.m6_qq_login_scan),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
            )
        }

        QqLoginUiState.Scanned -> Text(
            text = stringResource(Res.string.m6_qq_login_scanned),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        is QqLoginUiState.Failed -> {
            Text(
                text = stringResource(loginState.reason.messageResource()),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.size(4.dp))
            TextButton(onClick = onRetry) { Text(stringResource(Res.string.m6_qq_login_retry)) }
        }
    }
}

private fun QqLoginFailure.messageResource(): StringResource = when (this) {
    QqLoginFailure.UNAVAILABLE -> Res.string.m6_qq_login_unavailable
    QqLoginFailure.TIMED_OUT -> Res.string.m6_qq_login_timed_out
    QqLoginFailure.DECLINED -> Res.string.m6_qq_login_declined
    QqLoginFailure.INCOMPLETE -> Res.string.m6_qq_login_incomplete
}

private val qualityOptions = listOf(
    SettingsRow.Option(QqAudioQuality.LOSSLESS.name, Res.string.m6_qq_quality_lossless),
    SettingsRow.Option(QqAudioQuality.HIGH.name, Res.string.m6_qq_quality_high),
    SettingsRow.Option(QqAudioQuality.STANDARD.name, Res.string.m6_qq_quality_standard),
)
