package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Amazon Music settings for the instance-based source: quality, the user's instance list, pool status, Turnstile
 * authorization and the operator bypass token. [onAuthorize] starts the host's Turnstile WebView, which commits its
 * result through [commitAmazonTurnstileJwt]. [poolInstanceCount] is the host's count of pooled instances after the
 * pool toggle. [currentTimeMillis] and [formatExpiry] come from the host, so this module owns no clock or locale.
 * Authorize stays tappable without an instance; [onNotice] then reports [SettingsNotice.AmazonInstanceRequired], as
 * the baseline toast did, and nothing is launched.
 */
@Composable
fun AmazonSettings(
    controller: SettingsController,
    poolInstanceCount: Int,
    currentTimeMillis: () -> Long,
    formatExpiry: (Long) -> String,
    onAuthorize: () -> Unit,
    onFailure: () -> Unit,
    onNotice: (SettingsNotice) -> Unit,
    scrollTo: String? = null,
    resumed: Boolean = true,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()
    fun commit(change: PreferenceEditor.() -> Unit) {
        scope.launch {
            try { controller.edit(change) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
        }
    }
    var instancesDialogOpen by remember { mutableStateOf(false) }
    var bypassDialogOpen by remember { mutableStateOf(false) }
    val instanceCount = preferences.amazonInstanceList().size
    val jwt = preferences[AmazonTurnstileJwtKey, ""]
    val jwtExpiry = preferences[AmazonTurnstileJwtExpiryMsKey, 0L]
    val bypass = preferences[AmazonBypassTokenKey, ""]
    val sourceEnabled = preferences[AmazonEnabledKey, false]
    val instancesValue =
        if (instanceCount > 0) stringResource(Res.string.m6_amazon_instances_count, instanceCount)
        else stringResource(Res.string.m6_amazon_instances_empty)
    val poolValue =
        if (poolInstanceCount > 0) stringResource(Res.string.m6_amazon_pool_instances_count, poolInstanceCount)
        else stringResource(Res.string.m6_amazon_pool_instances_empty)
    val jwtValue = when (amazonJwtStatus(jwt, jwtExpiry, currentTimeMillis())) {
        AmazonJwtStatus.NEVER -> stringResource(Res.string.m6_amazon_jwt_status_never)
        AmazonJwtStatus.EXPIRED -> stringResource(Res.string.m6_amazon_jwt_status_expired)
        AmazonJwtStatus.VALID_UNTIL ->
            stringResource(Res.string.m6_amazon_jwt_status_valid_until, formatExpiry(jwtExpiry))
    }
    val bypassValue =
        if (bypass.isBlank()) stringResource(Res.string.m6_amazon_bypass_token_description)
        else stringResource(Res.string.m6_amazon_bypass_token_set, bypass.takeLast(4))
    val rows = buildList<SettingsRow> {
        add(SettingsRow.Header("amazon_source", Res.string.m6_source_amazon))
        // The source switch lives on Sources; this page owns the quality, the instances and the authorization.
        add(SettingsRow.Choice(
            "amazon_audio_quality", Res.string.m6_amazon_audio_quality, AmazonAudioQualityKey,
            AmazonAudioQuality.Default.name, amazonQualityOptions, enabled = sourceEnabled,
        ))
        add(SettingsRow.Header("amazon_instances_group", Res.string.m6_amazon_instances))
        add(SettingsRow.Status(
            "amazon_instances", Res.string.m6_amazon_instances, instancesValue,
            run = { instancesDialogOpen = true },
        ))
        add(SettingsRow.Status("amazon_pool_instances", Res.string.m6_amazon_pool_instances, poolValue))
        add(SettingsRow.Action(
            "amazon_authorize", Res.string.m6_amazon_authorize,
            run = {
                if (instanceCount > 0) onAuthorize() else onNotice(SettingsNotice.AmazonInstanceRequired)
            },
            description = Res.string.m6_amazon_authorize_description,
        ))
        add(SettingsRow.Status("amazon_jwt_status", Res.string.m6_amazon_jwt_status, jwtValue))
        add(SettingsRow.Status(
            "amazon_bypass_token", Res.string.m6_amazon_bypass_token, bypassValue,
            run = { bypassDialogOpen = true }, fullText = true,
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
    if (instancesDialogOpen) {
        AmazonTextDialog(
            title = Res.string.m6_amazon_instances,
            initial = preferences[AmazonInstancesKey, ""],
            placeholder = Res.string.m6_amazon_instances_hint,
            multiline = true,
            keyboardType = KeyboardType.Uri,
            onDone = { raw -> commit { setAmazonInstances(raw) } },
            onDismiss = { instancesDialogOpen = false },
        )
    }
    if (bypassDialogOpen) {
        AmazonTextDialog(
            title = Res.string.m6_amazon_bypass_token,
            initial = bypass,
            placeholder = Res.string.m6_amazon_bypass_token_hint,
            multiline = false,
            keyboardType = KeyboardType.Text,
            onDone = { raw -> commit { setAmazonBypassToken(raw) } },
            onDismiss = { bypassDialogOpen = false },
        )
    }
}

private val amazonQualityOptions = listOf(
    SettingsRow.Option(AmazonAudioQuality.ULTRA_HD.name, Res.string.m6_amazon_quality_ultra_hd),
    SettingsRow.Option(AmazonAudioQuality.HD.name, Res.string.m6_amazon_quality_hd),
    SettingsRow.Option(AmazonAudioQuality.STANDARD.name, Res.string.m6_amazon_quality_standard),
)

/** One text field for the instance list (multiline) or the bypass token (single line). Done saves the raw text. */
@Composable
private fun AmazonTextDialog(
    title: StringResource,
    initial: String,
    placeholder: StringResource,
    multiline: Boolean,
    keyboardType: KeyboardType,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = !multiline,
                minLines = if (multiline) 3 else 1,
                placeholder = { Text(stringResource(placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDone(text)
                onDismiss()
            }) { Text(stringResource(Res.string.m6_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}
