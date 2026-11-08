package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Default extraction page; the host opens it, so the shared page only passes the URL along. */
const val PoTokenDefaultSourceUrl = "https://youtube.com/account"

/**
 * Host answer for one extraction attempt. The host runs the sign-in or extraction surface and maps its
 * outcome here. A success that is missing any value is rejected by this page, never stored.
 */
sealed interface PoTokenGeneration {
    /** Values are secrets: [toString] is redacted so logs and diagnostics never carry them. */
    class Complete(val visitorData: String, val gvsToken: String, val playerToken: String) : PoTokenGeneration {
        val isComplete: Boolean get() = visitorData.isNotBlank() && gvsToken.isNotBlank() && playerToken.isNotBlank()
        override fun toString(): String = "PoTokenGeneration.Complete(redacted)"
    }

    /** The user backed out; stored values stay exactly as they were. */
    data object Cancelled : PoTokenGeneration

    /** [detail] must be a sanitized message and never a token value. Null shows the generic failure. */
    data class Failed(val detail: String?) : PoTokenGeneration
}

private sealed interface PoTokenNotice {
    data object Copied : PoTokenNotice
    data object Generated : PoTokenNotice
    data class Failed(val detail: String?) : PoTokenNotice
}

/**
 * Host-reported state of the optional local token helper. It never carries a token or cookie value: [detail] is a
 * sanitized technical hint (a loopback address or a failure class), and [toString] keeps it out of logs entirely.
 */
enum class PoTokenHelperState { DISABLED, IDLE, STARTING, READY, FAILED, UNSUPPORTED }

data class PoTokenHelperStatus(val state: PoTokenHelperState, val detail: String? = null) {
    override fun toString(): String = "PoTokenHelperStatus($state)"
}

/**
 * The web-client switch and, while it is on, the stored GVS, player and visitor values with copy and a
 * regenerate dialog. Extraction is a host callback. The source URL is persisted on every edit as an
 * ordinary preference. Only a complete token result is persisted, in one atomic edit, and a success
 * message appears only after that commit succeeds.
 */
