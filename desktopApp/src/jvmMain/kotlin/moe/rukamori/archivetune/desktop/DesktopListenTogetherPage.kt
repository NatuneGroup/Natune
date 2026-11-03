package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.data.listentogether.ChatSongPickerOwner
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSettingsDefaults
import moe.rukamori.archivetune.shared.domain.settings.listenTogetherAvatarIndex
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.ChatSongPicker
import moe.rukamori.archivetune.shared.ui.screens.ChatSongPickerActions
import moe.rukamori.archivetune.shared.ui.screens.ChatSongPickerUi
import moe.rukamori.archivetune.shared.ui.screens.ListenTogetherActions
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.shared.ui.settings.ListenTogetherSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsNotice

/** The Listen Together settings route, as the shared settings index names it. */
internal const val DesktopListenTogetherSettingsRoute = "settings/integrations/listen_together"

/**
 * The Listen Together room page over the graph's one owner. The chat opens the mounted chat route; the song picker
 * for suggestions and the custom member picture are not connected on this host, so those actions say so on the page
 * instead of opening nothing.
 */
@Composable
internal fun desktopListenTogetherPage(graph: DesktopSessionGraph, openRoute: (String) -> Unit): ArchiveGraphPage {
    val owner = graph.listenTogether
    val state by owner.ui.collectAsState()
    val uiScope = rememberCoroutineScope()
    var suggesting by remember { mutableStateOf(false) }
    val picker = remember(owner, uiScope) {
        owner.suggestionPicker(uiScope) { delivered ->
            suggesting = false
            owner.showMessage(
                DesktopStrings[if (delivered) "listen_together_suggestion_sent" else "listen_together_suggestion_guest_only"],
            )
        }
    }
    DisposableEffect(picker) { onDispose { picker.close() } }
    if (suggesting) {
        DesktopSuggestionPicker(graph, picker) {
            picker.close()
            suggesting = false
        }
    }
    val actions = remember(owner) {
        ListenTogetherActions(
            back = { },
            connect = owner::connect,
            disconnect = owner::disconnect,
            reconnect = owner::reconnect,
            username = owner::setUsername,
            roomCode = owner::setRoomCode,
            create = owner::create,
            join = owner::join,
            cancelJoin = owner::cancelJoin,
            leave = owner::leave,
            copyCode = { owner.client.roomCode.value?.let(::copyToDesktopClipboard) },
            shareInvite = { owner.inviteLink()?.let(::copyToDesktopClipboard) },
            chat = { openRoute("listen_together/chat") },
            approve = owner::approve,
            reject = owner::reject,
            approveSuggestion = owner::approveSuggestion,
            rejectSuggestion = owner::rejectSuggestion,
            kick = owner::kick,
            transfer = owner::transfer,
            suggestSong = { suggesting = true },
            settings = { openRoute(DesktopListenTogetherSettingsRoute) },
        )
    }
    return ArchiveGraphPage.ListenTogether(state, actions, avatar = { member, modifier ->
        DesktopListenTogetherAvatar(member.name, modifier)
    })
}

/** Settings > Listen Together over the same owner, so a blocked user released here is released in the live client too. */
@Composable
internal fun DesktopListenTogetherSettingsPage(
    graph: DesktopSessionGraph,
    controller: SettingsController,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
    unavailable: () -> Unit,
    scrollTo: String?,
) {
    val owner = graph.listenTogether
    val role by owner.settingsRole.collectAsState()
    val logs by owner.logs.collectAsState()
    val scope = rememberCoroutineScope()
    val snapshot by graph.settings.snapshots.collectAsState()
    val customPicture by produceState<ImageBitmap?>(null, snapshot?.listenTogetherAvatarIndex()) {
        value = if (snapshot?.listenTogetherAvatarIndex() == ListenTogetherSettingsDefaults.CUSTOM_AVATAR_INDEX) {
            owner.localAvatarBytes()?.let { runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
        } else {
            null
        }
    }
    ListenTogetherSettings(
        controller = controller,
        servers = owner.servers,
        role = role,
        logs = logs,
        onPickCustomAvatar = {
            // The pick suspends inside the owner, so the settings page launches it like the iOS one does.
            scope.launch {
                if (!owner.pickCustomAvatar()) onNotice(SettingsNotice.Message(Res.string.m6_listen_avatar_failed))
            }
        },
        onUnblockUser = owner.client::unblockUser,
        onClearLogs = owner::clearLogs,
        onCopyLogs = ::copyToDesktopClipboard,
        onNotice = onNotice,
        onFailure = onFailure,
        scrollTo = scrollTo,
        avatar = { index, modifier ->
            val picture = customPicture
            if (index == ListenTogetherSettingsDefaults.CUSTOM_AVATAR_INDEX && picture != null) {
                Image(bitmap = picture, contentDescription = null, modifier = modifier.clip(CircleShape))
            } else {
                DesktopListenTogetherAvatar("${index + 1}", modifier)
            }
        },
    )
}

/** A member's mark: the first letter of the name on a round tile. The preset avatar art is not bundled on this host. */
@Composable
internal fun DesktopListenTogetherAvatar(label: String, modifier: Modifier) {
    Box(
        modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.trim().take(if (label.trim().all(Char::isDigit)) 2 else 1).uppercase(),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}


/** The shared song picker as the room's suggestion sheet. It searches songs only, and there is no now-playing row. */
@Composable
private fun DesktopSuggestionPicker(graph: DesktopSessionGraph, picker: ChatSongPickerOwner, dismiss: () -> Unit) {
    val state by picker.state.collectAsState()
    ChatSongPicker(
        state = ChatSongPickerUi(
            query = state.query,
            page = PagedContentState(
                items = state.items,
                continuation = state.continuation,
                loading = state.loading,
                initialized = state.initialized,
                failed = state.failed,
            ),
            current = null,
            busy = false,
        ),
        actions = ChatSongPickerActions(
            query = picker::changeQuery,
            search = picker::search,
            share = picker::share,
            retry = picker::retry,
            loadMore = picker::loadMore,
            dismiss = dismiss,
        ),
        artwork = { item, modifier -> DesktopRemoteArtwork(item.artworkUri, graph.artworkLoader, modifier) },
    )
}
