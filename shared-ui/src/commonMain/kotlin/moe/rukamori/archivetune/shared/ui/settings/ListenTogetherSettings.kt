/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Listen Together settings. Rows and dialogs commit preference edits through [controller]. The host owns the approved
 * server list, the live client (a blocked user is released through [onUnblockUser] so the running client drops its copy
 * too), the connection log, the custom picture file behind [onPickCustomAvatar], and the avatar art in [avatar]. Name and
 * avatar edits are refused inside a room, and approval toggles are read-only for guests.
 */
@Composable
fun ListenTogetherSettings(
    controller: SettingsController,
    servers: List<ListenTogetherServerOption>,
    role: ListenTogetherSettingsRole,
    logs: List<ListenTogetherSettingsLogLine>,
    onPickCustomAvatar: () -> Unit,
    onUnblockUser: (String) -> Unit,
    onClearLogs: () -> Unit,
    onCopyLogs: (String) -> Unit,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    resumed: Boolean = true,
    motionEnabled: Boolean = true,
    avatar: @Composable (index: Int, modifier: Modifier) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()

    val defaultServerUrl = servers.firstOrNull()?.url.orEmpty()
    val username = preferences.listenTogetherUsername()
    val avatarIndex = preferences.listenTogetherAvatarIndex()
    val serverUrl = preferences.listenTogetherServerUrl(defaultServerUrl)
    val blocked = preferences.listenTogetherBlockedUsers()
    val identityEditable = listenTogetherIdentityEditable(role)
    val approvalEditable = listenTogetherApprovalEditable(role)

    var usernameOpen by rememberSaveable { mutableStateOf(false) }
    var serverOpen by rememberSaveable { mutableStateOf(false) }
    var avatarOpen by rememberSaveable { mutableStateOf(false) }
    var blockedOpen by rememberSaveable { mutableStateOf(false) }
    var logsOpen by rememberSaveable { mutableStateOf(false) }

    fun commit(change: PreferenceEditor.() -> Unit) {
        scope.launch {
            try {
                controller.edit(change)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }

    fun whenIdentityEditable(action: () -> Unit) {
        if (identityEditable) action() else onNotice(SettingsNotice.Message(Res.string.m6_listen_cannot_edit_in_room))
    }

    val notSet = stringResource(Res.string.m6_listen_not_set)
    val blockedSummary = if (blocked.isNotEmpty()) {
        stringResource(Res.string.m6_listen_blocked_users_count, blocked.size)
    } else {
        stringResource(Res.string.m6_listen_no_blocked_users)
    }
    val blockedRun: (() -> Unit)? = if (blocked.isNotEmpty()) ({ blockedOpen = true }) else null

    val rows = buildList<SettingsRow> {
        add(SettingsRow.Header("listen_together_settings", Res.string.m6_listen_group_settings))
        add(
            SettingsRow.Status(
                anchor = "listen_together_username",
                title = Res.string.m6_listen_username,
                value = username.ifEmpty { notSet },
                run = { whenIdentityEditable { usernameOpen = true } },
            ),
        )
        add(
            SettingsRow.Status(
                anchor = "listen_together_server_url",
                title = Res.string.m6_listen_server_url,
                value = listenTogetherServerLabel(serverUrl, servers),
                run = { serverOpen = true },
            ),
        )
        add(
            SettingsRow.Action(
                anchor = "listen_together_custom_avatar",
                title = Res.string.m6_listen_custom_avatar,
                run = { whenIdentityEditable(onPickCustomAvatar) },
                description = Res.string.m6_listen_custom_avatar_desc,
            ),
        )
        add(
            SettingsRow.Status(
                anchor = "listen_together_blocked_users",
                title = Res.string.m6_listen_blocked_users,
                value = blockedSummary,
                run = blockedRun,
            ),
        )
        add(
            SettingsRow.Toggle(
                anchor = "listen_together_auto_approval",
                title = Res.string.m6_listen_auto_approval,
                key = ListenTogetherAutoApprovalKey,
                default = ListenTogetherSettingsDefaults.AUTO_APPROVAL,
                description = Res.string.m6_listen_auto_approval_desc,
                enabled = approvalEditable,
            ),
        )
        add(
            SettingsRow.Toggle(
                anchor = "listen_together_suggestion_auto_approve",
                title = Res.string.m6_listen_suggestion_auto_approve,
                key = ListenTogetherSuggestionAutoApproveKey,
                default = ListenTogetherSettingsDefaults.SUGGESTION_AUTO_APPROVE,
                description = Res.string.m6_listen_suggestion_auto_approve_desc,
                enabled = approvalEditable,
            ),
        )
        add(
            SettingsRow.Toggle(
                anchor = "listen_together_chat_notifications",
                title = Res.string.m6_listen_chat_notifications,
                key = ListenTogetherChatNotificationsKey,
                default = ListenTogetherSettingsDefaults.CHAT_NOTIFICATIONS,
                description = Res.string.m6_listen_chat_notifications_desc,
            ),
        )
        add(
            SettingsRow.Toggle(
                anchor = "listen_together_sync_volume",
                title = Res.string.m6_listen_sync_volume,
                key = ListenTogetherSyncVolumeKey,
                default = ListenTogetherSettingsDefaults.SYNC_HOST_VOLUME,
                description = Res.string.m6_listen_sync_volume_desc,
            ),
        )
        add(
            SettingsRow.Toggle(
                anchor = "listen_together_smart_resync",
                title = Res.string.m6_listen_smart_resync,
                key = ListenTogetherSmartResyncKey,
                default = ListenTogetherSettingsDefaults.SMART_RESYNC,
                description = Res.string.m6_listen_smart_resync_desc,
            ),
        )
        add(
            SettingsRow.Action(
                anchor = "listen_together_view_logs",
                title = Res.string.m6_listen_view_logs,
                run = { logsOpen = true },
                description = Res.string.m6_listen_view_logs_desc,
            ),
        )
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
        leadingItems = {
            item(key = "listen_together_profile") {
                ListenTogetherProfileCard(
                    avatarIndex = avatarIndex,
                    username = username.ifEmpty { notSet },
                    avatar = avatar,
                    onAvatarClick = { whenIdentityEditable { avatarOpen = true } },
                )
            }
        },
        leadingItemCount = 1,
        extraItems = {
            item(key = "listen_together_description") {
                Text(
                    text = stringResource(Res.string.m6_listen_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        },
    )

    if (usernameOpen) {
        var draft by rememberSaveable(usernameOpen) { mutableStateOf(username) }
        AlertDialog(
            onDismissRequest = { usernameOpen = false },
            title = { Text(stringResource(Res.string.m6_listen_username)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(Res.string.m6_listen_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = draft
                        usernameOpen = false
                        commit { commitListenTogetherUsername(name) }
                    },
                ) { Text(stringResource(Res.string.m6_listen_ok)) }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            usernameOpen = false
                            commit { commitListenTogetherUsername("") }
                        },
                    ) { Text(stringResource(Res.string.m6_listen_reset)) }
                    TextButton(onClick = { usernameOpen = false }) { Text(stringResource(Res.string.m6_cancel)) }
                }
            },
        )
    }

    if (serverOpen) {
        var customDraft by rememberSaveable(serverOpen) { mutableStateOf(serverUrl) }
        val trimmedCustom = customDraft.trim()
        AlertDialog(
            onDismissRequest = { serverOpen = false },
            title = { Text(stringResource(Res.string.m6_listen_choose_server)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    servers.forEach { server ->
                        val selected = server.url == serverUrl
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                serverOpen = false
                                commit { commitListenTogetherServerUrl(server.url) }
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${server.location} - ${server.operator}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = server.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    Text(
                        text = stringResource(Res.string.m6_listen_custom_server),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = customDraft,
                        onValueChange = { customDraft = it },
                        label = { Text(stringResource(Res.string.m6_listen_server_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            serverOpen = false
                            commit { commitListenTogetherServerUrl(trimmedCustom) }
                        },
                        enabled = trimmedCustom.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(stringResource(Res.string.m6_listen_use_custom_server)) }
                }
            },
            confirmButton = { TextButton(onClick = { serverOpen = false }) { Text(stringResource(Res.string.m6_cancel)) } },
        )
    }

    if (avatarOpen) {
        AlertDialog(
            onDismissRequest = { avatarOpen = false },
            title = { Text(stringResource(Res.string.m6_listen_choose_avatar)) },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().height(248.dp),
                ) {
                    items((0 until ListenTogetherSettingsDefaults.PRESET_AVATAR_COUNT).toList()) { index ->
                        Surface(
                            modifier = Modifier
                                .padding(6.dp)
                                .size(56.dp)
                                .clip(CircleShape)
                                .clickable {
                                    avatarOpen = false
                                    commit { commitListenTogetherAvatar(index) }
                                },
                            shape = CircleShape,
                            color = if (index == avatarIndex) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            avatar(index, Modifier.fillMaxSize())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { avatarOpen = false }) { Text(stringResource(Res.string.m6_listen_ok)) } },
        )
    }

    if (blockedOpen) {
        AlertDialog(
            onDismissRequest = { blockedOpen = false },
            title = { Text(stringResource(Res.string.m6_listen_blocked_users)) },
            text = {
                if (blocked.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.m6_listen_no_blocked_users),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(blocked) { blockedName ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = blockedName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onUnblockUser(blockedName) }) {
                                    Text(stringResource(Res.string.m6_listen_unblock))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { blockedOpen = false }) { Text(stringResource(Res.string.m6_listen_ok)) } },
        )
    }

    if (logsOpen) {
        val listState = rememberLazyListState()
        LaunchedEffect(logs.size) {
            if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
        }
        AlertDialog(
            onDismissRequest = { logsOpen = false },
            title = { Text(stringResource(Res.string.m6_listen_logs)) },
            text = {
                Box(modifier = Modifier.fillMaxWidth().height(350.dp)) {
                    if (logs.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.m6_listen_no_logs),
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(logs) { line -> ListenTogetherLogRow(line) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { logsOpen = false }) { Text(stringResource(Res.string.m6_listen_ok)) } },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = logs.isNotEmpty(),
                        onClick = {
                            onCopyLogs(listenTogetherLogsText(logs))
                            onNotice(SettingsNotice.Message(Res.string.m6_listen_copied))
                        },
                    ) { Text(stringResource(Res.string.m6_listen_copy)) }
                    TextButton(onClick = onClearLogs) { Text(stringResource(Res.string.m6_listen_clear)) }
                }
            },
        )
    }
}

@Composable
private fun ListenTogetherProfileCard(
    avatarIndex: Int,
    username: String,
    avatar: @Composable (index: Int, modifier: Modifier) -> Unit,
    onAvatarClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onAvatarClick),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            avatar(avatarIndex, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Text(text = username, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ListenTogetherLogRow(line: ListenTogetherSettingsLogLine) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = line.timeText,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = when (line.level) {
                    ListenTogetherSettingsLogLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
                    ListenTogetherSettingsLogLevel.WARNING -> Color(0xFFFFF3CD)
                    ListenTogetherSettingsLogLevel.DEBUG -> MaterialTheme.colorScheme.surfaceVariant
                    ListenTogetherSettingsLogLevel.INFO -> MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                Text(
                    text = line.level.name,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    color = when (line.level) {
                        ListenTogetherSettingsLogLevel.ERROR -> MaterialTheme.colorScheme.onErrorContainer
                        ListenTogetherSettingsLogLevel.WARNING -> Color(0xFF856404)
                        ListenTogetherSettingsLogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                        ListenTogetherSettingsLogLevel.INFO -> MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
            }
        }
        Text(
            text = line.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        line.details?.let { details ->
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun listenTogetherLogsText(logs: List<ListenTogetherSettingsLogLine>): String =
    logs.joinToString("\n") { line ->
        buildString {
            append(line.timeText)
            append(" [").append(line.level.name).append("] ")
            append(line.message)
            line.details?.let { append(" -- ").append(it) }
        }
    }