@Composable
fun PoTokenSettings(
    controller: SettingsController,
    generateTokens: suspend (sourceUrl: String) -> PoTokenGeneration,
    copyToClipboard: (String) -> Unit,
    onFailure: () -> Unit,
    modifier: Modifier = Modifier,
    defaultSourceUrl: String = PoTokenDefaultSourceUrl,
    localHelperStatus: (suspend () -> PoTokenHelperStatus)? = null,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()
    var generating by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<PoTokenNotice?>(null) }

    fun copy(token: String) {
        copyToClipboard(token)
        notice = PoTokenNotice.Copied
    }

    /**
     * The source URL is an ordinary preference, so each edit writes it at once, as the baseline's
     * preference-backed field did. A failed write reports through [onFailure] like any other settings edit.
     */
    fun persistSourceUrl(typed: String) {
        scope.launch {
            try { controller.edit { this[PoTokenSourceUrlKey] = typed } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { onFailure() }
        }
    }

    fun regenerate(typedSourceUrl: String) {
        if (generating) return
        scope.launch {
            generating = true
            notice = null
            try {
                // The stored URL already equals the sheet text. A blank URL falls back to the default page.
                val result = try {
                    generateTokens(typedSourceUrl.ifBlank { defaultSourceUrl })
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    PoTokenGeneration.Failed(null)
                }
                when (result) {
                    PoTokenGeneration.Cancelled -> Unit
                    is PoTokenGeneration.Failed -> notice = PoTokenNotice.Failed(result.detail)
                    is PoTokenGeneration.Complete -> if (result.isComplete) {
                        controller.edit {
                            this[PoTokenGvsKey] = result.gvsToken
                            this[PoTokenPlayerKey] = result.playerToken
                            this[VisitorDataKey] = result.visitorData
                        }
                        notice = PoTokenNotice.Generated
                    } else {
                        notice = PoTokenNotice.Failed(null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onFailure()
            } finally {
                generating = false
            }
        }
    }

    SettingsPage(
        rows = buildList {
            add(
                SettingsRow.Toggle(
                    anchor = "web_client_po_token",
                    title = Res.string.m6_po_token_web_client,
                    key = WebClientPoTokenEnabledKey,
                    default = false,
                    description = Res.string.m6_po_token_web_client_desc,
                ),
            )
            if (localHelperStatus != null) {
                add(
                    SettingsRow.Toggle(
                        anchor = "desktop_local_po_token_helper",
                        title = Res.string.m6_po_token_helper,
                        key = DesktopPoTokenHelperEnabledKey,
                        default = true,
                        description = Res.string.m6_po_token_helper_desc,
                    ),
                )
            }
        },
        preferences = preferences,
        controller = controller,
        onFailure = onFailure,
        modifier = modifier,
        extraItems = {
            localHelperStatus?.let { loader ->
                item(key = "po_token_helper_status") {
                    PoTokenHelperStatusRow(
                        enabled = preferences[DesktopPoTokenHelperEnabledKey, true],
                        load = loader,
                    )
                }
            }
            if (preferences[WebClientPoTokenEnabledKey, false]) {
                item(key = "po_token_generated_title") {
                    Text(
                        stringResource(Res.string.m6_po_token_generated_tokens),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                    )
                }
                item(key = "po_token_gvs") {
                    PoTokenValueRow(stringResource(Res.string.m6_po_token_gvs), preferences[PoTokenGvsKey, ""], ::copy)
                }
                item(key = "po_token_player") {
                    PoTokenValueRow(stringResource(Res.string.m6_po_token_player), preferences[PoTokenPlayerKey, ""], ::copy)
                }
                item(key = "po_token_visitor_data") {
                    PoTokenValueRow(stringResource(Res.string.m6_po_token_visitor_data), preferences[VisitorDataKey, ""], ::copy)
                }
                item(key = "po_token_regenerate") {
                    Button(
                        onClick = {
                            draft = preferences[PoTokenSourceUrlKey, ""]
                            sheetOpen = true
                        },
                        enabled = !generating,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text(stringResource(Res.string.m6_po_token_regenerate)) }
                }
                notice?.let { current ->
                    item(key = "po_token_notice") { PoTokenNoticeText(current) }
                }
            }
        },
    )

    if (sheetOpen) AlertDialog(
        onDismissRequest = { if (!generating) sheetOpen = false },
        title = { Text(stringResource(Res.string.m6_po_token_source_url)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { typed ->
                    draft = typed
                    persistSourceUrl(typed)
                },
                label = { Text(stringResource(Res.string.m6_po_token_source_url)) },
                placeholder = { Text(stringResource(Res.string.m6_po_token_source_url_placeholder)) },
                singleLine = true,
                enabled = !generating,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = !generating, onClick = {
                sheetOpen = false
                regenerate(draft)
            }) { Text(stringResource(Res.string.m6_po_token_regenerate_token)) }
        },
        dismissButton = { TextButton(onClick = { sheetOpen = false }) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}

/** An empty value is shown as a dash and is not copyable, so a blank token is never offered. */
@Composable
private fun PoTokenValueRow(label: String, token: String, onCopy: (String) -> Unit) {
    ListItem(
        overlineContent = { Text(label, color = MaterialTheme.colorScheme.primary) },
        headlineContent = {
            SelectionContainer {
                Text(
                    token.ifBlank { stringResource(Res.string.m6_po_token_empty_value) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            if (token.isNotBlank()) TextButton(onClick = { onCopy(token) }) {
                Text(stringResource(Res.string.m6_po_token_copy))
            }
        },
        modifier = Modifier.clickable(enabled = token.isNotBlank()) { onCopy(token) },
    )
}

@Composable
private fun PoTokenHelperStatusRow(enabled: Boolean, load: suspend () -> PoTokenHelperStatus) {
    var refresh by remember(enabled) { mutableStateOf(0) }
    var status by remember(enabled) { mutableStateOf<PoTokenHelperStatus?>(null) }
    LaunchedEffect(enabled, refresh) {
        status =
            try {
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                PoTokenHelperStatus(PoTokenHelperState.FAILED)
            }
    }
    ListItem(
        overlineContent = { Text(stringResource(Res.string.m6_po_token_helper_status), color = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(PoTokenHelperStatusText(status)) },
        trailingContent = {
            if (enabled) {
                TextButton(onClick = { refresh += 1 }) { Text(stringResource(Res.string.m6_po_token_helper_refresh)) }
            }
        },
    )
}

@Composable
private fun PoTokenHelperStatusText(status: PoTokenHelperStatus?): String {
    if (status == null) return stringResource(Res.string.m6_po_token_helper_checking)
    val label =
        when (status.state) {
            PoTokenHelperState.DISABLED -> stringResource(Res.string.m6_po_token_helper_disabled)
            PoTokenHelperState.IDLE -> stringResource(Res.string.m6_po_token_helper_idle)
            PoTokenHelperState.STARTING -> stringResource(Res.string.m6_po_token_helper_starting)
            PoTokenHelperState.READY -> stringResource(Res.string.m6_po_token_helper_ready)
            PoTokenHelperState.FAILED -> stringResource(Res.string.m6_po_token_helper_failed)
            PoTokenHelperState.UNSUPPORTED -> stringResource(Res.string.m6_po_token_helper_unsupported)
        }
    val detail = status.detail?.takeIf(String::isNotBlank) ?: return label
    return "$label · $detail"
}

@Composable
private fun PoTokenNoticeText(notice: PoTokenNotice) {
    val text = when (notice) {
        PoTokenNotice.Copied -> stringResource(Res.string.m6_po_token_copied)
        PoTokenNotice.Generated -> stringResource(Res.string.m6_po_token_generated)
        is PoTokenNotice.Failed -> notice.detail?.takeIf(String::isNotBlank)
            ?: stringResource(Res.string.m6_po_token_failed)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
