package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Route the host's Qobuz web sign-in answers to. The host owns that page; this module only links to it. */
const val QobuzLoginRoute = "settings/qobuz/login"

/**
 * Qobuz accounts and proxy instances, ported from the baseline page. Accounts are direct API credentials. The host runs
 * their test through [checkTokens] and the instance test through [checkInstances], and both write into host-owned health
 * maps, so results survive leaving the page as the baseline cache did. Credentials stay masked, and the page never copies
 * them. Removing an account or instance forgets its health result. Reset clears all stored entries in one edit.
 */
@Composable
fun QobuzSettings(
    controller: SettingsController,
    tokenHealth: Map<String, SourceHealth>,
    instanceHealth: Map<String, SourceHealth>,
    checkTokens: suspend (tokens: List<QobuzTokenEntry>, probeTrack: String, quality: String) -> Unit,
    checkInstances: suspend (instances: List<String>, probeTrack: String, quality: String) -> Unit,
    forgetTokenHealth: (Set<String>) -> Unit,
    forgetInstanceHealth: (Set<String>) -> Unit,
    copyToClipboard: (String) -> Unit,
    navigate: (String) -> Unit,
    onNotice: (SettingsNotice) -> Unit,
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
    fun commit(change: PreferenceEditor.() -> Unit) {
        scope.launch {
            try { controller.edit(change) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
        }
    }

    var tokenManagementOpen by remember { mutableStateOf(false) }
    var instanceManagementOpen by remember { mutableStateOf(false) }
    var addTokensOpen by remember { mutableStateOf(false) }
    var addInstanceOpen by remember { mutableStateOf(false) }
    var bulkInstanceOpen by remember { mutableStateOf(false) }
    var detailTokenId by remember { mutableStateOf<String?>(null) }
    var detailInstance by remember { mutableStateOf<String?>(null) }
    var testingTokens by remember { mutableStateOf(false) }
    var testingInstances by remember { mutableStateOf(false) }

    val qobuzEnabled = preferences[QobuzEnabledKey, false]
    val quality = preferences[QobuzAudioQualityKey, "FLAC"]
    val probeTrack = preferences[QobuzLastProbeTrackKey, ""]
    val tokens = preferences.qobuzTokens()
    val instances = preferences.qobuzInstanceList()
    val tokenSummary = stringResource(
        Res.string.m6_qobuz_health_summary,
        tokens.size,
        tokens.count { tokenHealth[it.id]?.status == SourceHealthStatus.HEALTHY },
        tokens.count { tokenHealth[it.id]?.status == SourceHealthStatus.PREVIEW_ONLY },
        tokens.count { tokenHealth[it.id]?.status == SourceHealthStatus.UNREACHABLE },
    )
    val instanceSummary = stringResource(
        Res.string.m6_qobuz_health_summary,
        instances.size,
        instances.count { instanceHealth[it]?.status == SourceHealthStatus.HEALTHY },
        instances.count { instanceHealth[it]?.status == SourceHealthStatus.PREVIEW_ONLY },
        instances.count { instanceHealth[it]?.status == SourceHealthStatus.UNREACHABLE },
    )
    val defaultAccount = stringResource(Res.string.m6_qobuz_default_account)
    val tokenRowValues = HashMap<String, String>()
    for (entry in tokens) {
        val name = entry.label.ifBlank { entry.userId.ifBlank { defaultAccount } }
        val statusText = healthLabel(tokenHealth[entry.id], Res.string.m6_qobuz_token_status_unknown)
        tokenRowValues[entry.id] = stringResource(Res.string.m6_qobuz_account_row, name, entry.id) + "\n" + statusText
    }
    val instanceRowValues = HashMap<String, String>()
    for (url in instances) {
        instanceRowValues[url] = healthLabel(instanceHealth[url], Res.string.m6_qobuz_instance_unknown)
    }

    fun removeInstances(urls: Set<String>) {
        commit { setQobuzInstances(instances.filterNot { it in urls }) }
        forgetInstanceHealth(urls)
    }

    fun removeInstancesWithStatus(status: SourceHealthStatus) {
        val doomed = instances.filter { instanceHealth[it]?.status == status }.toSet()
        if (doomed.isEmpty()) {
            onNotice(SettingsNotice.Message(Res.string.m6_qobuz_nothing_to_do))
        } else {
            removeInstances(doomed)
            onNotice(SettingsNotice.Message(Res.string.m6_qobuz_removed, doomed.size))
        }
    }

    fun copyOnlineInstances() {
        val online = instances.filter { instanceHealth[it]?.status == SourceHealthStatus.HEALTHY }
        if (online.isEmpty()) {
            onNotice(SettingsNotice.Message(Res.string.m6_qobuz_nothing_to_do))
        } else {
            copyToClipboard(online.joinToString("\n"))
        }
    }

    // Manual, on-demand probes only. The host runs them when the user taps a test row, never at startup.
    fun runTokenCheck() {
        if (testingTokens || tokens.isEmpty()) return
        testingTokens = true
        val entries = tokens
        scope.launch {
            try { checkTokens(entries, probeTrack, quality) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
            finally { testingTokens = false }
        }
    }

    fun runInstanceCheck() {
        if (testingInstances || instances.isEmpty()) return
        testingInstances = true
        val urls = instances
        scope.launch {
            try { checkInstances(urls, probeTrack, quality) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
            finally { testingInstances = false }
        }
    }

    val rows = buildList<SettingsRow> {
        add(SettingsRow.Header("qobuz_account", Res.string.m6_qobuz_title))
        add(SettingsRow.Toggle(
            "qobuz_enable", Res.string.m6_qobuz_enable, QobuzEnabledKey, false,
            description = Res.string.m6_qobuz_enable_description,
        ))
        add(SettingsRow.Choice(
            "qobuz_audio_quality", Res.string.m6_qobuz_audio_quality, QobuzAudioQualityKey, "FLAC",
            listOf(
                SettingsRow.Option("FLAC", Res.string.m6_qobuz_quality_flac),
                SettingsRow.Option("HI_RES", Res.string.m6_qobuz_quality_hires),
                SettingsRow.Option("MAX", Res.string.m6_qobuz_quality_max),
            ),
            enabled = qobuzEnabled,
        ))

        add(SettingsRow.Header("qobuz_tokens", Res.string.m6_qobuz_accounts_group))
        add(SettingsRow.Action("qobuz_login_web", Res.string.m6_qobuz_login_web, run = { navigate(QobuzLoginRoute) }))
        if (tokenManagementOpen) {
            add(SettingsRow.Action("qobuz_add_tokens", Res.string.m6_qobuz_add_tokens, run = { addTokensOpen = true }))
        }
        if (tokens.isNotEmpty()) {
            add(SettingsRow.Action(
                "qobuz_check_tokens",
                if (testingTokens) Res.string.m6_qobuz_checking_tokens else Res.string.m6_qobuz_check_tokens,
                run = { runTokenCheck() },
                enabled = !testingTokens,
            ))
        }
        add(SettingsRow.Status(
            "qobuz_manage_accounts", Res.string.m6_qobuz_manage_accounts, tokenSummary,
            run = { tokenManagementOpen = !tokenManagementOpen }, fullText = true,
        ))
        if (tokenManagementOpen) {
            tokens.forEach { entry ->
                add(SettingsRow.Status(
                    "qobuz_token:${entry.id}", Res.string.m6_qobuz_account_row_title,
                    tokenRowValues[entry.id].orEmpty(),
                    run = { detailTokenId = entry.id }, fullText = true,
                ))
            }
            if (tokens.isNotEmpty()) {
                add(SettingsRow.Action("qobuz_reset_tokens", Res.string.m6_qobuz_reset_tokens, run = {
                    forgetTokenHealth(tokenHealth.keys.toSet())
                    commit { clearQobuzTokens() }
                }))
            }
        }

        add(SettingsRow.Header("qobuz_instances", Res.string.m6_qobuz_instances_group))
        add(SettingsRow.Action(
            "qobuz_check_instances",
            if (testingInstances) Res.string.m6_qobuz_checking_instances else Res.string.m6_qobuz_check_instances,
            run = { runInstanceCheck() },
            enabled = !testingInstances && instances.isNotEmpty(),
        ))
        add(SettingsRow.Status(
            "source_manage_instances", Res.string.m6_qobuz_manage_instances, instanceSummary,
            run = { instanceManagementOpen = !instanceManagementOpen }, fullText = true,
        ))
        if (instanceManagementOpen) {
            instances.forEach { url ->
                add(SettingsRow.Status(
                    "qobuz_instance:$url", Res.string.m6_qobuz_instance_row_title,
                    "$url\n${instanceRowValues[url].orEmpty()}",
                    run = { detailInstance = url }, fullText = true,
                ))
            }
            add(SettingsRow.Action("qobuz_add_instance", Res.string.m6_qobuz_add_instance, run = { addInstanceOpen = true }))
            add(SettingsRow.Action(
                "source_bulk_add", Res.string.m6_qobuz_bulk_add, run = { bulkInstanceOpen = true },
                description = Res.string.m6_qobuz_bulk_hint,
            ))
            if (instances.isNotEmpty()) {
                add(SettingsRow.Action("source_copy_online", Res.string.m6_qobuz_copy_online, run = { copyOnlineInstances() }))
                add(SettingsRow.Action("source_remove_dead", Res.string.m6_qobuz_remove_dead, run = {
                    removeInstancesWithStatus(SourceHealthStatus.UNREACHABLE)
                }))
                add(SettingsRow.Action("source_remove_deprecated", Res.string.m6_qobuz_remove_deprecated, run = {
                    removeInstancesWithStatus(SourceHealthStatus.PREVIEW_ONLY)
                }))
            }
            add(SettingsRow.Action("qobuz_reset_instances", Res.string.m6_qobuz_reset_instances, run = {
                forgetInstanceHealth(instanceHealth.keys.toSet())
                commit { setQobuzInstances(emptyList()) }
            }))
        }
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

    val detailToken = detailTokenId?.let { id -> tokens.firstOrNull { it.id == id } }
    if (detailToken != null) {
        QobuzTokenDialog(
            entry = detailToken,
            statusLabel = healthLabel(tokenHealth[detailToken.id], Res.string.m6_qobuz_token_status_unknown),
            deprecated = tokenHealth[detailToken.id]?.status == SourceHealthStatus.PREVIEW_ONLY,
            onSave = { updated ->
                commit { replaceQobuzToken(detailToken.id, updated) }
                if (updated.id != detailToken.id) forgetTokenHealth(setOf(detailToken.id))
                detailTokenId = null
            },
            onRemove = {
                commit { replaceQobuzToken(detailToken.id, null) }
                forgetTokenHealth(setOf(detailToken.id))
                detailTokenId = null
            },
            onCopy = {
                // The baseline copies the stored token, not an unsaved edit, and closes the details.
                copyToClipboard(detailToken.token)
                detailTokenId = null
            },
            onDismiss = { detailTokenId = null },
        )
    }
    detailInstance?.let { url ->
        QobuzInstanceDialog(
            url = url,
            statusLabel = healthLabel(instanceHealth[url], Res.string.m6_qobuz_instance_unknown),
            onCopy = {
                copyToClipboard(url)
                detailInstance = null
            },
            onRemove = {
                removeInstances(setOf(url))
                detailInstance = null
            },
            onDismiss = { detailInstance = null },
        )
    }
    if (addTokensOpen) {
        SettingsTextEntryDialog(
            title = Res.string.m6_qobuz_add_tokens,
            placeholder = Res.string.m6_qobuz_add_tokens_hint,
            multiline = true,
            masked = false,
            description = null,
            isValid = { it.isNotBlank() },
            onConfirm = { raw ->
                val parsed = parseQobuzTokenBlocks(raw)
                if (parsed.isEmpty()) {
                    onNotice(SettingsNotice.Message(Res.string.m6_qobuz_tokens_none_parsed))
                } else {
                    commit { addQobuzTokens(parsed) }
                    onNotice(SettingsNotice.Message(Res.string.m6_qobuz_tokens_parsed, parsed.size))
                }
            },
            onDismiss = { addTokensOpen = false },
        )
    }
    if (addInstanceOpen) {
        SettingsTextEntryDialog(
            title = Res.string.m6_qobuz_add_instance,
            placeholder = Res.string.m6_qobuz_instance_url_hint,
            multiline = false,
            masked = false,
            description = null,
            isValid = { normalizeSourceInstanceUrl(it) != null },
            onConfirm = { raw ->
                val normalized = normalizeSourceInstanceUrl(raw)
                when {
                    normalized == null -> onNotice(SettingsNotice.Message(Res.string.m6_qobuz_instance_invalid_url))
                    normalized in instances -> onNotice(SettingsNotice.Message(Res.string.m6_qobuz_instance_duplicate))
                    else -> commit { setQobuzInstances(instances + normalized) }
                }
            },
            onDismiss = { addInstanceOpen = false },
        )
    }
    if (bulkInstanceOpen) {
        SettingsTextEntryDialog(
            title = Res.string.m6_qobuz_bulk_add,
            placeholder = Res.string.m6_qobuz_bulk_hint,
            multiline = true,
            masked = false,
            description = null,
            isValid = { it.isNotBlank() },
            onConfirm = { raw ->
                val added = parseSourceInstanceUrls(raw)
                    .mapNotNull(::normalizeSourceInstanceUrl)
                    .filterNot { it in instances }
                if (added.isEmpty()) {
                    onNotice(SettingsNotice.Message(Res.string.m6_qobuz_bulk_none))
                } else {
                    commit { setQobuzInstances(instances + added) }
                    onNotice(SettingsNotice.Message(Res.string.m6_qobuz_bulk_added, added.size))
                }
            },
            onDismiss = { bulkInstanceOpen = false },
        )
    }
}

/** Status text for one probe result. Untested entries use [unknown], and the page never probes on its own. */
@Composable
private fun healthLabel(health: SourceHealth?, unknown: StringResource): String {
    if (health == null) return stringResource(unknown)
    val latency = (health.latencyMs ?: 0L).toInt()
    return when (health.status) {
        SourceHealthStatus.HEALTHY -> stringResource(Res.string.m6_qobuz_probe_online, latency)
        SourceHealthStatus.PREVIEW_ONLY -> stringResource(Res.string.m6_qobuz_probe_deprecated, latency)
        SourceHealthStatus.UNREACHABLE -> stringResource(Res.string.m6_qobuz_probe_failed)
    }
}

/**
 * Account editor. Confirm saves only a complete entry, and the removal action sits beside it. The token and both secrets
 * are masked until revealed. A deprecated probe result explains why lossless playback is limited.
 */
@Composable
private fun QobuzTokenDialog(
    entry: QobuzTokenEntry,
    statusLabel: String,
    deprecated: Boolean,
    onSave: (QobuzTokenEntry) -> Unit,
    onRemove: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember(entry) { mutableStateOf(entry.label) }
    var userId by remember(entry) { mutableStateOf(entry.userId) }
    var token by remember(entry) { mutableStateOf(entry.token) }
    var appId by remember(entry) { mutableStateOf(entry.appId) }
    var appSecret by remember(entry) { mutableStateOf(entry.appSecret) }
    var revealToken by remember(entry) { mutableStateOf(false) }
    var revealAppId by remember(entry) { mutableStateOf(false) }
    var revealAppSecret by remember(entry) { mutableStateOf(false) }
    val edited = entry.copy(
        label = label.trim(),
        userId = userId.trim(),
        token = token.trim(),
        appId = appId.trim(),
        appSecret = appSecret.trim(),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.m6_qobuz_details)) },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(Res.string.m6_qobuz_field_account)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = userId,
                    onValueChange = { userId = it },
                    label = { Text(stringResource(Res.string.m6_qobuz_field_user_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                QobuzSecretField(
                    value = token,
                    onValueChange = { token = it },
                    label = stringResource(Res.string.m6_qobuz_field_token),
                    revealed = revealToken,
                    onToggleReveal = { revealToken = !revealToken },
                )
                QobuzSecretField(
                    value = appId,
                    onValueChange = { appId = it },
                    label = stringResource(Res.string.m6_qobuz_field_app_id),
                    revealed = revealAppId,
                    onToggleReveal = { revealAppId = !revealAppId },
                )
                QobuzSecretField(
                    value = appSecret,
                    onValueChange = { appSecret = it },
                    label = stringResource(Res.string.m6_qobuz_field_app_secret),
                    revealed = revealAppSecret,
                    onToggleReveal = { revealAppSecret = !revealAppSecret },
                )
                SettingsDetailField(stringResource(Res.string.m6_qobuz_field_status), statusLabel)
                SettingsDetailField(
                    stringResource(Res.string.m6_qobuz_field_subscription),
                    entry.subscription.ifBlank { stringResource(Res.string.m6_qobuz_token_status_unknown) },
                )
                if (deprecated) {
                    SettingsDetailField(
                        stringResource(Res.string.m6_qobuz_deprecated_title),
                        stringResource(Res.string.m6_qobuz_deprecated_info),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = edited.isComplete(), onClick = { onSave(edited) }) {
                Text(stringResource(Res.string.m6_done))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRemove) { Text(stringResource(Res.string.m6_qobuz_remove_account)) }
                TextButton(onClick = onCopy) { Text(stringResource(Res.string.m6_qobuz_copy_token)) }
            }
        },
    )
}

@Composable
private fun QobuzInstanceDialog(
    url: String,
    statusLabel: String,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.m6_qobuz_details)) },
        text = {
            Column {
                SettingsDetailField(stringResource(Res.string.m6_qobuz_field_instance_url), url)
                SettingsDetailField(stringResource(Res.string.m6_qobuz_field_status), statusLabel)
                TextButton(onClick = onRemove) { Text(stringResource(Res.string.m6_qobuz_remove_instance)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6_qobuz_close)) } },
        dismissButton = { TextButton(onClick = onCopy) { Text(stringResource(Res.string.m6_qobuz_copy_url)) } },
    )
}

/** Masked credential field with a text reveal toggle. Each field reveals independently. */
@Composable
private fun QobuzSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = onToggleReveal) {
                Text(stringResource(if (revealed) Res.string.m6_qobuz_hide else Res.string.m6_qobuz_show))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
