package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.QobuzEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.QobuzTokenEntry
import moe.rukamori.archivetune.shared.domain.settings.SourceHealthStatus
import moe.rukamori.archivetune.shared.domain.settings.addQobuzTokens
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m6_cancel
import moe.rukamori.archivetune.shared.ui.resources.m6_done
import moe.rukamori.archivetune.shared.ui.resources.m6_qobuz_login_app_id_hint
import moe.rukamori.archivetune.shared.ui.resources.m6_qobuz_login_app_secret_hint
import moe.rukamori.archivetune.shared.ui.resources.m6_qobuz_login_description
import moe.rukamori.archivetune.shared.ui.resources.m6_qobuz_login_manual_secret
import moe.rukamori.archivetune.shared.ui.resources.m6_qobuz_login_no_secret
import moe.rukamori.archivetune.shared.ui.resources.m6_qobuz_login_open_player
import moe.rukamori.archivetune.shared.ui.resources.m6_qobuz_login_searching
import moe.rukamori.archivetune.shared.ui.resources.m6_qobuz_login_start
import moe.rukamori.archivetune.shared.ui.resources.m6_qobuz_login_success
import moe.rukamori.archivetune.shared.ui.resources.m6_qobuz_login_title
import moe.rukamori.archivetune.shared.ui.resources.m6_qobuz_login_token_hint
import org.jetbrains.compose.resources.stringResource

/** The secret input shape the baseline dialog enforced. */
private val AppSecretPattern = Regex("^[a-f0-9]{32}$")

/**
 * The Qobuz web sign-in, ported to hosts that cannot hook a WebView's request headers. The Android
 * screen captured `X-User-Auth-Token`/`X-App-Id` from the player's own API calls and scraped the
 * bundle for app_secret candidates; here the user signs in on the player page and pastes the two
 * values, then the same two things happen in the same order: the live bundle is scraped for
 * ordered candidates and each is verified against the API until one signs. A manual secret is the
 * same fallback the baseline offered when every candidate failed.
 *
 * The token and secret are never echoed back, never written to a log, and only the verified pair
 * is committed — to the Qobuz token list, with the source enabled the way the baseline's save did.
 */
@Composable
fun QobuzLoginScreen(
    controller: SettingsController,
    scrapeCandidates: suspend () -> List<String>?,
    verify: suspend (QobuzTokenEntry) -> SourceHealthStatus,
    openWebPlayer: (() -> Unit)? = null,
    navigateUp: () -> Unit,
    onFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var allRejected by remember { mutableStateOf(false) }
    var manualSecret by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    val rejected = remember { mutableSetOf<String>() }

    fun search(credentials: Pair<String, String>, pool: List<String>) {
        val (userToken, userAppId) = credentials
        val untried = pool.filterNot(rejected::contains)
        if (untried.isEmpty()) {
            allRejected = true
            return
        }
        searching = true
        allRejected = false
        scope.launch {
            val verified = try {
                untried.firstNotNullOfOrNull { candidate ->
                    val attempt = QobuzTokenEntry(
                        token = userToken,
                        appId = userAppId,
                        appSecret = candidate,
                        label = "Web login",
                    )
                    val health = try {
                        verify(attempt)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SourceHealthStatus.UNREACHABLE
                    }
                    if (health != SourceHealthStatus.UNREACHABLE) attempt else null.also { rejected += candidate }
                }
            } finally {
                searching = false
            }
            if (verified != null) {
                try {
                    controller.edit {
                        addQobuzTokens(listOf(verified))
                        // Signing in is an explicit opt-in; leaving the source off would make a
                        // successful login look like it did nothing.
                        this[QobuzEnabledKey] = true
                    }
                    saved = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onFailure()
                }
            } else {
                // Every candidate was rejected — the bundle changed shape or this token cannot
                // sign. Ask for the secret rather than leaving the screen looking idle.
                allRejected = true
            }
        }
    }

    fun start() {
        val userToken = token.trim()
        val userAppId = appId.trim()
        // The baseline's capture hook applied the same guards before it would fire.
        if (userToken.length <= 20 || userAppId.length <= 3) return
        scope.launch {
            val candidates = try {
                scrapeCandidates().orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            search(userToken to userAppId, candidates)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.m6_qobuz_login_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(Res.string.m6_qobuz_login_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (openWebPlayer != null) {
            TextButton(onClick = openWebPlayer) { Text(stringResource(Res.string.m6_qobuz_login_open_player)) }
        }
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            singleLine = true,
            enabled = !searching && !saved,
            placeholder = { Text(stringResource(Res.string.m6_qobuz_login_token_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it },
            singleLine = true,
            enabled = !searching && !saved,
            placeholder = { Text(stringResource(Res.string.m6_qobuz_login_app_id_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            searching -> {
                CircularProgressIndicator()
                Text(stringResource(Res.string.m6_qobuz_login_searching))
            }
            saved -> Button(onClick = navigateUp) { Text(stringResource(Res.string.m6_done)) }
            else -> Button(
                enabled = token.trim().length > 20 && appId.trim().length > 3,
                onClick = ::start,
            ) { Text(stringResource(Res.string.m6_qobuz_login_start)) }
        }
        if (saved) Text(stringResource(Res.string.m6_qobuz_login_success))
        if (allRejected && !saved && !searching) {
            Text(
                stringResource(Res.string.m6_qobuz_login_no_secret),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedTextField(
                value = manualSecret,
                onValueChange = { manualSecret = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.m6_qobuz_login_app_secret_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                enabled = AppSecretPattern.matches(manualSecret.trim()),
                onClick = { search(token.trim() to appId.trim(), listOf(manualSecret.trim())) },
            ) { Text(stringResource(Res.string.m6_qobuz_login_manual_secret)) }
        }
    }
}
