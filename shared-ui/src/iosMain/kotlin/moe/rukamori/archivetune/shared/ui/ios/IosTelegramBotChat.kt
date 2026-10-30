package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.data.platform.ios.IosShareSheet
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramClient
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramTdJson
import moe.rukamori.archivetune.shared.data.platform.ios.iosShareFileUrl
import moe.rukamori.archivetune.shared.data.telegram.TdJsonAudioTrack
import moe.rukamori.archivetune.shared.data.telegram.TdJsonBotReply
import moe.rukamori.archivetune.shared.data.telegram.TdJsonInlineButton
import moe.rukamori.archivetune.shared.data.telegram.TdJsonInlinePrompt
import moe.rukamori.archivetune.shared.data.telegram.TdJsonTelegramBotSession
import moe.rukamori.archivetune.shared.data.telegram.TelegramAudioMetadata
import moe.rukamori.archivetune.shared.data.telegram.telegramChannelChatId
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentQuery
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.LibraryMutation
import moe.rukamori.archivetune.shared.domain.settings.TelegramBotForwardToChannelKey
import moe.rukamori.archivetune.shared.domain.settings.TelegramBotsKey
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBot
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotCodec
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.PlaylistChoiceUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotChatActions
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotChatNotice
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotChatUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotCommandUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotPlaylistPickUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotPromptButtonUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotPromptUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotTrackUi
import platform.Foundation.NSUUID

/**
 * One saved bot's chat over the signed-in iOS client: it resolves the bot once and saves its chat id, sends text and
 * presses keyboard buttons, reads the bot's audio replies into the library, and plays, saves, adds or forwards them.
 * Every request waits for the account check first. [scope] is the page's UI scope, so the maps below see one thread.
 *
 * A bot track's Download fetches the whole file through TDLib and hands it to the system share sheet, whose Save to
 * Files is the iOS export path. This host has no download library a file could join.
 */
