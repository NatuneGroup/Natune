package moe.rukamori.archivetune.shared.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.setValue

enum class RoomConnectionUi { NOT_CONFIGURED, DISCONNECTED, CONNECTING, CONNECTED, ERROR }
data class RoomMemberUi(val id: String, val name: String, val host: Boolean, val self: Boolean, val connected: Boolean)
data class RoomJoinRequestUi(val id: String, val name: String, val busy: Boolean)
data class RoomSuggestionUi(val id: String, val author: String, val track: ContentOccurrence, val busy: Boolean)
data class RoomQueueRowUi(val occurrence: ContentOccurrence, val current: Boolean)
data class ListenRoomUi(
    val code: String,
    val isHost: Boolean,
    val members: List<RoomMemberUi>,
    val requests: List<RoomJoinRequestUi>,
    val suggestions: List<RoomSuggestionUi>,
    val queue: List<RoomQueueRowUi>,
    val unread: Int,
)
/** Presentation only. Endpoint allow-list, protocol, bounded waits and live room identity belong to the existing manager. */
data class ListenTogetherUi(
    val connection: RoomConnectionUi,
    val username: String,
    val roomCode: String,
    val room: ListenRoomUi?,
    val hostingAllowed: Boolean,
    val hostingRestriction: String?,
    val joining: Boolean = false,
    val creating: Boolean = false,
    val waitingForApproval: Boolean = false,
    val error: String? = null,
)
/** Data so the navigation host can rebind [back] to the shell stack without losing room operations. */
data class ListenTogetherActions(
    val back: () -> Unit,
    val connect: () -> Unit,
    val disconnect: () -> Unit,
    val reconnect: () -> Unit,
    val username: (String) -> Unit,
    val roomCode: (String) -> Unit,
    val create: (String) -> Unit,
    val join: (String, String) -> Unit,
    val cancelJoin: () -> Unit,
    val leave: () -> Unit,
    val copyCode: () -> Unit,
    val shareInvite: () -> Unit,
    val chat: () -> Unit,
    val approve: (String) -> Unit,
    val reject: (String) -> Unit,
    val approveSuggestion: (String) -> Unit,
    val rejectSuggestion: (String) -> Unit,
    val kick: (RoomMemberUi, Boolean) -> Unit,
    val transfer: (RoomMemberUi) -> Unit,
    val suggestSong: () -> Unit,
    val settings: () -> Unit,
)

