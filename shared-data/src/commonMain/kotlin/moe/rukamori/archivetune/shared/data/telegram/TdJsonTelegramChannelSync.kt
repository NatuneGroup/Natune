package moe.rukamori.archivetune.shared.data.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentQuery
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.telegram.TelegramChannelLibrary

/** The local playlist id a channel materialises as, the same `LPtg<chatId>` id Android and desktop use. */
fun telegramChannelPlaylistId(chatId: Long): String = "LPtg$chatId"

/** The chat id behind an `LPtg<chatId>` playlist id, or null for any other playlist. */
fun telegramChannelChatId(playlistId: String): Long? =
    playlistId.takeIf { it.startsWith(CHANNEL_PLAYLIST_PREFIX) }?.removePrefix(CHANNEL_PLAYLIST_PREFIX)?.toLongOrNull()

private const val CHANNEL_PLAYLIST_PREFIX = "LPtg"

/**
 * Channel playlists over a TDLib JSON client: a channel opens as its `LPtg<chatId>` local playlist, and a sync adds
 * every audio message the channel holds that the playlist does not have yet. Songs already in the playlist, and songs
 * the user removed and re-added by hand, keep their place, because only new occurrence ids are appended.
 *
 * [ready] waits for a verified account, and a sync without one does nothing. Syncs run on [scope]; a second sync of a
 * channel that is still syncing is ignored.
 */
class TdJsonTelegramChannelSync(
    private val td: TdJsonRequester,
    private val library: RoomLibraryRepository,
    private val scope: CoroutineScope,
    private val ready: suspend () -> Boolean,
    private val report: (String) -> Unit = {},
) : TelegramChannelLibrary {
    private val inFlight = mutableSetOf<Long>()
    private val inFlightLock = Mutex()

    override suspend fun ensurePlaylist(chatId: Long, title: String): String {
        val playlistId = telegramChannelPlaylistId(chatId)
        val exists = try {
            library.item(ContentRef(LOCAL_PROVIDER, ContentKind.PLAYLIST, playlistId))
            true
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!exists) library.createPlaylist(playlistId, title.ifBlank { playlistId })
        return playlistId
    }

    override fun startSync(chatId: Long, title: String, losslessOnly: Boolean) {
        scope.launch {
            val claimed = inFlightLock.withLock { inFlight.add(chatId) }
            if (!claimed) return@launch
            try {
                sync(chatId, title, losslessOnly)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("Telegram channel sync failed: ${failure::class.simpleName}")
            } finally {
                inFlightLock.withLock { inFlight.remove(chatId) }
            }
        }
    }

    private suspend fun sync(chatId: Long, title: String, losslessOnly: Boolean) {
        val playlistId = ensurePlaylist(chatId, title)
        if (!ready()) return
        runCatchingNonCancel { td.call("openChat", REQUEST_TIMEOUT_MS) { put("chat_id", chatId) } }
        primeHistory(chatId)
        delay(OPEN_CHAT_SETTLE_MS)
        val tracks = LinkedHashMap<Long, TdJsonAudioTrack>()
        for (filter in SEARCH_FILTERS) {
            var fromMessageId = 0L
            while (true) {
                val page = searchPage(chatId, fromMessageId, filter)
                page.tracks.forEach { track ->
                    if (!losslessOnly || track.isLossless) tracks.getOrPut(track.messageId) { track }
                }
                val next = page.nextFromMessageId ?: break
                if (next == 0L || next == fromMessageId || page.messageCount < PAGE_LIMIT) break
                fromMessageId = next
            }
        }
        val existing = existingOccurrenceIds(playlistId)
        val additions = tracks.values
            .map { it.occurrence(fallbackArtist = title, album = title) }
            .filter { it.occurrenceId !in existing }
            .distinctBy { it.occurrenceId }
        if (additions.isNotEmpty()) library.appendImported(playlistId, additions, setOf(TELEGRAM_TAG))
        runCatchingNonCancel { td.call("closeChat", REQUEST_TIMEOUT_MS) { put("chat_id", chatId) } }
    }

    /** A channel that was never opened has no local history, and searches on it answer empty until one page is read. */
    private suspend fun primeHistory(chatId: Long) {
        repeat(HISTORY_PRIME_ATTEMPTS) {
            val page = runCatchingNonCancel {
                td.call("getChatHistory", REQUEST_TIMEOUT_MS) {
                    put("chat_id", chatId)
                    put("from_message_id", 0)
                    put("offset", 0)
                    put("limit", HISTORY_PRIME_LIMIT)
                    put("only_local", false)
                }
            }
            if (page != null && TdJsonTelegramMessages.messages(page).isNotEmpty()) return
            delay(HISTORY_PRIME_DELAY_MS)
        }
    }

    private class SearchPage(val tracks: List<TdJsonAudioTrack>, val messageCount: Int, val nextFromMessageId: Long?)

    private suspend fun searchPage(chatId: Long, fromMessageId: Long, filter: String): SearchPage {
        repeat(FETCH_RETRY_COUNT) { attempt ->
            val found = runCatchingNonCancel {
                td.call("searchChatMessages", REQUEST_TIMEOUT_MS) {
                    put("chat_id", chatId)
                    put("query", "")
                    put("from_message_id", fromMessageId)
                    put("offset", 0)
                    put("limit", PAGE_LIMIT)
                    putJsonObject("filter") { put("@type", filter) }
                }
            }
            val messages = found?.let(TdJsonTelegramMessages::messages).orEmpty()
            // A first page that comes back empty is often TDLib still loading the channel, so it is asked again.
            if (messages.isNotEmpty() || fromMessageId != 0L || attempt == FETCH_RETRY_COUNT - 1) {
                return SearchPage(
                    tracks = messages.mapNotNull(TdJsonTelegramMessages::audioTrack),
                    messageCount = messages.size,
                    nextFromMessageId = found?.tdLong("next_from_message_id")
                        ?: messages.lastOrNull()?.tdLong("id"),
                )
            }
            delay(FETCH_RETRY_DELAY_MS)
        }
        return SearchPage(emptyList(), 0, null)
    }

    private suspend fun existingOccurrenceIds(playlistId: String): Set<String> {
        val parent = ContentRef(LOCAL_PROVIDER, ContentKind.PLAYLIST, playlistId)
        val ids = mutableSetOf<String>()
        var continuation: String? = null
        do {
            val page = library.page(ContentQuery(RoomLibraryRepository.PLAYLIST_ITEMS, parent = parent), continuation)
            page.items.mapTo(ids) { it.occurrenceId }
            continuation = page.continuation
        } while (continuation != null)
        return ids
    }

    private suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val LOCAL_PROVIDER = "local"
        const val TELEGRAM_TAG = "telegram"
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val HISTORY_PRIME_LIMIT = 50
        const val HISTORY_PRIME_ATTEMPTS = 8
        const val HISTORY_PRIME_DELAY_MS = 400L
        const val OPEN_CHAT_SETTLE_MS = 800L
        const val PAGE_LIMIT = 100
        const val FETCH_RETRY_COUNT = 5
        const val FETCH_RETRY_DELAY_MS = 600L
        val SEARCH_FILTERS = listOf("searchMessagesFilterAudio", "searchMessagesFilterDocument")
    }
}