internal class IosTelegramBotChatOwner(
    private val graph: IosSessionGraph,
    private val client: IosTelegramClient,
    private val botId: String,
    private val scope: CoroutineScope,
    private val shareFile: suspend (path: String) -> Boolean?,
    private val openLink: (String) -> Unit,
) {
    private val session = TdJsonTelegramBotSession(IosTelegramTdJson(client))
    private val published = MutableStateFlow(
        TelegramBotChatUi(null, "", false, null, null, false, emptyList(), emptyList(), null),
    )
    val state: StateFlow<TelegramBotChatUi> = published.asStateFlow()

    private val thumbnailFlow = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, ImageBitmap>> = thumbnailFlow.asStateFlow()

    private val tracks = LinkedHashMap<String, BotTrack>()
    private val buttons = LinkedHashMap<Int, TdJsonInlineButton>()
    private var currentPrompt: TdJsonInlinePrompt? = null
    private var bot: TelegramBot? = null
    private var openedChatId = 0L
    private val jobs = mutableListOf<Job>()
    private var active = true

    val actions = TelegramBotChatActions(
        back = {},
        input = { value -> published.update { it.copy(input = value) } },
        send = ::send,
        choose = ::choose,
        openLink = { url -> openLink(url) },
        play = { track -> play(listOfNotNull(tracks[track.id])) },
        playAll = { play(published.value.results.mapNotNull { tracks[it.id] }) },
        download = { track -> tracks[track.id]?.let(::download) },
        addToPlaylist = { track -> tracks[track.id]?.let(::addToPlaylist) },
        playlistName = { name -> updatePlaylistPick { it.copy(newName = name, failed = false) } },
        choosePlaylist = ::choosePlaylist,
        createPlaylist = ::createPlaylist,
        dismissPlaylistPick = { published.update { it.copy(playlistPick = null) } },
        dismissNotice = { published.update { it.copy(notice = null) } },
    )

    fun start() {
        launchTracked {
            bot = bots().firstOrNull { it.id == botId }
            published.update { it.copy(botUsername = bot?.username) }
            val resolved = (if (iosTelegramAwaitAdmission(client)) ensureChatId() else null)
                ?: return@launchTracked published.update { it.copy(notice = TelegramBotChatNotice.ResolveFailed) }
            session.open(resolved.chatId)
            openedChatId = resolved.chatId
            val commands = session.commands(resolved.chatId).map { TelegramBotCommandUi(it.command, it.description) }
            published.update { it.copy(advertisedCommands = commands) }
        }
    }

    fun close() {
        active = false
        jobs.toList().forEach(Job::cancel)
        val chatId = openedChatId
        // Closing the chat must outlive the page scope, which is being cancelled with this owner.
        if (chatId != 0L) graph.scope.launch { session.close(chatId) }
    }

    private fun launchTracked(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(block = block).also { job ->
            jobs += job
            job.invokeOnCompletion { jobs.remove(job) }
        }

    /** The bot's chat id, resolving and saving it the first time. Null when the bot cannot be reached as a bot. */
    private suspend fun ensureChatId(): TelegramBot? {
        val current = bot ?: return null
        if (current.chatId != 0L) return current
        val resolved = try {
            session.resolveBot(current.username)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        val updated = current.copy(chatId = resolved.chatId, title = resolved.title ?: current.title)
        val encoded = TelegramBotCodec.encode(bots().map { if (it.id == botId) updated else it })
        try {
            graph.settings.edit { this[TelegramBotsKey] = encoded }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("the resolved Telegram bot was not saved: ${failure::class.simpleName}")
        }
        bot = updated
        return updated
    }

    private fun send(raw: String) {
        val text = raw.trim()
        if (text.isBlank() || published.value.sending) return
        launchTracked {
            published.update {
                it.copy(sending = true, noReply = false, notice = null, results = emptyList(), prompt = null, pendingChoice = null)
            }
            currentPrompt = null
            buttons.clear()
            try {
                val activeBot = if (iosTelegramAwaitAdmission(client)) ensureChatId() else null
                if (activeBot == null) {
                    published.update { it.copy(notice = TelegramBotChatNotice.ResolveFailed) }
                    return@launchTracked
                }
                val sentId = session.sendText(activeBot.chatId, text)
                apply(session.collectReplies(activeBot.chatId, sentId), activeBot.title)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                published.update { it.copy(notice = TelegramBotChatNotice.SendFailed(failure::class.simpleName.orEmpty())) }
            } finally {
                if (active) published.update { it.copy(sending = false) }
            }
        }
    }

    private fun choose(button: TelegramBotPromptButtonUi) {
        val actual = buttons[button.index] ?: return
        val prompt = currentPrompt ?: return
        if (actual.callbackData == null || published.value.sending) return
        launchTracked {
            published.update { it.copy(pendingChoice = button.text, sending = true, noReply = false) }
            try {
                if (prompt.isCancel(actual)) {
                    currentPrompt = null
                    buttons.clear()
                    published.update { it.copy(prompt = null) }
                    return@launchTracked
                }
                session.press(prompt, actual)
                apply(session.collectReplies(prompt.chatId, prompt.messageId), bot?.title ?: "")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                published.update { it.copy(notice = TelegramBotChatNotice.SendFailed(failure::class.simpleName.orEmpty())) }
            } finally {
                if (active) published.update { it.copy(pendingChoice = null, sending = false) }
            }
        }
    }

    private suspend fun apply(replies: List<TdJsonBotReply>, sourceTitle: String) {
        val newTracks = replies.filterIsInstance<TdJsonBotReply.Track>().map { BotTrack(it.track, sourceTitle) }
        val latestPrompt = replies.filterIsInstance<TdJsonBotReply.Prompt>().lastOrNull()?.prompt
        when {
            newTracks.isNotEmpty() -> {
                persistTracks(newTracks)
                currentPrompt = null
                buttons.clear()
                published.update { it.copy(noReply = false, prompt = null) }
            }
            latestPrompt != null -> {
                currentPrompt = latestPrompt
                buttons.clear()
                var index = 0
                val rows = latestPrompt.rows.map { row ->
                    row.map { actual ->
                        buttons[index] = actual
                        TelegramBotPromptButtonUi(index++, actual.text, actual.url)
                    }
                }
                published.update { it.copy(prompt = TelegramBotPromptUi(latestPrompt.text, rows)) }
            }
            currentPrompt == null -> published.update { it.copy(noReply = true) }
        }
    }

    private suspend fun persistTracks(newTracks: List<BotTrack>) {
        // As on Android, the song rows are saved first and the results appear only after the write succeeds.
        graph.library.saveItems(newTracks.map { it.audio.item(it.sourceTitle) })
        newTracks.forEach { tracks[it.audio.mediaId] = it }
        val decoded = newTracks.mapNotNull { track ->
            iosDecodeMinithumbnail(track.audio.minithumbnail)?.let { track.audio.mediaId to it }
        }
        if (decoded.isNotEmpty()) thumbnailFlow.update { it + decoded }
        published.update { it.copy(results = newTracks.map(BotTrack::ui)) }
    }

    private fun play(selected: List<BotTrack>) {
        if (selected.isEmpty()) return
        val entries = selected.map { it.audio.occurrence(it.sourceTitle) }
        launchTracked {
            try {
                graph.queue.replace(entries, entries.first().occurrenceId, true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("the Telegram track was not played: ${failure::class.simpleName}")
            }
        }
    }

    private fun download(track: BotTrack) {
        launchTracked {
            val exported = try {
                if (!iosTelegramAwaitAdmission(client)) {
                    false
                } else {
                    // A dismissed sheet is the user's choice, not a failure.
                    shareFile(session.download(track.audio)) != false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!exported) published.update { it.copy(notice = TelegramBotChatNotice.DownloadFailed) }
        }
    }

    private fun updatePlaylistPick(edit: (TelegramBotPlaylistPickUi) -> TelegramBotPlaylistPickUi) {
        published.update { state -> state.playlistPick?.let { state.copy(playlistPick = edit(it)) } ?: state }
    }

    private fun addToPlaylist(track: BotTrack) {
        published.update { it.copy(playlistPick = TelegramBotPlaylistPickUi(track.ui(), null)) }
        launchTracked {
            try {
                val playlists = graph.libraryContent.playlistRows().first()
                    .filter { it.editable }
                    .map { PlaylistChoiceUi(it.item.ref, it.item.title, it.songCount) }
                updatePlaylistPick { it.copy(playlists = playlists) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updatePlaylistPick { it.copy(playlists = emptyList(), failed = true) }
            }
        }
    }

    private fun choosePlaylist(playlist: PlaylistChoiceUi) {
        val pick = published.value.playlistPick ?: return
        if (pick.busy) return
        val track = tracks[pick.track.id] ?: return
        commit(track) { playlist.ref.id }
    }

    private fun createPlaylist() {
        val pick = published.value.playlistPick ?: return
        val title = pick.newName.trim()
        val track = tracks[pick.track.id] ?: return
        if (title.isBlank() || pick.busy) return
        commit(track) {
            val playlistId = newLocalPlaylistId()
            graph.libraryContent.createPlaylist(playlistId, title)
            playlistId
        }
    }

    private fun commit(track: BotTrack, target: suspend () -> String) {
        updatePlaylistPick { it.copy(busy = true, failed = false) }
        launchTracked {
            try {
                val playlistId = target()
                graph.library.mutate(LibraryMutation.AddToPlaylist(playlistId, listOf(track.audio.occurrence(track.sourceTitle))))
                published.update { it.copy(playlistPick = null) }
                maybeForward(track)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updatePlaylistPick { it.copy(busy = false, failed = true) }
            }
        }
    }

    /** Android copies a bot track into every synced channel playlist that holds it, unless the user turned that off. */
    private suspend fun maybeForward(track: BotTrack) {
        if (graph.settings.snapshots.value?.get(TelegramBotForwardToChannelKey) == false) return
        val channels = graph.libraryContent.playlistRows().first().mapNotNull { row ->
            telegramChannelChatId(row.item.ref.id)?.let { row.item.ref.id to it }
        }
        for ((playlistId, chatId) in channels) {
            if (!playlistContains(playlistId, track.audio.mediaId)) continue
            val notice = try {
                session.forward(chatId, track.audio)
                TelegramBotChatNotice.Forwarded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                TelegramBotChatNotice.ForwardFailed(failure::class.simpleName.orEmpty())
            }
            published.update { it.copy(notice = notice) }
        }
    }

    private suspend fun playlistContains(playlistId: String, mediaId: String): Boolean {
        val parent = ContentRef(LOCAL_PROVIDER, ContentKind.PLAYLIST, playlistId)
        var continuation: String? = null
        do {
            val page = graph.library.page(ContentQuery(RoomLibraryRepository.PLAYLIST_ITEMS, parent = parent), continuation)
            if (page.items.any { it.item.ref.id == mediaId }) return true
            continuation = page.continuation
        } while (continuation != null)
        return false
    }

    private fun bots(): List<TelegramBot> =
        TelegramBotCodec.decode(graph.settings.snapshots.value?.get(TelegramBotsKey).orEmpty()) { NSUUID().UUIDString }

    private class BotTrack(val audio: TdJsonAudioTrack, val sourceTitle: String) {
        fun ui(): TelegramBotTrackUi {
            val (title, artist) = TelegramAudioMetadata.derive(audio.title, audio.performer, audio.fileName)
            return TelegramBotTrackUi(audio.mediaId, title, artist)
        }
    }

    private companion object {
        const val LOCAL_PROVIDER = "local"
        const val PLAYLIST_ID_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

        /** The `LP` + 8 letters id Android's PlaylistEntity gives a new local playlist. */
        fun newLocalPlaylistId(random: Random = Random.Default): String =
            "LP" + (1..8).map { PLAYLIST_ID_LETTERS[random.nextInt(PLAYLIST_ID_LETTERS.length)] }.joinToString("")
    }
}

/**
 * The `telegram/bot/<botId>` page. Without a signed-in client it keeps the host boundary, as browse and the bot list
 * do. The client and bot id are the key, so another account or bot never reuses an owner.
 */
@Composable
internal fun iosTelegramBotChatPage(graph: IosSessionGraph, host: IosHost, botId: String): ArchiveGraphPage {
    val client = host.telegram.collectAsState().value ?: return ArchiveGraphPage.Host
    return key(client, botId) { iosTelegramBotChatMount(graph, host, client, botId) }
}

@Composable
private fun iosTelegramBotChatMount(
    graph: IosSessionGraph,
    host: IosHost,
    client: IosTelegramClient,
    botId: String,
): ArchiveGraphPage {
    val scope = rememberCoroutineScope()
    val owner = remember(client, botId, scope) {
        val sheet = IosShareSheet()
        IosTelegramBotChatOwner(
            graph = graph,
            client = client,
            botId = botId,
            scope = scope,
            shareFile = { path -> sheet.share(host.viewController, listOf(iosShareFileUrl(path))) },
            openLink = { url -> openIosUri(url, graph::report) },
        )
    }
    DisposableEffect(owner) {
        owner.start()
        onDispose { owner.close() }
    }
    val state by owner.state.collectAsState()
    val thumbnails by owner.thumbnails.collectAsState()
    return ArchiveGraphPage.TelegramBotChat(state, owner.actions) { track, modifier ->
        val bitmap = thumbnails[track.id]
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = track.title, modifier = modifier)
        } else {
            IosLetterAvatar(track.title, modifier)
        }
    }
}
