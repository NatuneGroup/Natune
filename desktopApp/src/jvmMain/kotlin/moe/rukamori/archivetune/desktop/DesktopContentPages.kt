package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.listentogether.TrackInfo
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountRequired
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellNavigation
import moe.rukamori.archivetune.shared.ui.screens.AccountContentType
import moe.rukamori.archivetune.shared.ui.screens.AccountUi
import moe.rukamori.archivetune.shared.ui.screens.commenttogether.CommentTogetherNativePorts

private const val ACCOUNT_PAGE_LIMIT = 25

/**
 * The account and Listen Together chat routes of the desktop graph: the signed-in YouTube library shelves, and the
 * room chat over the graph's one owner. A route whose content has no real source still falls back to the explicit
 * host boundary, never a fabricated empty surface.
 */
@Composable
internal fun desktopAccountPage(graph: DesktopSessionGraph): ArchiveGraphPage {
    var selected by remember { mutableStateOf(AccountContentType.PLAYLISTS) }
    var lists by remember { mutableStateOf<Map<AccountContentType, List<ContentItem>>?>(null) }
    var denied by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        denied = false
        lists = try {
            AccountContentType.entries.associateWith { type ->
                graph.readAccountSurface(type.toSurface())
            }
        } catch (required: ContentAccountRequired) {
            denied = true
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("the YouTube account page could not be loaded")
            null
        }
    }
    if (denied) return ArchiveGraphPage.Host
    val listsNow = lists
    return ArchiveGraphPage.Account(
        state = AccountUi(
            selected = selected,
            playlists = listsNow?.get(AccountContentType.PLAYLISTS),
            albums = listsNow?.get(AccountContentType.ALBUMS),
            artists = listsNow?.get(AccountContentType.ARTISTS),
        ),
        select = { selected = it },
        more = null,
    )
}

private fun AccountContentType.toSurface(): ContentSurface = when (this) {
    AccountContentType.PLAYLISTS -> ContentSurface.LIBRARY_PLAYLISTS
    AccountContentType.ALBUMS -> ContentSurface.LIBRARY_ALBUMS
    AccountContentType.ARTISTS -> ContentSurface.LIBRARY_ARTISTS
}

/** Every page of one account surface, bounded so a repeating continuation cannot loop the read forever. */
private suspend fun DesktopSessionGraph.readAccountSurface(surface: ContentSurface): List<ContentItem> {
    val items = mutableListOf<ContentItem>()
    var continuation: String? = null
    repeat(ACCOUNT_PAGE_LIMIT) {
        val page = youtubeContent.page(
            ContentRequest(provider = ContentProvider.YOUTUBE, surface = surface),
            continuation,
        )
        items += page.items.map { it.item }
        continuation = page.continuation ?: return items
    }
    return items
}

/**
 * The chat screen over the desktop Listen Together owner: the shared binding on the owner's thread, the same avatar
 * tile the room list uses, remote artwork for shared covers, and the shell's own navigation for leaving the chat.
 */
@Composable
internal fun desktopCommentTogetherPage(graph: DesktopSessionGraph): ArchiveGraphPage {
    val navigation = LocalShellNavigation.current
    val chatClock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val binding = remember(graph.listenTogether) {
        graph.listenTogether.commentTogether(
            object : CommentTogetherNativePorts {
                override val localCurrentTrack: StateFlow<TrackInfo?> =
                    combine(graph.queue.state, graph.player.snapshot) { state, snapshot: PlaybackSnapshot? ->
                        state.entries.firstOrNull { it.occurrenceId == snapshot?.occurrenceId }?.item?.toChatTrack()
                    }.stateIn(graph.scope, SharingStarted.Eagerly, null)

                // A desktop window is never backgrounded for the chat; the notification gate stays open.
                override val appForeground: StateFlow<Boolean> = MutableStateFlow(true)

                override fun formatTime(timestampMs: Long): String = synchronized(chatClock) {
                    chatClock.format(Date(timestampMs))
                }

                override fun copyText(text: String): Boolean {
                    copyToDesktopClipboard(text)
                    return true
                }

                override fun playShared(track: TrackInfo): Boolean {
                    graph.scope.launch {
                        val occurrence = track.toOccurrence()
                        graph.queue.insert(listOf(occurrence), UserQueuedKind.PLAY_NEXT)
                        graph.queue.select(occurrence.occurrenceId)
                    }
                    return true
                }

                override fun openLink(url: String) {
                    openDesktopUri(url, graph::report)
                }

                override fun back() {
                    navigation?.back()
                }
            },
        )
    }
    DisposableEffect(binding) { onDispose { binding.close() } }
    return ArchiveGraphPage.CommentTogether(
        owner = binding,
        avatar = { author, modifier -> DesktopListenTogetherAvatar(author.username, modifier) },
        thumbnail = { url, modifier -> DesktopRemoteArtwork(url, graph.artworkLoader, modifier) },
    )
}

private fun ContentItem.toChatTrack() = TrackInfo(
    id = ref.id,
    title = title,
    artist = artists.joinToString(", ") { it.name }.ifEmpty { subtitle },
    album = album,
    duration = durationMs ?: 0L,
    thumbnail = artworkUri,
)
