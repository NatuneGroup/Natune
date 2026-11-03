package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import moe.rukamori.archivetune.shared.data.listentogether.ChatSongPickerOwner
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherChatHistoryStore
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherChatOwner
import moe.rukamori.archivetune.shared.data.listentogether.MessageFormat
import moe.rukamori.archivetune.shared.data.listentogether.TrackInfo
import moe.rukamori.archivetune.shared.data.repository.ContentRepositoryRouter
import moe.rukamori.archivetune.shared.data.repository.TypedRoomLibraryRepository
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherChatHistoryKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.commenttogether.CommentTogetherNativePorts
import moe.rukamori.archivetune.shared.ui.screens.commenttogether.CommentTogetherOwnerBinding

/**
 * The chat history document in the same vault-routed logical store the reconnect session already uses, keyed by
 * [ListenTogetherChatHistoryKey]. A refused write is logged and dropped rather than retried into a second location.
 */
private class DesktopListenTogetherChatHistoryStore(
    private val settings: SettingsStore,
    private val report: (String) -> Unit,
) : ListenTogetherChatHistoryStore {
    override suspend fun read(): String? = settings.snapshots.value?.get(ListenTogetherChatHistoryKey)

    override suspend fun write(document: String?) {
        try {
            // Setting the key to null removes it, the same remove the editor applies everywhere else.
            settings.edit { this[ListenTogetherChatHistoryKey] = document }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("Listen Together chat history could not be saved")
        }
    }
}

/**
 * The desktop native ports for the comment-together binding. Everything reports a real outcome: the clipboard write
 * logs its own failure, the shared track goes through the room's own rules, and a link the OS refuses is reported by
 * [openDesktopUri] the same as every other external open.
 */
private class DesktopCommentTogetherPorts(
    private val graph: DesktopSessionGraph,
    private val onBack: () -> Unit,
) : CommentTogetherNativePorts {
    override val localCurrentTrack: StateFlow<TrackInfo?> get() = graph.listenTogether.localCurrentTrack

    // A desktop window is foreground while it exists; this host has no background chat notification path to gate.
    override val appForeground: StateFlow<Boolean> = MutableStateFlow(true)

    private val clock = SimpleDateFormat("HH:mm")

    override fun formatTime(timestampMs: Long): String = synchronized(clock) { clock.format(Date(timestampMs)) }

    override fun copyText(text: String): Boolean {
        copyToDesktopClipboard(text)
        return true
    }

    override fun playShared(track: TrackInfo): Boolean = graph.listenTogether.playSharedTrack(track)

    override fun openLink(url: String) {
        openDesktopUri(url, graph::report)
    }

    override fun back() = onBack()
}

private class DesktopCommentTogetherMount(
    private val chat: ListenTogetherChatOwner,
    private val songPicker: ChatSongPickerOwner,
    val binding: CommentTogetherOwnerBinding,
) {
    fun close() {
        // The binding stops first so no projection publishes after the owners' flows shut down.
        binding.close()
        chat.close()
        songPicker.close()
    }
}

/**
 * The Listen Together room chat over the graph's owners. Chat rides only on the JSON wire protocol the client
 * negotiated with its server; on the protobuf host the binding's [serverChatSupported] is false and the screen says
 * chat is unsupported there instead of offering dead controls. The owners and the binding live exactly as long as the
 * route stays on the stack and are closed when it leaves.
 */
@Composable
internal fun desktopListenTogetherChatPage(graph: DesktopSessionGraph, back: () -> Unit): ArchiveGraphPage {
    val owner = graph.listenTogether
    val mount = remember {
        val chat = ListenTogetherChatOwner(
            client = owner.client,
            scope = graph.scope,
            history = DesktopListenTogetherChatHistoryStore(graph.settings, graph::report),
            localAvatarBytes = { owner.localAvatarBytes() },
            nowMs = System::currentTimeMillis,
            onDiagnostic = graph::report,
        )
        val songPicker = ChatSongPickerOwner(
            content = ContentRepositoryRouter(listOf(TypedRoomLibraryRepository(graph.library), graph.youtubeContent)),
            scope = graph.scope,
            onShare = { track -> chat.shareTrack(track) },
        )
        val chatSupported = owner.client.protocol
            .map { it == MessageFormat.JSON }
            .stateIn(graph.scope, SharingStarted.Eagerly, owner.client.protocol.value == MessageFormat.JSON)
        DesktopCommentTogetherMount(
            chat = chat,
            songPicker = songPicker,
            binding = CommentTogetherOwnerBinding(
                scope = graph.scope,
                client = owner.client,
                chat = chat,
                songPicker = songPicker,
                serverChatSupported = chatSupported,
                native = DesktopCommentTogetherPorts(graph, back),
            ),
        )
    }
    DisposableEffect(mount) {
        onDispose { mount.close() }
    }
    return ArchiveGraphPage.CommentTogether(
        owner = mount.binding,
        avatar = { author, modifier -> DesktopListenTogetherAvatar(author.username, modifier) },
        thumbnail = { uri, modifier -> DesktopRemoteArtwork(uri, graph.artworkLoader, modifier) },
    )
}
