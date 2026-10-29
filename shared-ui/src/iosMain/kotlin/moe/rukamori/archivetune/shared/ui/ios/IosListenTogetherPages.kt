package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import moe.rukamori.archivetune.shared.data.listentogether.TrackInfo
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ListenTogetherActions
import moe.rukamori.archivetune.shared.ui.screens.commenttogether.CommentTogetherNativePorts
import moe.rukamori.archivetune.shared.ui.settings.ListenTogetherSettings
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import moe.rukamori.archivetune.shared.ui.settings.SettingsNotice
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970

/** The Listen Together settings route, as the shared settings index names it. */
internal const val IosListenTogetherSettingsRoute = "settings/integrations/listen_together"

/** The chat route the room page opens, as the shared route table names it. */
private const val IOS_LISTEN_TOGETHER_CHAT_ROUTE = "listen_together/chat"

/**
 * The Listen Together room page over the graph's one owner. The chat and the settings open their routes. The song picker
 * for suggestions is not connected on this host, so that action says so on the page instead of opening nothing. Back is
 * the root's back, the same as the system gesture. The invite goes out through the iOS share sheet.
 */
@Composable
internal fun iosListenTogetherPage(
    graph: IosSessionGraph,
    host: IosHost,
    back: () -> Unit,
    openRoute: (String) -> Unit,
): ArchiveGraphPage {
    val owner = graph.listenTogether
    val state by owner.ui.collectAsState()
    val unavailable = stringResource(Res.string.ios_settings_feature_unavailable)
    val actions = remember(owner, host, back, openRoute, unavailable) {
        ListenTogetherActions(
            back = back,
            connect = owner::connect,
            disconnect = owner::disconnect,
            reconnect = owner::reconnect,
            username = owner::setUsername,
            roomCode = owner::setRoomCode,
            create = owner::create,
            join = owner::join,
            cancelJoin = owner::cancelJoin,
            leave = owner::leave,
            copyCode = { owner.client.roomCode.value?.let(::copyToIosClipboard) },
            shareInvite = { owner.inviteLink()?.let { link -> iosShareText(host, link) } },
            chat = { openRoute(IOS_LISTEN_TOGETHER_CHAT_ROUTE) },
            approve = owner::approve,
            reject = owner::reject,
            approveSuggestion = owner::approveSuggestion,
            rejectSuggestion = owner::rejectSuggestion,
            kick = owner::kick,
            transfer = owner::transfer,
            suggestSong = { owner.showMessage(unavailable) },
            settings = { openRoute(IosListenTogetherSettingsRoute) },
        )
    }
    return ArchiveGraphPage.ListenTogether(state, actions, avatar = { member, modifier ->
        IosLetterAvatar(member.name, modifier)
    })
}

/**
 * Settings > Listen Together over the same owner, so a blocked user released here is released in the live client too.
 * The custom member picture is not connected on this host, so that row reports it through [unavailable].
 */
@Composable
internal fun IosListenTogetherSettingsPage(
    owner: IosListenTogetherOwner,
    controller: SettingsController,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
    unavailable: () -> Unit,
    scrollTo: String?,
) {
    val role by owner.settingsRole.collectAsState()
    val logs by owner.logs.collectAsState()
    ListenTogetherSettings(
        controller = controller,
        servers = owner.servers,
        role = role,
        logs = logs,
        onPickCustomAvatar = unavailable,
        onUnblockUser = owner.client::unblockUser,
        onClearLogs = owner::clearLogs,
        onCopyLogs = ::copyToIosClipboard,
        onNotice = onNotice,
        onFailure = onFailure,
        scrollTo = scrollTo,
        avatar = { index, modifier -> IosLetterAvatar("${index + 1}", modifier) },
    )
}

/**
 * The chat screen over the iOS Listen Together owner: the shared binding on the caller's UI scope, the same letter tile
 * the room list uses, and remote artwork for shared covers. Back is the root's back, the same as the system gesture.
 */
@Composable
internal fun iosCommentTogetherPage(graph: IosSessionGraph, host: IosHost, back: () -> Unit): ArchiveGraphPage {
    val owner = graph.listenTogether
    val uiScope = rememberCoroutineScope()
    val mount = remember(owner, host, back, uiScope) {
        owner.commentTogether(iosCommentTogetherPorts(graph, host, back, uiScope), uiScope)
    }
    DisposableEffect(mount) { onDispose { mount.close() } }
    return ArchiveGraphPage.CommentTogether(
        owner = mount.binding,
        avatar = { author, modifier -> IosLetterAvatar(author.username, modifier) },
        thumbnail = { url, modifier -> IosRemoteArtwork(url, graph.artworkLoader, modifier) },
    )
}

/**
 * The chat's native ports on this host. The local song is the player's current queue item, the share fallback for a
 * room with no current track, as the desktop host maps it. Its flow stops with the chat mount.
 */
private fun iosCommentTogetherPorts(
    graph: IosSessionGraph,
    host: IosHost,
    onBack: () -> Unit,
    uiScope: CoroutineScope,
): CommentTogetherNativePorts = object : CommentTogetherNativePorts {
    override val localCurrentTrack: StateFlow<TrackInfo?> =
        combine(graph.queue.state, graph.player.snapshot) { state, snapshot ->
            state.entries.firstOrNull { it.occurrenceId == snapshot?.occurrenceId }?.item?.toChatTrack()
        }.stateIn(uiScope, SharingStarted.Eagerly, null)

    override val appForeground: StateFlow<Boolean> = host.appActive

    override fun formatTime(timestampMs: Long): String =
        chatClockFormat.stringFromDate(NSDate.dateWithTimeIntervalSince1970(timestampMs / 1000.0))

    override fun copyText(text: String): Boolean {
        copyToIosClipboard(text)
        return true
    }

    override fun playShared(track: TrackInfo): Boolean = graph.listenTogether.playSharedTrack(track)

    override fun openLink(url: String) {
        openIosUri(url, graph::report)
    }

    override fun back() {
        onBack()
    }
}

/** A member's or a channel's mark: the first letter of the name on a round tile, or the slot number for a preset. */
@Composable
internal fun IosLetterAvatar(label: String, modifier: Modifier) {
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

/** The chat's view of a queue item: the fields a room track carries, mapped as the desktop host maps them. */
private fun ContentItem.toChatTrack(): TrackInfo = TrackInfo(
    id = ref.id,
    title = title,
    artist = artists.joinToString(", ") { it.name }.ifEmpty { subtitle },
    album = album,
    duration = durationMs ?: 0L,
    thumbnail = artworkUri,
)

/** Local clock text for the chat's message times, such as 14:05. NSDateFormatter is safe to share once configured. */
private val chatClockFormat = NSDateFormatter().apply { dateFormat = "HH:mm" }
