package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.listentogether.ChatSongPickerOwner
import moe.rukamori.archivetune.shared.data.listentogether.TrackInfo
import moe.rukamori.archivetune.shared.data.platform.ios.IosListenTogetherAvatar
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSettingsDefaults
import moe.rukamori.archivetune.shared.domain.settings.commitListenTogetherAvatar
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ChatSongPicker
import moe.rukamori.archivetune.shared.ui.screens.ChatSongPickerActions
import moe.rukamori.archivetune.shared.ui.screens.ChatSongPickerUi
import moe.rukamori.archivetune.shared.ui.screens.ListenTogetherActions
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
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
 * The Listen Together room page over the graph's one owner. The chat and the settings open their routes. Suggest a song
 * opens the shared song picker as a sheet over the room; a pick goes to the host as a suggestion. Back is the root's
 * back, the same as the system gesture. The invite goes out through the iOS share sheet.
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
    val sent = stringResource(Res.string.ios_social_suggestion_sent)
    val notGuest = stringResource(Res.string.ios_social_suggestion_guest_only)
    val uiScope = rememberCoroutineScope()
    var suggesting by remember { mutableStateOf(false) }
    val picker = remember(owner, uiScope, sent, notGuest) {
        owner.suggestionPicker(uiScope) { delivered ->
            suggesting = false
            owner.showMessage(if (delivered) sent else notGuest)
        }
    }
    DisposableEffect(picker) { onDispose { picker.close() } }
    if (suggesting) {
        IosSuggestionPicker(graph, picker) {
            picker.close()
            suggesting = false
        }
    }
    val actions = remember(owner, host, back, openRoute) {
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
            suggestSong = { suggesting = true },
            settings = { openRoute(IosListenTogetherSettingsRoute) },
        )
    }
    return ArchiveGraphPage.ListenTogether(state, actions, avatar = { member, modifier ->
        IosLetterAvatar(member.name, modifier)
    })
}

/**
 * Settings > Listen Together over the same owner, so a blocked user released here is released in the live client too.
 * The custom member picture is picked from the photo library over [host]'s view controller, stored downscaled, chosen
 * as the avatar and sent to the room at once, as Android does. Without a [host] the row reports [unavailable].
 */
@Composable
internal fun IosListenTogetherSettingsPage(
    owner: IosListenTogetherOwner,
    controller: SettingsController,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
    unavailable: () -> Unit,
    scrollTo: String?,
    host: IosHost? = null,
) {
    val role by owner.settingsRole.collectAsState()
    val logs by owner.logs.collectAsState()
    val scope = rememberCoroutineScope()
    var customPicture by remember { mutableStateOf<ImageBitmap?>(null) }
    var pictureVersion by remember { mutableStateOf(0) }
    LaunchedEffect(pictureVersion) { customPicture = iosDecodeMinithumbnail(IosListenTogetherAvatar.load()) }
    val pickCustomAvatar: () -> Unit = {
        if (host == null) {
            unavailable()
        } else {
            scope.launch {
                val image = IosListenTogetherAvatar.pick(host.viewController) ?: return@launch
                if (!IosListenTogetherAvatar.save(image)) {
                    onNotice(SettingsNotice.Message(Res.string.ios_social_avatar_failed))
                    return@launch
                }
                pictureVersion++
                try {
                    controller.edit { commitListenTogetherAvatar(ListenTogetherSettingsDefaults.CUSTOM_AVATAR_INDEX) }
                    // Members already in the room see the new picture without waiting for a rejoin.
                    owner.chat.broadcastCustomAvatar()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onFailure()
                }
            }
        }
    }
    ListenTogetherSettings(
        controller = controller,
        servers = owner.servers,
        role = role,
        logs = logs,
        onPickCustomAvatar = pickCustomAvatar,
        onUnblockUser = owner.client::unblockUser,
        onClearLogs = owner::clearLogs,
        onCopyLogs = ::copyToIosClipboard,
        onNotice = onNotice,
        onFailure = onFailure,
        scrollTo = scrollTo,
        avatar = { index, modifier ->
            val picture = customPicture
            if (index == ListenTogetherSettingsDefaults.CUSTOM_AVATAR_INDEX && picture != null) {
                Image(bitmap = picture, contentDescription = null, modifier = modifier.clip(CircleShape))
            } else {
                IosLetterAvatar("${index + 1}", modifier)
            }
        },
    )
}

/** The shared song picker as the room's suggestion sheet. It searches songs only, and there is no now-playing row. */
@Composable
private fun IosSuggestionPicker(graph: IosSessionGraph, picker: ChatSongPickerOwner, dismiss: () -> Unit) {
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
        artwork = { item, modifier -> IosRemoteArtwork(item.artworkUri, graph.artworkLoader, modifier) },
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
