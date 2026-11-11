package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Route the host's Tidal web sign-in answers to. The host owns that page; this module only links to it. */
const val TidalLoginRoute = "settings/tidal/login"

/**
 * Tidal account and HiFi-instance settings. The device sign-in through [deviceLink] is the primary way in; web sign-in is
 * a host route and a pasted refresh token is the fallback. Every way in begins an attempt with [accounts] before TIDAL is
 * asked, and only a session that TIDAL verified is committed, as that account. A pasted refresh token is exchanged by
 * [verifyRefreshToken]. [onSignOut] drops the host's in-memory tokens before [accounts] signs the account out. A null
 * [deviceLink] keeps the device row visible but disabled, because this build has no device client. [openExternal]
 * opens the device page in the browser. Instance health is host state: [checkInstances]
 * probes the configured instances, [instanceHealth] is the latest result, and [forgetInstanceHealth] drops results for
 * instances the user removed. The access token is never displayed or copied by this page.
 */
@Composable
fun TidalSettings(
    controller: SettingsController,
    accounts: ProviderAccountLifecycle,
    deviceLink: TidalDeviceLink?,
    openExternal: (String) -> Unit,
    instanceHealth: Map<String, TidalInstanceHealth>,
    checkInstances: suspend () -> Unit,
    forgetInstanceHealth: (Set<String>) -> Unit,
    verifyRefreshToken: suspend (refreshToken: String) -> TidalSessionFacts?,
    onSignOut: suspend () -> Unit,
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
    fun hostAction(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
        }
    }
    var accountDialogOpen by remember { mutableStateOf(false) }
    var tokenDialogOpen by remember { mutableStateOf(false) }
    var deviceLinkOpen by remember { mutableStateOf(false) }
    var addDialogOpen by remember { mutableStateOf(false) }
    var bulkDialogOpen by remember { mutableStateOf(false) }
    var detailInstance by remember { mutableStateOf<String?>(null) }
    var instanceManagementOpen by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    val signedIn = preferences.tidalSignedIn()
    val needsRelogin = preferences.tidalNeedsRelogin()
    val subscription = preferences.tidalSubscription()
    val accountName = preferences.tidalAccountName()
    val instances = preferences.tidalInstanceList()
    val onlineCount = instances.count { instanceHealth[it]?.status == TidalInstanceStatus.HEALTHY }
    val deprecatedCount = instances.count { instanceHealth[it]?.status == TidalInstanceStatus.PREVIEW_ONLY }
    val failedCount = instances.count { instanceHealth[it]?.status == TidalInstanceStatus.UNREACHABLE }
    val summaryValue = stringResource(
        Res.string.m6_tidal_instances_summary, instances.size, onlineCount, deprecatedCount, failedCount,
    )
    val connectedValue = accountName.ifBlank { stringResource(Res.string.m6_tidal) }
    val subscriptionValue = stringResource(
        when (subscription) {
            TidalSubscriptionStatus.PREMIUM -> Res.string.m6_tidal_subscription_premium
            TidalSubscriptionStatus.FREE -> Res.string.m6_tidal_subscription_free
            TidalSubscriptionStatus.UNKNOWN -> Res.string.m6_tidal_subscription_checking
        },
    )
    val accountStatusValue = stringResource(
        if (needsRelogin) Res.string.m6_tidal_account_status_relogin else Res.string.m6_tidal_account_status_active,
    )
    val instanceLabels = HashMap<String, String>()
    for (url in instances) instanceLabels[url] = instanceStatusLabel(instanceHealth[url])

    fun removeInstances(urls: Set<String>) {
        commit { setTidalInstances(instances.filterNot { it in urls }) }
        forgetInstanceHealth(urls)
    }

    fun removeWithStatus(status: TidalInstanceStatus) {
        val doomed = instances.filter { instanceHealth[it]?.status == status }.toSet()
        if (doomed.isEmpty()) {
            onNotice(SettingsNotice.Message(Res.string.m6_tidal_nothing_to_do))
        } else {
            removeInstances(doomed)
            onNotice(SettingsNotice.Message(Res.string.m6_tidal_removed, doomed.size))
        }
    }

    // Manual, on-demand probe only. The host runs it when the user taps Test instances, never at startup.
    fun runCheck() {
        if (testing) return
        testing = true
        scope.launch {
            try { checkInstances() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
            finally { testing = false }
        }
    }

    val rows = buildList<SettingsRow> {
        add(SettingsRow.Header("tidal_account", Res.string.m6_tidal_account_group))
        add(SettingsRow.Toggle("tidal_account_quality_fallback", Res.string.builtin_tidal_quality_fallback,
            TidalAccountQualityFallbackKey, false))
        if (signedIn) {
            add(SettingsRow.Status(
                "tidal_account_connected", Res.string.m6_tidal_account_connected_label, connectedValue,
                run = { accountDialogOpen = true },
            ))
            add(SettingsRow.Status("tidal_subscription", Res.string.m6_tidal_subscription, subscriptionValue))
            if (needsRelogin) {
                add(SettingsRow.Action(
                    "tidal_reconnect", Res.string.m6_tidal_reconnect,
                    run = { if (deviceLink != null) deviceLinkOpen = true else navigate(TidalLoginRoute) },
                ))
            }
            add(SettingsRow.Action("tidal_disconnect", Res.string.m6_tidal_disconnect, run = {
                hostAction {
                    onSignOut()
                    accounts.signOut(ProviderAccountLifecycle.TIDAL)
                }
            }))
        } else {
            add(SettingsRow.Action(
                "tidal_link_device", Res.string.m6_tidal_link_device, run = { deviceLinkOpen = true },
                description = if (deviceLink != null) Res.string.m6_tidal_link_device_desc
                else Res.string.m6_tidal_link_unavailable,
                enabled = deviceLink != null,
            ))
            add(SettingsRow.Action(
                "tidal_login_web", Res.string.m6_tidal_login_web, run = { navigate(TidalLoginRoute) },
            ))
            add(SettingsRow.Action(
                "tidal_paste_token", Res.string.m6_tidal_paste_token, run = { tokenDialogOpen = true },
                description = Res.string.m6_tidal_paste_token_help,
            ))
        }
        add(SettingsRow.Header("tidal_instances", Res.string.m6_tidal_instances_group))
        add(SettingsRow.Status(
            "source_manage_instances", Res.string.m6_tidal_manage_instances, summaryValue,
            run = { instanceManagementOpen = !instanceManagementOpen }, fullText = true,
        ))
        add(SettingsRow.Action(
            "tidal_check_instances",
            if (testing) Res.string.m6_tidal_checking_instances else Res.string.m6_tidal_check_instances,
            run = { runCheck() }, enabled = !testing,
        ))
        if (instanceManagementOpen) {
            instances.forEach { url ->
                add(SettingsRow.Status(
                    "tidal_instance:$url", Res.string.m6_tidal_instance_row_title,
                    "$url\n${instanceLabels[url].orEmpty()}",
                    run = { detailInstance = url }, fullText = true,
                ))
            }
            add(SettingsRow.Action("tidal_add_instance", Res.string.m6_tidal_add_instance, run = { addDialogOpen = true }))
            add(SettingsRow.Action(
                "source_bulk_add", Res.string.m6_tidal_bulk_add, run = { bulkDialogOpen = true },
                description = Res.string.m6_tidal_bulk_hint,
            ))
            add(SettingsRow.Action("source_copy_online", Res.string.m6_tidal_copy_online, run = {
                val online = instances.filter { instanceHealth[it]?.status == TidalInstanceStatus.HEALTHY }
                if (online.isEmpty()) {
                    onNotice(SettingsNotice.Message(Res.string.m6_tidal_nothing_to_do))
                } else {
                    copyToClipboard(online.joinToString("\n"))
                }
            }))
            add(SettingsRow.Action("source_remove_dead", Res.string.m6_tidal_remove_dead, run = {
                removeWithStatus(TidalInstanceStatus.UNREACHABLE)
            }))
            add(SettingsRow.Action("source_remove_deprecated", Res.string.m6_tidal_remove_deprecated, run = {
                removeWithStatus(TidalInstanceStatus.PREVIEW_ONLY)
            }))
            add(SettingsRow.Action("tidal_reset_instances", Res.string.m6_tidal_reset_instances, run = {
                forgetInstanceHealth(instanceHealth.keys.toSet())
                commit { setTidalInstances(emptyList()) }
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

    if (accountDialogOpen) {
        TidalAccountDialog(
            accountName = accountName,
            userId = preferences.tidalUserId(),
            countryCode = preferences.tidalCountryCode(),
            statusLabel = accountStatusValue,
            subscriptionLabel = subscriptionValue,
            onDismiss = { accountDialogOpen = false },
        )
    }
    detailInstance?.let { url ->
        TidalInstanceDialog(
            url = url,
            statusLabel = instanceLabels[url].orEmpty(),
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
    if (addDialogOpen) {
        TidalTextDialog(
            title = Res.string.m6_tidal_add_instance,
            placeholder = Res.string.m6_tidal_instance_url_hint,
            multiline = false,
            masked = false,
            description = null,
            isValid = { normalizeSourceInstanceUrl(it) != null },
            onConfirm = { raw ->
                val normalized = normalizeSourceInstanceUrl(raw)
                when {
                    normalized == null -> onNotice(SettingsNotice.Message(Res.string.m6_tidal_instance_invalid_url))
                    normalized in instances -> onNotice(SettingsNotice.Message(Res.string.m6_tidal_instance_duplicate))
                    else -> commit { setTidalInstances(instances + normalized) }
                }
            },
            onDismiss = { addDialogOpen = false },
        )
    }
    if (bulkDialogOpen) {
        TidalTextDialog(
            title = Res.string.m6_tidal_bulk_add,
            placeholder = Res.string.m6_tidal_bulk_hint,
            multiline = true,
            masked = false,
            description = null,
            isValid = { it.isNotBlank() },
            onConfirm = { raw ->
                val added = parseSourceInstanceUrls(raw).mapNotNull(::normalizeSourceInstanceUrl)
                    .filterNot { it in instances }
                if (added.isEmpty()) {
                    onNotice(SettingsNotice.Message(Res.string.m6_tidal_bulk_none))
                } else {
                    commit { setTidalInstances(instances + added) }
                    onNotice(SettingsNotice.Message(Res.string.m6_tidal_bulk_added, added.size))
                }
            },
            onDismiss = { bulkDialogOpen = false },
        )
    }
    if (tokenDialogOpen) {
        TidalTextDialog(
            title = Res.string.m6_tidal_token_title,
            placeholder = Res.string.m6_tidal_token_label,
            multiline = false,
            masked = true,
            description = Res.string.m6_tidal_paste_token_help,
            isValid = { it.trim().split('.').size == 3 },
            onConfirm = { raw ->
                val typed = raw.trim()
                scope.launch {
                    try {
                        val attempt = accounts.begin(ProviderAccountLifecycle.TIDAL)
                        val session = verifyRefreshToken(typed)
                        val outcome = session?.let { accounts.commitTidal(attempt, it) }
                        onNotice(SettingsNotice.Message(when (outcome) {
                            ProviderSignInOutcome.Accepted -> Res.string.m6_tidal_token_verified
                            null, ProviderSignInOutcome.Unverified -> Res.string.m6_tidal_token_failed
                            else -> Res.string.m6_tidal_link_not_saved
                        }))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        onFailure()
                    }
                }
            },
            onDismiss = { tokenDialogOpen = false },
        )
    }
    if (deviceLinkOpen && deviceLink != null) {
        TidalDeviceLinkDialog(
            deviceLink = deviceLink,
            accounts = accounts,
            openExternal = openExternal,
            copyToClipboard = copyToClipboard,
            onNotice = onNotice,
            onDismiss = { deviceLinkOpen = false },
        )
    }
}

private sealed interface DeviceLinkState {
    data object Requesting : DeviceLinkState
    class Showing(val authorization: TidalDeviceAuthorization) : DeviceLinkState
    class Ended(val message: StringResource) : DeviceLinkState
}

/**
 * The device sign-in. The attempt begins before the code is requested, the dialog polls at the interval TIDAL asked for,
 * and closing the dialog cancels the loop, so no poll runs after the user left. Only a session that the host verified is
 * committed, and the dialog closes only once that commit was accepted.
 */
@Composable
private fun TidalDeviceLinkDialog(
    deviceLink: TidalDeviceLink,
    accounts: ProviderAccountLifecycle,
    openExternal: (String) -> Unit,
    copyToClipboard: (String) -> Unit,
    onNotice: (SettingsNotice) -> Unit,
    onDismiss: () -> Unit,
) {
    var run by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<DeviceLinkState>(DeviceLinkState.Requesting) }
    LaunchedEffect(run) {
        state = DeviceLinkState.Requesting
        state = try {
            val attempt = accounts.begin(ProviderAccountLifecycle.TIDAL)
            val authorization = deviceLink.start()
            if (authorization == null) {
                DeviceLinkState.Ended(Res.string.m6_tidal_link_unavailable)
            } else {
                state = DeviceLinkState.Showing(authorization)
                val started = TimeSource.Monotonic.markNow()
                var interval = authorization.intervalMillis.coerceAtLeast(MIN_POLL_INTERVAL_MS)
                var ended: DeviceLinkState? = null
                while (ended == null) {
                    if (started.elapsedNow() >= authorization.expiresInMillis.milliseconds) {
                        ended = DeviceLinkState.Ended(Res.string.m6_tidal_link_expired)
                        break
                    }
                    delay(interval)
                    when (val poll = deviceLink.poll(authorization)) {
                        TidalDeviceLinkPoll.Pending -> Unit
                        TidalDeviceLinkPoll.SlowDown -> interval = tidalDeviceSlowDownInterval(interval)
                        TidalDeviceLinkPoll.Expired -> ended = DeviceLinkState.Ended(Res.string.m6_tidal_link_expired)
                        TidalDeviceLinkPoll.Denied -> ended = DeviceLinkState.Ended(Res.string.m6_tidal_link_denied)
                        TidalDeviceLinkPoll.Failed -> ended = DeviceLinkState.Ended(Res.string.m6_tidal_link_failed)
                        is TidalDeviceLinkPoll.Authorized -> {
                            ended = when (accounts.commitTidal(attempt, poll.session)) {
                                ProviderSignInOutcome.Accepted -> {
                                    onNotice(SettingsNotice.Message(Res.string.m6_tidal_link_connected))
                                    onDismiss()
                                    return@LaunchedEffect
                                }
                                ProviderSignInOutcome.Unverified -> DeviceLinkState.Ended(Res.string.m6_tidal_link_failed)
                                else -> DeviceLinkState.Ended(Res.string.m6_tidal_link_not_saved)
                            }
                        }
                    }
                }
                ended ?: DeviceLinkState.Ended(Res.string.m6_tidal_link_failed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DeviceLinkState.Ended(Res.string.m6_tidal_link_failed)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.m6_tidal_link_title)) },
        text = {
            Column {
                when (val current = state) {
                    DeviceLinkState.Requesting -> {
                        Text(stringResource(Res.string.m6_tidal_link_requesting))
                        CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                    }
                    is DeviceLinkState.Showing -> {
                        Text(stringResource(Res.string.m6_tidal_link_instructions))
                        Text(
                            current.authorization.userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        Text(current.authorization.verificationUri, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { openExternal(current.authorization.verificationUri) }) {
                            Text(stringResource(Res.string.m6_tidal_link_open))
                        }
                        TextButton(onClick = { copyToClipboard(current.authorization.userCode) }) {
                            Text(stringResource(Res.string.m6_tidal_link_copy_code))
                        }
                        Text(
                            stringResource(Res.string.m6_tidal_link_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is DeviceLinkState.Ended -> Text(stringResource(current.message))
                }
            }
        },
        confirmButton = {
            if (state is DeviceLinkState.Ended) {
                TextButton(onClick = { run++ }) { Text(stringResource(Res.string.m6_tidal_link_new_code)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}

/** The floor of the poll interval, so a missing or zero interval never turns the loop into a busy poll. */
private const val MIN_POLL_INTERVAL_MS = 2_000L

/** Ping label for one probe result. Untested instances say so, and the page never probes on its own. */
@Composable
private fun instanceStatusLabel(health: TidalInstanceHealth?): String {
    if (health == null) return stringResource(Res.string.m6_tidal_instance_unknown)
    val latency = (health.latencyMs ?: 0L).toInt()
    return when (health.status) {
        TidalInstanceStatus.HEALTHY -> stringResource(Res.string.m6_tidal_instance_healthy, latency)
        TidalInstanceStatus.PREVIEW_ONLY -> stringResource(Res.string.m6_tidal_instance_preview_only, latency)
        TidalInstanceStatus.UNREACHABLE -> stringResource(Res.string.m6_tidal_instance_unreachable)
    }
}

@Composable
private fun TidalAccountDialog(
    accountName: String,
    userId: Long,
    countryCode: String,
    statusLabel: String,
    subscriptionLabel: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.m6_tidal_details)) },
        text = {
            Column {
                if (accountName.isNotBlank()) {
                    TidalDetailField(stringResource(Res.string.m6_tidal_field_account), accountName)
                }
                if (userId != 0L) {
                    TidalDetailField(stringResource(Res.string.m6_tidal_field_user_id), userId.toString())
                }
                if (countryCode.isNotBlank()) {
                    TidalDetailField(stringResource(Res.string.m6_tidal_field_country), countryCode)
                }
                TidalDetailField(stringResource(Res.string.m6_tidal_field_status), statusLabel)
                TidalDetailField(stringResource(Res.string.m6_tidal_subscription), subscriptionLabel)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6_tidal_close)) } },
    )
}

@Composable
private fun TidalInstanceDialog(
    url: String,
    statusLabel: String,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.m6_tidal_details)) },
        text = {
            Column {
                TidalDetailField(stringResource(Res.string.m6_tidal_field_instance_url), url)
                TidalDetailField(stringResource(Res.string.m6_tidal_field_status), statusLabel)
                TextButton(onClick = onRemove) { Text(stringResource(Res.string.m6_tidal_instance_remove)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6_tidal_close)) } },
        dismissButton = { TextButton(onClick = onCopy) { Text(stringResource(Res.string.m6_tidal_instance_copy_url)) } },
    )
}

/** Single- or multi-line entry with an optional masked mode. Confirm stays disabled until [isValid] accepts the text. */
@Composable
private fun TidalTextDialog(
    title: StringResource,
    placeholder: StringResource,
    multiline: Boolean,
    masked: Boolean,
    description: StringResource?,
    isValid: (String) -> Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = !multiline,
                    minLines = if (multiline) 3 else 1,
                    placeholder = { Text(stringResource(placeholder)) },
                    visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (masked) KeyboardType.Password else KeyboardType.Uri,
                    ),
                )
                description?.let { Text(stringResource(it)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = isValid(text),
                onClick = {
                    onConfirm(text)
                    onDismiss()
                },
            ) { Text(stringResource(Res.string.m6_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}

@Composable
private fun TidalDetailField(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
