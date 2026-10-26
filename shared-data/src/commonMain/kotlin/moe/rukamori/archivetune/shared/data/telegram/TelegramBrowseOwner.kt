package moe.rukamori.archivetune.shared.data.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBrowseIssue
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBrowseState
import moe.rukamori.archivetune.shared.domain.telegram.TelegramChannelInfo
import moe.rukamori.archivetune.shared.domain.telegram.TelegramChannelLibrary
import moe.rukamori.archivetune.shared.domain.telegram.TelegramChannelSearch

/**
 * Owns the channel browse screen: search, then open a channel as a local playlist. Ported from the Android
 * TelegramBrowseScreen. A search replaces the results only when it finishes; a failed search answers with no
 * results and the failure detail, so the screen never reads as still loading. Opening creates or reuses the
 * `LPtg<chatId>` playlist, starts the background sync and reports the playlist id only on success.
 *
 * Work runs on the dispatcher of [scope]; the host passes its UI dispatcher. [close] cancels a search or an open
 * that is still running, as leaving the Android screen's composition does.
 */
class TelegramBrowseOwner(
    private val channelSearch: TelegramChannelSearch,
    private val channelLibrary: TelegramChannelLibrary,
    private val losslessOnly: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val published =
        MutableStateFlow(
            TelegramBrowseState(
                query = "",
                searching = false,
                searched = false,
                opening = false,
                results = emptyList(),
                issue = null,
            ),
        )
    val state: StateFlow<TelegramBrowseState> = published.asStateFlow()

    private var searchJob: Job? = null
    private var openJob: Job? = null

    fun changeQuery(text: String) {
        published.update { it.copy(query = text) }
    }

    /** Searches the trimmed query. Ignored for a blank query or while a search is already running. */
    fun search() {
        val current = published.value
        val trimmed = current.query.trim()
        if (trimmed.isEmpty() || current.searching) return
        published.update { it.copy(searching = true, issue = null) }
        searchJob =
            scope.launch {
                try {
                    val found = channelSearch.searchChannels(trimmed)
                    published.update { it.copy(searching = false, searched = true, results = found) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    published.update {
                        it.copy(
                            searching = false,
                            searched = true,
                            results = emptyList(),
                            issue = TelegramBrowseIssue.SearchFailed(failed.message ?: "?"),
                        )
                    }
                }
            }
    }

    /**
     * Creates or reuses the channel's playlist, starts its sync, then reports the playlist id so the host can
     * navigate to it. A failure sets [TelegramBrowseIssue.OpenFailed] and does not call [onOpened].
     */
    fun open(
        channel: TelegramChannelInfo,
        onOpened: (playlistId: String) -> Unit,
    ) {
        if (published.value.opening) return
        published.update { it.copy(opening = true, issue = null) }
        openJob =
            scope.launch {
                val playlistId =
                    try {
                        channelLibrary.ensurePlaylist(channel.chatId, channel.title)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failed: Exception) {
                        null
                    }
                if (playlistId == null) {
                    published.update { it.copy(opening = false, issue = TelegramBrowseIssue.OpenFailed) }
                    return@launch
                }
                published.update { it.copy(opening = false) }
                // The sync fills the playlist in the background; the playlist screen shows songs as they arrive.
                channelLibrary.startSync(channel.chatId, channel.title, losslessOnly())
                onOpened(playlistId)
            }
    }

    fun dismissIssue() {
        published.update { it.copy(issue = null) }
    }

    /** Cancels a search or an open that is still running. */
    fun close() {
        searchJob?.cancel()
        openJob?.cancel()
    }
}