@Composable
fun ListenTogetherScreen(
    state: ListenTogetherUi,
    actions: ListenTogetherActions,
    artwork: ContentArtwork,
    memberAvatar: @Composable (RoomMemberUi, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
    /** Baseline shows its header for the top-bar route or the in-top-bar preference (default true). */
    showHeader: Boolean = true,
) {
    var selectedMemberId by rememberSaveable { mutableStateOf<String?>(null) }
    val room = state.room
    val busy = state.joining || state.creating || state.waitingForApproval
    Column(modifier.fillMaxSize()) {
        if (showHeader) ManagementHeader(stringResource(Res.string.m4_listen_together), actions.back)
        LazyColumn(Modifier.weight(1f).imePadding(), contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("connection") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(when (state.connection) {
                        RoomConnectionUi.NOT_CONFIGURED -> Res.string.m4_room_not_configured
                        RoomConnectionUi.DISCONNECTED -> Res.string.m4_room_disconnected
                        RoomConnectionUi.CONNECTING -> Res.string.m4_room_connecting
                        RoomConnectionUi.CONNECTED -> Res.string.m4_room_connected
                        RoomConnectionUi.ERROR -> Res.string.m4_room_connection_failed
                    }), style = MaterialTheme.typography.titleLarge)
                    when (state.connection) {
                        RoomConnectionUi.DISCONNECTED -> Button(actions.connect) { Text(stringResource(Res.string.m4_connect)) }
                        RoomConnectionUi.CONNECTED -> TextButton(actions.disconnect) { Text(stringResource(Res.string.m4_disconnect)) }
                        RoomConnectionUi.ERROR -> Button(actions.reconnect) { Text(stringResource(Res.string.m4_retry)) }
                        RoomConnectionUi.CONNECTING -> TextButton(actions.disconnect) { Text(stringResource(Res.string.m4_cancel)) }
                        RoomConnectionUi.NOT_CONFIGURED -> Unit
                    }
                    if (state.connection == RoomConnectionUi.CONNECTED) Text(stringResource(Res.string.m4_room_background_note),
                        style = MaterialTheme.typography.bodySmall)
                    state.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            item("join-create") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.username, actions.username, label = { Text(stringResource(Res.string.m4_username)) },
                        singleLine = true, readOnly = room != null, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(room?.code ?: state.roomCode, actions.roomCode,
                        label = { Text(stringResource(Res.string.m4_room_code)) }, singleLine = true, readOnly = room != null,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    if (state.waitingForApproval) Text(stringResource(Res.string.m4_room_waiting))
                    if (!state.hostingAllowed) Text(state.hostingRestriction?.takeIf { it.isNotBlank() }
                        ?: stringResource(Res.string.m4_room_hosting_restricted), color = MaterialTheme.colorScheme.error)
                    when {
                        room != null -> TextButton(actions.leave) { Text(stringResource(Res.string.m4_leave_room)) }
                        busy -> TextButton(actions.cancelJoin, enabled = !state.creating) { Text(stringResource(Res.string.m4_cancel)) }
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val connected = state.connection == RoomConnectionUi.CONNECTED
                            Button({ actions.join(state.roomCode.trim(), state.username.trim()) },
                                enabled = connected && state.username.isNotBlank() && state.roomCode.trim().length == 8) {
                                Text(stringResource(Res.string.m4_join_room))
                            }
                            OutlinedButton({ actions.create(state.username.trim()) },
                                enabled = connected && state.hostingAllowed && state.username.isNotBlank()) {
                                Text(stringResource(Res.string.m4_create_room))
                            }
                        }
                    }
                }
            }
            if (room != null) {
                item("room-actions") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { TextButton(actions.copyCode) { Text(stringResource(Res.string.m4_copy_code)) } }
                        item { TextButton(actions.shareInvite) { Text(stringResource(Res.string.m4_share_invite)) } }
                        item { TextButton(actions.chat) { Text(stringResource(Res.string.m4_room_chat, room.unread)) } }
                    }
                }
                items(room.members.filter { it.connected }, key = { "member-${it.id}" }) { member ->
                    ListItem(headlineContent = { Text(member.name) },
                        supportingContent = if (member.host) ({ Text(stringResource(Res.string.m4_room_host)) }) else null,
                        leadingContent = { memberAvatar(member, Modifier.size(48.dp)) },
                        trailingContent = if (room.isHost && !member.self) ({
                            TextButton({ selectedMemberId = member.id }) { Text(stringResource(Res.string.m4_more)) }
                        }) else null)
                }
                if (room.isHost) {
                    items(room.requests, key = { "request-${it.id}" }) { request ->
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text(request.name)
                            Row {
                                TextButton({ actions.approve(request.id) }, enabled = !request.busy) { Text(stringResource(Res.string.m4_approve)) }
                                TextButton({ actions.reject(request.id) }, enabled = !request.busy) { Text(stringResource(Res.string.m4_reject)) }
                            }
                        }
                    }
                    items(room.suggestions, key = { "suggestion-${it.id}" }) { suggestion ->
                        ListItem(headlineContent = { Text(suggestion.track.item.title) },
                            supportingContent = { Text(suggestion.author) },
                            leadingContent = { artwork(suggestion.track.item, Modifier.size(48.dp)) },
                            trailingContent = { Column {
                                TextButton({ actions.approveSuggestion(suggestion.id) }, enabled = !suggestion.busy) { Text(stringResource(Res.string.m4_approve)) }
                                TextButton({ actions.rejectSuggestion(suggestion.id) }, enabled = !suggestion.busy) { Text(stringResource(Res.string.m4_reject)) }
                            } })
                    }
                } else item("suggest") {
                    TextButton(actions.suggestSong) { Text(stringResource(Res.string.m4_suggest_song)) }
                }
                item("queue-header") { Text(stringResource(Res.string.m4_room_queue), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) }
                items(room.queue, key = { "queue-${it.occurrence.occurrenceId}" }) { row ->
                    ListItem(headlineContent = { Text(row.occurrence.item.title) },
                        supportingContent = { Text(row.occurrence.item.subtitle) },
                        leadingContent = { artwork(row.occurrence.item, Modifier.size(48.dp)) },
                        trailingContent = if (row.current) ({ Text(stringResource(Res.string.m4_now_playing)) }) else null)
                }
            }
            item("settings") { TextButton(actions.settings) { Text(stringResource(Res.string.m4_settings)) } }
        }
    }
    val member = room?.members?.firstOrNull { it.id == selectedMemberId && !it.self }
    if (room?.isHost == true && member != null) AlertDialog(
        onDismissRequest = { selectedMemberId = null }, title = { Text(member.name) },
        text = { Column {
            TextButton({ selectedMemberId = null; actions.kick(member, false) }) { Text(stringResource(Res.string.m4_kick_member)) }
            TextButton({ selectedMemberId = null; actions.kick(member, true) }) { Text(stringResource(Res.string.m4_block_member)) }
            TextButton({ selectedMemberId = null; actions.transfer(member) }) { Text(stringResource(Res.string.m4_transfer_host)) }
        } },
        confirmButton = { TextButton({ selectedMemberId = null }) { Text(stringResource(Res.string.m4_close)) } })
}
