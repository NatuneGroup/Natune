package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktop.telegram.TdlightClientSession
import moe.rukamori.archivetune.shared.data.download.DownloadKey
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentQuery
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.LibraryMutation
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.settings.TelegramBotForwardToChannelKey
import moe.rukamori.archivetune.shared.domain.settings.TelegramBotsKey
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBot
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotCodec
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotChatActions
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotChatNotice
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotChatUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotCommandUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotPromptButtonUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotPromptUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotTrackUi
import moe.rukamori.archivetune.shared.ui.screens.PlaylistChoiceUi
import moe.rukamori.archivetune.shared.ui.screens.TelegramBotPlaylistPickUi
import org.drinkless.tdlib.TdApi
import org.jetbrains.skia.Image as SkiaImage
import java.nio.file.Path
import java.util.Locale
import java.util.UUID

private const val BOT_REPLY_TIMEOUT_MS = 60_000L
private const val POST_REPLY_GRACE_MS = 5_000L
private const val REQUEST_TIMEOUT_MS = 30_000L
private const val DOWNLOAD_TIMEOUT_MS = 300_000L
private const val TELEGRAM_DOWNLOAD_PRIORITY = 32

internal class DesktopTelegramBotChatOwner(
    private val graph: DesktopSessionGraph,
    private val session: TdlightClientSession,
    private val botId: String,
    private val scope: CoroutineScope,
) {
    private val published = MutableStateFlow(
        TelegramBotChatUi(null, "", false, null, null, false, emptyList(), emptyList(), null),
    )
    val state: StateFlow<TelegramBotChatUi> = published.asStateFlow()

    private val tracks = LinkedHashMap<String, BotTrack>()
    private val prompts = LinkedHashMap<Int, TdApi.InlineKeyboardButton>()
    private var bot: TelegramBot? = null
    private var highestSeenMessageId = 0L
    private var sendJob: Job? = null
    private var active = true

    internal val thumbnails: Map<String, ImageBitmap> get() = thumbnailsMap
    private val thumbnailsMap = LinkedHashMap<String, ImageBitmap>()

    val actions = TelegramBotChatActions(
        back = {},
        input = { value -> published.update { it.copy(input = value) } },
        send = { value -> send(value) },
        choose = { button -> choose(button) },
        openLink = { url -> openDesktopUri(url, graph::report) },
        play = { track -> play(listOfNotNull(tracks[track.id]), 0) },
        playAll = { play(state.value.results.mapNotNull { tracks[it.id] }, 0) },
        download = { track -> tracks[track.id]?.let(::download) },
        addToPlaylist = { track -> tracks[track.id]?.let(::addToPlaylist) },
        playlistName = { name -> updatePlaylistPick { it.copy(newName = name, failed = false) } },
        choosePlaylist = { playlist -> choosePlaylist(playlist) },
        createPlaylist = { createPlaylist() },
        dismissPlaylistPick = { published.update { it.copy(playlistPick = null) } },
        dismissNotice = { published.update { it.copy(notice = null) } },
    )

    fun start() {
        scope.launch {
            bot = bots().firstOrNull { it.id == botId }
            published.update { it.copy(botUsername = bot?.username) }
            val resolved = ensureChatId() ?: return@launch published.update {
                it.copy(notice = TelegramBotChatNotice.ResolveFailed)
            }
            published.update {
                it.copy(advertisedCommands = fetchCommands(resolved.chatId).map { command ->
                    TelegramBotCommandUi(command.command, command.description)
                })
            }
        }
    }

    fun close() {
        active = false
        sendJob?.cancel()
        bot?.chatId?.takeIf { it != 0L }?.let(session::forgetChat)
    }

    private suspend fun ensureChatId(): TelegramBot? {
        val current = bot ?: return null
        if (current.chatId != 0L) return current
        val username = current.username.removePrefix("@").trim().lowercase(Locale.US)
        if (username.isEmpty()) return null
        val chat = runCatching {
            session.admittedRequest(
                "resolveTelegramBot",
                TdApi.SearchPublicChat(username),
                REQUEST_TIMEOUT_MS,
            ) as? TdApi.Chat
        }.getOrNull() ?: return null
        val private = chat.type as? TdApi.ChatTypePrivate ?: return null
        val user = runCatching {
            session.admittedRequest("resolveTelegramBotUser", TdApi.GetUser(private.userId), REQUEST_TIMEOUT_MS) as? TdApi.User
        }.getOrNull()
        if (user != null && user.type !is TdApi.UserTypeBot) return null
        val updated = current.copy(
            chatId = chat.id,
            title = user?.firstName?.takeIf(String::isNotBlank) ?: current.title,
        )
        val encoded = TelegramBotCodec.encode(bots().map { if (it.id == botId) updated else it })
        runCatching { graph.settings.edit { this[TelegramBotsKey] = encoded } }
        bot = updated
        return updated
    }

    private suspend fun fetchCommands(chatId: Long): List<TdApi.BotCommand> = runCatching {
        val commands = session.admittedRequest(
            "fetchTelegramBotCommands",
            TdApi.GetCommands(TdApi.BotCommandScopeChat(chatId), ""),
            REQUEST_TIMEOUT_MS,
        ) as? TdApi.BotCommands
        commands?.commands?.toList().orEmpty()
    }.getOrDefault(emptyList())

    private fun send(raw: String) {
        val text = raw.trim()
        if (text.isBlank() || published.value.sending) return
        sendJob = scope.launch {
            published.update {
                it.copy(
                    sending = true,
                    noReply = false,
                    notice = null,
                    results = emptyList(),
                    prompt = null,
                    pendingChoice = null,
                )
            }
            currentPrompt = null
            prompts.clear()
            try {
                val activeBot = ensureChatId()
                if (activeBot == null) {
                    published.update { it.copy(notice = TelegramBotChatNotice.ResolveFailed) }
                    return@launch
                }
                val sent = session.admittedRequest(
                    "sendTelegramBotMessage",
                    TdApi.SendMessage(
                        activeBot.chatId,
                        null,
                        null,
                        null,
                        null,
                        TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false),
                    ),
                    REQUEST_TIMEOUT_MS,
                ) as? TdApi.Message ?: throw IllegalStateException("send")
                highestSeenMessageId = sent.id
                collectAndApply(activeBot.chatId, sent.id, activeBot.title)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                published.update { it.copy(notice = TelegramBotChatNotice.SendFailed(failure.javaClass.simpleName)) }
            } finally {
                if (active) published.update { it.copy(sending = false) }
            }
        }
    }

    private fun choose(button: TelegramBotPromptButtonUi) {
        val actual = prompts[button.index] ?: return
        val prompt = currentPrompt ?: return
        val data = (actual.type as? TdApi.InlineKeyboardButtonTypeCallback)?.data ?: return
        if (published.value.sending) return
        sendJob = scope.launch {
            published.update { it.copy(pendingChoice = button.text, sending = true, noReply = false) }
            try {
                if (prompt.isCancelButton(actual)) {
                    currentPrompt = null
                    prompts.clear()
                    published.update { it.copy(prompt = null, pendingChoice = null, sending = false) }
                    return@launch
                }
                // Android swallows the callback answer itself and always opens the next collection window.
                runCatching {
                    session.admittedRequest(
                        "clickTelegramBotButton",
                        TdApi.GetCallbackQueryAnswer(prompt.chatId, prompt.messageId, TdApi.CallbackQueryPayloadData(data)),
                        REQUEST_TIMEOUT_MS,
                    )
                }
                collectAndApply(prompt.chatId, prompt.messageId, bot?.title.orEmpty())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                published.update { it.copy(notice = TelegramBotChatNotice.SendFailed(failure.javaClass.simpleName)) }
            } finally {
                if (active) published.update { it.copy(pendingChoice = null, sending = false) }
            }
        }
    }

    private var currentPrompt: BotPrompt? = null

    private fun updatePlaylistPick(edit: (TelegramBotPlaylistPickUi) -> TelegramBotPlaylistPickUi) {
        published.update { state -> state.playlistPick?.let { state.copy(playlistPick = edit(it)) } ?: state }
    }

    private suspend fun collectAndApply(chatId: Long, afterMessageId: Long, sourceTitle: String) {
        val replies = collectReplies(chatId, afterMessageId)
        replies.maxOfOrNull { reply ->
            when (reply) {
                is BotReply.Track -> reply.track.messageId
                is BotReply.Prompt -> reply.prompt.messageId
            }
        }?.let { highestSeenMessageId = maxOf(highestSeenMessageId, it) }
        val newTracks = replies.filterIsInstance<BotReply.Track>().map { it.track }
        val latestPrompt = replies.filterIsInstance<BotReply.Prompt>().lastOrNull()?.prompt
        when {
            newTracks.isNotEmpty() -> {
                persistTracks(newTracks, sourceTitle)
                currentPrompt = null
                prompts.clear()
                published.update { it.copy(noReply = false, prompt = null) }
            }
            latestPrompt != null -> {
                currentPrompt = latestPrompt
                prompts.clear()
                var index = 0
                val rows = latestPrompt.rows.map { row ->
                    row.map { actual ->
                        prompts[index] = actual
                        val url = (actual.type as? TdApi.InlineKeyboardButtonTypeUrl)?.url
                        TelegramBotPromptButtonUi(index++, actual.text, url)
                    }
                }
                published.update { it.copy(prompt = TelegramBotPromptUi(latestPrompt.text, rows)) }
            }
            currentPrompt == null -> published.update { it.copy(noReply = true) }
        }
    }

    private suspend fun collectReplies(chatId: Long, afterMessageId: Long): List<BotReply> {
        val messages = session.messagesForChat(chatId)
        val channel = Channel<BotReply>(Channel.UNLIMITED)
        val collector = scope.launch {
            messages.collect { message ->
                if (message.id > afterMessageId) message.toReply()?.let { channel.send(it) }
            }
        }
        try {
            val first = withTimeoutOrNull(BOT_REPLY_TIMEOUT_MS) { channel.receive() } ?: return emptyList()
            val replies = mutableListOf(first)
            var deadline = System.currentTimeMillis() + POST_REPLY_GRACE_MS
            while (true) {
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                val next = withTimeoutOrNull(remaining) { channel.receive() } ?: break
                replies += next
                deadline = System.currentTimeMillis() + POST_REPLY_GRACE_MS
            }
            return replies
        } finally {
            collector.cancel()
        }
    }

    private suspend fun persistTracks(newTracks: List<BotTrack>, sourceTitle: String) {
        val stored = newTracks.map { it.copy(sourceTitle = sourceTitle) }
        // Android persists the song rows first; the result list appears only after the write succeeds.
        graph.library.saveItems(stored.map { it.item(it.cachedArtworkUri()) })
        tracks.putAll(stored.associateBy { it.mediaId })
        stored.forEach { track ->
            track.thumbnailBytes?.let { bytes ->
                runCatching { thumbnailsMap[track.mediaId] = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }
            }
        }
        published.update { it.copy(results = stored.map(BotTrack::ui)) }
    }

    private suspend fun playlistContains(playlistId: String, mediaId: String): Boolean {
        val parent = ContentRef("local", ContentKind.PLAYLIST, playlistId)
        var continuation: String? = null
        do {
            val page = graph.library.page(ContentQuery(RoomLibraryRepository.PLAYLIST_ITEMS, parent = parent), continuation)
            if (page.items.any { it.item.ref.id == mediaId }) return true
            continuation = page.continuation
        } while (continuation != null)
        return false
    }

    private suspend fun maybeForward(track: BotTrack) {
        if (graph.settings.snapshots.value?.get(TelegramBotForwardToChannelKey) == false) return
        val rows = graph.libraryContent.playlistRows().first()
            .filter { it.item.ref.id.startsWith("LPtg") }
        for (row in rows) {
            val chatId = row.item.ref.id.removePrefix("LPtg").toLongOrNull() ?: continue
            if (!playlistContains(row.item.ref.id, track.mediaId)) continue
            val result = runCatching {
                session.admittedRequest(
                    "forwardTelegramBotTrack",
                    TdApi.ForwardMessages(chatId, null, track.chatId, longArrayOf(track.messageId), null, false, false),
                    REQUEST_TIMEOUT_MS,
                ) as? TdApi.Messages
            }
            published.update {
                it.copy(
                    notice = result.fold(
                        onSuccess = { TelegramBotChatNotice.Forwarded },
                        onFailure = { TelegramBotChatNotice.ForwardFailed(it.javaClass.simpleName) },
                    ),
                )
            }
        }
    }

    private fun play(selected: List<BotTrack>, startIndex: Int) {
        if (selected.isEmpty()) return
        val entries = selected.map { it.occurrence(it.cachedArtworkUri()) }
        scope.launch {
            runCatching { graph.queue.replace(entries, entries[startIndex.coerceIn(entries.indices)].occurrenceId, true) }
                .onFailure { graph.report("the Telegram track was not played: ${it.javaClass.simpleName}") }
        }
    }

    private fun download(track: BotTrack) {
        scope.launch {
            try {
                // TDLib's synchronous file request reads the same Telegram file playback does; committing the completed
                // file through the download index keeps this a user download rather than a replay cache entry.
                val file = session.admittedRequest(
                    "downloadTelegramBotTrack",
                    TdApi.DownloadFile(track.fileId, TELEGRAM_DOWNLOAD_PRIORITY, 0, 0, true),
                    DOWNLOAD_TIMEOUT_MS,
                ) as? TdApi.File ?: error("download")
                val path = file.local?.path?.takeIf(String::isNotBlank) ?: error("missing path")
                graph.downloadStore.importLocalFile(
                    key = DownloadKey(track.mediaId, AudioSourceType.YOUTUBE, "telegram", "telegram"),
                    source = Path.of(path),
                    song = track.item(track.cachedArtworkUri()),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                published.update { it.copy(notice = TelegramBotChatNotice.DownloadFailed) }
            }
        }
    }

    private fun addToPlaylist(track: BotTrack) {
        published.update { it.copy(playlistPick = TelegramBotPlaylistPickUi(track.ui, null)) }
        scope.launch {
            try {
                val playlists = graph.libraryContent.playlistRows().first()
                    .filter { it.editable }
                    .map { PlaylistChoiceUi(it.item.ref, it.item.title, it.songCount) }
                updatePlaylistPick { it.copy(playlists = playlists) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updatePlaylistPick { it.copy(playlists = emptyList(), failed = true) }
            }
        }
    }

    private fun choosePlaylist(playlist: PlaylistChoiceUi) {
        val pick = published.value.playlistPick ?: return
        if (pick.busy) return
        val track = tracks[pick.track.id] ?: return
        commitToPlaylist(track, playlist.ref.id)
    }

    private fun createPlaylist() {
        val pick = published.value.playlistPick ?: return
        val title = pick.newName.trim()
        val track = tracks[pick.track.id] ?: return
        if (title.isBlank() || pick.busy) return
        updatePlaylistPick { it.copy(busy = true, failed = false) }
        scope.launch {
            try {
                val playlistId = randomDesktopPlaylistId()
                graph.libraryContent.createPlaylist(playlistId, title)
                addThenForward(track, playlistId)
                published.update { it.copy(playlistPick = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updatePlaylistPick { it.copy(busy = false, failed = true) }
            }
        }
    }

    private fun commitToPlaylist(track: BotTrack, playlistId: String) {
        updatePlaylistPick { it.copy(busy = true, failed = false) }
        scope.launch {
            try {
                addThenForward(track, playlistId)
                published.update { it.copy(playlistPick = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                updatePlaylistPick { it.copy(busy = false, failed = true) }
            }
        }
    }

    private suspend fun addThenForward(track: BotTrack, playlistId: String) {
        graph.library.mutate(LibraryMutation.AddToPlaylist(playlistId, listOf(track.occurrence(track.cachedArtworkUri()))))
        maybeForward(track)
    }

    private fun BotTrack.cachedArtworkUri(): String? {
        val metadata = deriveTrackMetadata(title, artist, fileName)
        return graph.telegramTrackArtworkUri(
            thumbnailFileId,
            metadata.first,
            metadata.second,
            fileUniqueId.ifEmpty { "$chatId-$messageId" },
            thumbnailBytes,
        )
    }

    private suspend fun bots(): List<TelegramBot> =
        TelegramBotCodec.decode(graph.settings.snapshots.value?.get(TelegramBotsKey).orEmpty()) { UUID.randomUUID().toString() }
}

private data class BotPrompt(val chatId: Long, val messageId: Long, val text: String, val rows: List<List<TdApi.InlineKeyboardButton>>) {
    fun isCancelButton(button: TdApi.InlineKeyboardButton): Boolean {
        val text = button.text.lowercase(Locale.US)
        return text == "cancel" || text == "✕" || text == "x" || text.contains("cancel")
    }
}

private sealed interface BotReply {
    data class Track(val track: BotTrack) : BotReply
    data class Prompt(val prompt: BotPrompt) : BotReply
}

private data class BotTrack(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileUniqueId: String,
    val title: String,
    val artist: String?,
    val fileName: String,
    val mimeType: String,
    val durationSeconds: Int,
    val sizeBytes: Long,
    val dateSeconds: Int,
    val thumbnailBytes: ByteArray?,
    val thumbnailFileId: Int,
    val sourceTitle: String? = null,
) {
    val mediaId = TelegramTrackRef(chatId, messageId, fileId, fileUniqueId).toMediaId()
    private val lookupMetadata get() = deriveTrackMetadata(title, artist, fileName)
    val ui get() = TelegramBotTrackUi(mediaId, displayTitle(), artist ?: lookupMetadata.second)
    fun occurrence(artworkUri: String? = null) = ContentOccurrence("telegram:$chatId:$messageId", item(artworkUri))
    fun item(artworkUri: String? = telegramArtworkUri(thumbnailFileId, lookupMetadata.first, lookupMetadata.second)): ContentItem {
        val metadata = lookupMetadata
        val artistName = metadata.second ?: sourceTitle?.takeIf(String::isNotBlank) ?: "Telegram"
        return ContentItem(
            ref = ContentRef("telegram", ContentKind.SONG, mediaId),
            title = metadata.first,
            subtitle = artistName,
            artists = listOf(ContentCredit(artistName)),
            artworkUri = artworkUri,
            durationMs = durationSeconds.takeIf { it > 0 }?.let { it * 1000L },
        )
    }
    private fun displayTitle() = title.ifBlank { fileName.substringBeforeLast('.').ifBlank { fileName } }
}

private fun TdApi.Message.toReply(): BotReply? {
    val track = when (val content = content) {
        is TdApi.MessageAudio -> content.audio?.let { audio ->
            BotTrack(
                chatId,
                id,
                audio.audio?.id ?: return null,
                audio.audio?.remote?.uniqueId.orEmpty(),
                audio.title.orEmpty(),
                audio.performer?.takeIf(String::isNotBlank),
                audio.fileName.orEmpty(),
                audio.mimeType.orEmpty(),
                audio.duration,
                audio.audio?.size ?: 0L,
                date,
                audio.albumCoverMinithumbnail?.data,
                audio.albumCoverThumbnail?.file?.id ?: 0,
            )
        }
        is TdApi.MessageDocument -> content.document?.let { document ->
            val fileName = document.fileName.orEmpty()
            val mime = document.mimeType.orEmpty()
            if (!isAudioDocument(mime, fileName)) return null
            BotTrack(
                chatId,
                id,
                document.document?.id ?: return null,
                document.document?.remote?.uniqueId.orEmpty(),
                "",
                null,
                fileName,
                mime,
                0,
                document.document?.size ?: 0L,
                date,
                document.minithumbnail?.data,
                document.thumbnail?.file?.id ?: 0,
            )
        }
        else -> null
    }
    if (track != null) return BotReply.Track(track)
    val keyboard = replyMarkup as? TdApi.ReplyMarkupInlineKeyboard ?: return null
    val rows = keyboard.rows.map { row ->
        row.filter {
            it.text.isNotBlank() &&
                (it.type is TdApi.InlineKeyboardButtonTypeCallback || it.type is TdApi.InlineKeyboardButtonTypeUrl)
        }
    }.filter { it.isNotEmpty() }
    if (rows.isEmpty()) return null
    val text = (content as? TdApi.MessageText)?.text?.text.orEmpty()
    return BotReply.Prompt(BotPrompt(chatId, id, text, rows))
}

@Composable
internal fun desktopTelegramBotChatPage(graph: DesktopSessionGraph, botId: String): ArchiveGraphPage {
    val owner = remember(botId) { DesktopTelegramBotChatOwner(graph, graph.telegram, botId, graph.scope) }
    DisposableEffect(owner) {
        owner.start()
        onDispose { owner.close() }
    }
    val state by owner.state.collectAsState()
    val thumbs = owner.thumbnails
    return ArchiveGraphPage.TelegramBotChat(state, owner.actions) { track, modifier ->
        thumbs[track.id]?.let { Image(bitmap = it, contentDescription = track.title, modifier = modifier) }
            ?: DesktopListenTogetherAvatar(track.title, modifier)
    }
}