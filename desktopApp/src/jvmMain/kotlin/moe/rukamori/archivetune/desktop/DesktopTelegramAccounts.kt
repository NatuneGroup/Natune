package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import moe.rukamori.archivetune.desktop.telegram.TdlightClientSession
import moe.rukamori.archivetune.desktop.telegram.TelegramNativeAdmission
import moe.rukamori.archivetune.shared.data.telegram.TelegramBotsOwner
import moe.rukamori.archivetune.shared.data.telegram.TelegramBrowseOwner
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentQuery
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.domain.settings.TelegramBotForwardToChannelKey
import moe.rukamori.archivetune.shared.domain.settings.TelegramBotsKey
import moe.rukamori.archivetune.shared.domain.settings.TelegramLosslessOnlyKey
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotDirectory
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotProfile
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotStore
import moe.rukamori.archivetune.shared.domain.telegram.TelegramChannelInfo
import moe.rukamori.archivetune.shared.domain.telegram.TelegramChannelLibrary
import moe.rukamori.archivetune.shared.domain.telegram.TelegramChannelSearch
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.telegram.TelegramBotsOwnerBinding
import moe.rukamori.archivetune.shared.ui.screens.telegram.TelegramBrowseOwnerBinding
import moe.rukamori.archivetune.shared.ui.screens.TelegramChannelUi
import org.jetbrains.skia.Image as SkiaImage

private const val LOCAL_CHAT_SEARCH_LIMIT = 30
private const val READY_TIMEOUT_MS = 15_000L
private const val HISTORY_PRIME_LIMIT = 50
private const val OPEN_CHAT_SETTLE_MS = 800L
private const val PAGE_FETCH_LIMIT = 100
private const val FETCH_RETRY_COUNT = 5
private const val FETCH_RETRY_DELAY_MS = 600L

private class TdlightChannelSearch(private val session: TdlightClientSession) : TelegramChannelSearch {
    override suspend fun searchChannels(query: String): List<TelegramChannelInfo> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val results = LinkedHashMap<Long, TelegramChannelInfo>()

        extractInviteLink(trimmed)?.let { link ->
            runCatching {
                val info = session.admittedRequest("checkTelegramInvite", TdApi.CheckChatInviteLink(link))
                if (info is TdApi.ChatInviteLinkInfo) {
                    val joinedChatId = runCatching {
                        (session.admittedRequest("joinTelegramInvite", TdApi.JoinChatByInviteLink(link))
                            as? TdApi.ChatJoinResultSuccess)?.chatId
                    }.getOrNull()
                    val chatId = joinedChatId ?: info.chatId
                    if (chatId != 0L) channelInfo(session, chatId)?.let { results[chatId] = it }
                }
            }
        }

        extractUsername(trimmed)?.let { username ->
            runCatching {
                val chat = session.admittedRequest("searchTelegramChat", TdApi.SearchPublicChat(username)) as? TdApi.Chat
                chat?.id?.let { chatId -> channelInfo(session, chatId)?.let { results[chatId] = it } }
            }
        }

        runCatching {
            val chats = session.admittedRequest("searchPublicChats", TdApi.SearchPublicChats(trimmed, null)) as? TdApi.Chats
            chats?.chatIds?.forEach { chatId ->
                runCatching { channelInfo(session, chatId) }.getOrNull()?.let { results.putIfAbsent(chatId, it) }
            }
        }

        runCatching {
            val chats = session.admittedRequest(
                "searchLocalChats",
                TdApi.SearchChats(trimmed, null, LOCAL_CHAT_SEARCH_LIMIT),
            ) as? TdApi.Chats
            chats?.chatIds?.forEach { chatId ->
                if (!results.containsKey(chatId)) {
                    runCatching { channelInfo(session, chatId) }.getOrNull()?.let { results[chatId] = it }
                }
            }
        }
        return results.values.toList()
    }

    private suspend fun channelInfo(session: TdlightClientSession, chatId: Long): TelegramChannelInfo? {
        val chat = session.admittedRequest("getTelegramChat", TdApi.GetChat(chatId)) as? TdApi.Chat ?: return null
        val type = chat.type as? TdApi.ChatTypeSupergroup ?: return null
        val supergroup = session.admittedRequest("getTelegramSupergroup", TdApi.GetSupergroup(type.supergroupId)) as? TdApi.Supergroup
        val username = supergroup?.usernames?.activeUsernames?.firstOrNull().orEmpty()
        return TelegramChannelInfo(
            chatId = chat.id,
            title = chat.title,
            username = username,
            memberCount = supergroup?.memberCount ?: 0,
            isBroadcast = type.isChannel,
            photoMinithumbnail = chat.photo?.minithumbnail?.data,
            photoFileId = chat.photo?.small?.id ?: 0,
        )
    }

    private fun extractInviteLink(query: String): String? = inviteLink(query)

    private fun extractUsername(query: String): String? = username(query)
}

private class TdlightBotDirectory(private val session: TdlightClientSession) : TelegramBotDirectory {
    override suspend fun resolve(username: String): TelegramBotProfile? {
        val cleaned = username.removePrefix("@").trim().lowercase()
        if (cleaned.isEmpty()) return null
        val chat = runCatching {
            session.admittedRequest("resolveTelegramBot", TdApi.SearchPublicChat(cleaned))
        }.getOrNull() as? TdApi.Chat ?: return null
        val type = chat.type as? TdApi.ChatTypePrivate ?: return null
        val user = runCatching {
            session.admittedRequest("getTelegramBotUser", TdApi.GetUser(type.userId))
        }.getOrNull() as? TdApi.User
        if (user != null && user.type !is TdApi.UserTypeBot) return null
        return TelegramBotProfile(
            chatId = chat.id,
            title = listOfNotNull(user?.firstName, user?.lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { null },
            photoMinithumbnail = chat.photo?.minithumbnail?.data,
            photoFileId = chat.photo?.small?.id ?: 0,
        )
    }
}

private class SettingsTelegramBotStore(private val graph: DesktopSessionGraph) : TelegramBotStore {
    override suspend fun readBots(): String = graph.settings.snapshots.value?.get(TelegramBotsKey).orEmpty()

    override suspend fun writeBots(encoded: String) {
        graph.settings.edit { this[TelegramBotsKey] = encoded }
    }

    override suspend fun readForwardToChannel(): Boolean =
        graph.settings.snapshots.value?.get(TelegramBotForwardToChannelKey) ?: true

    override suspend fun writeForwardToChannel(enabled: Boolean) {
        graph.settings.edit { this[TelegramBotForwardToChannelKey] = enabled }
    }
}

private data class TelegramSyncTrack(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileUniqueId: String,
    val title: String,
    val artist: String?,
    val durationSeconds: Int,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long,
    val thumbnailFileId: Int,
    val albumCoverMinithumbnail: ByteArray?,
) {
    val mediaId: String get() = TelegramTrackRef(chatId, messageId, fileId, fileUniqueId).toMediaId()
}

internal class TdlightChannelLibrary(
    private val graph: DesktopSessionGraph,
    private val session: TdlightClientSession,
) : TelegramChannelLibrary {
    private val inFlight = MutableStateFlow<Set<Long>>(emptySet())
    private val inFlightLock = Mutex()

    override suspend fun ensurePlaylist(chatId: Long, title: String): String {
        val playlistId = "LPtg$chatId"
        val ref = ContentRef("local", ContentKind.PLAYLIST, playlistId)
        val exists = try {
            graph.library.item(ref)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!exists) graph.library.createPlaylist(playlistId, title)
        return playlistId
    }

    override fun startSync(chatId: Long, title: String, losslessOnly: Boolean) {
        graph.scope.launch {
            inFlightLock.withLock {
                if (chatId in inFlight.value) return@launch
                inFlight.value = inFlight.value + chatId
            }
            try {
                syncChannel(chatId, title, losslessOnly)
            } finally {
                inFlightLock.withLock { inFlight.value = inFlight.value - chatId }
            }
        }
    }

    private suspend fun syncChannel(chatId: Long, title: String, losslessOnly: Boolean) {
        val playlistId = ensurePlaylist(chatId, title)
        withTimeoutOrNull(READY_TIMEOUT_MS) {
            session.admission.first { it is TelegramNativeAdmission.Admitted }
        } ?: return
        val tracks = LinkedHashMap<Long, TelegramSyncTrack>()
        runCatching { session.admittedRequest("openTelegramChannel", TdApi.OpenChat(chatId)) }
        primeChatHistory(chatId)
        delay(OPEN_CHAT_SETTLE_MS)

        val filters = listOf(TdApi.SearchMessagesFilterAudio(), TdApi.SearchMessagesFilterDocument())
        for (filter in filters) {
            var fromMessageId = 0L
            while (true) {
                val page = fetchAudioPage(chatId, fromMessageId, filter)
                page.forEach { message ->
                    val track = message.toTrack() ?: return@forEach
                    if (losslessOnly && !track.isLossless()) return@forEach
                    tracks.putIfAbsent(track.messageId, track)
                }
                fromMessageId = page.lastOrNull()?.id ?: break
                if (page.size < PAGE_FETCH_LIMIT) break
            }
        }

        val existing = existingOccurrenceIds(playlistId)
        val occurrences = tracks.values
            .asSequence()
            .map { it.toOccurrence(title) }
            .filter { it.occurrenceId !in existing }
            .distinctBy { it.occurrenceId }
            .toList()
        if (occurrences.isNotEmpty()) {
            graph.library.appendImported(playlistId, occurrences, setOf("telegram"))
        }
    }

    private suspend fun primeChatHistory(chatId: Long) {
        var fromMessageId = 0L
        repeat(8) {
            val messages = runCatching {
                session.admittedRequest(
                    "primeTelegramChannelHistory",
                    TdApi.GetChatHistory(chatId, fromMessageId, 0, HISTORY_PRIME_LIMIT, false),
                ) as? TdApi.Messages
            }.getOrNull()
            if (!messages?.messages.isNullOrEmpty()) return
            delay(400)
            fromMessageId = 0L
        }
    }

    private suspend fun fetchAudioPage(
        chatId: Long,
        fromMessageId: Long,
        filter: TdApi.SearchMessagesFilter,
    ): List<TdApi.Message> {
        repeat(FETCH_RETRY_COUNT) { attempt ->
            val found = runCatching {
                session.admittedRequest(
                    "fetchTelegramChannelPage",
                    TdApi.SearchChatMessages(chatId, null, "", null, fromMessageId, 0, PAGE_FETCH_LIMIT, filter),
                ) as? TdApi.FoundChatMessages
            }.getOrNull()
            val messages = found?.messages.orEmpty()
            if (messages.isNotEmpty() || fromMessageId != 0L || attempt == FETCH_RETRY_COUNT - 1) return messages.toList()
            delay(FETCH_RETRY_DELAY_MS)
        }
        return emptyList()
    }

    private suspend fun existingOccurrenceIds(playlistId: String): Set<String> {
        val parent = ContentRef("local", ContentKind.PLAYLIST, playlistId)
        val ids = mutableSetOf<String>()
        var continuation: String? = null
        do {
            val page = graph.library.page(ContentQuery(RoomLibraryRepository.PLAYLIST_ITEMS, parent = parent), continuation)
            page.items.mapTo(ids) { it.occurrenceId }
            continuation = page.continuation
        } while (continuation != null)
        return ids
    }

    private fun TdApi.Message.toTrack(): TelegramSyncTrack? = when (val messageContent = this.content) {
        is TdApi.MessageAudio -> messageContent.audio?.let { audio ->
            TelegramSyncTrack(
                chatId = chatId,
                messageId = id,
                fileId = audio.audio?.id ?: return null,
                fileUniqueId = audio.audio?.remote?.uniqueId.orEmpty(),
                title = audio.title.orEmpty(),
                artist = audio.performer?.takeIf { it.isNotBlank() },
                durationSeconds = audio.duration,
                mimeType = audio.mimeType.orEmpty(),
                fileName = audio.fileName.orEmpty(),
                sizeBytes = audio.audio?.size ?: 0L,
                thumbnailFileId = audio.albumCoverThumbnail?.file?.id ?: 0,
                albumCoverMinithumbnail = audio.albumCoverMinithumbnail?.data,
            )
        }
        is TdApi.MessageDocument -> messageContent.document?.let { document ->
            if (!isAudioDocument(document.mimeType.orEmpty(), document.fileName.orEmpty())) return null
            TelegramSyncTrack(
                chatId = chatId,
                messageId = id,
                fileId = document.document?.id ?: return null,
                fileUniqueId = document.document?.remote?.uniqueId.orEmpty(),
                title = document.fileName.orEmpty(),
                artist = null,
                durationSeconds = 0,
                mimeType = document.mimeType.orEmpty(),
                fileName = document.fileName.orEmpty(),
                sizeBytes = document.document?.size ?: 0L,
                thumbnailFileId = document.thumbnail?.file?.id ?: 0,
                albumCoverMinithumbnail = document.minithumbnail?.data,
            )
        }
        else -> null
    }

    private fun TelegramSyncTrack.toOccurrence(channelTitle: String): ContentOccurrence {
        val metadata = deriveTrackMetadata(title, artist, fileName)
        return ContentOccurrence(
            occurrenceId = "telegram:$chatId:$messageId",
            item = ContentItem(
                ref = ContentRef("telegram", ContentKind.SONG, mediaId),
                title = metadata.first,
                subtitle = metadata.second ?: channelTitle.ifBlank { "Telegram" },
                album = channelTitle.takeIf { it.isNotBlank() },
                artists = listOf(moe.rukamori.archivetune.shared.domain.repository.ContentCredit(metadata.second ?: channelTitle.ifBlank { "Telegram" })),
                artworkUri = graph.telegramTrackArtworkUri(
                    thumbnailFileId,
                    metadata.first,
                    metadata.second,
                    fileUniqueId.ifEmpty { "$chatId-$messageId" },
                    albumCoverMinithumbnail,
                ),
                durationMs = durationSeconds.takeIf { it > 0 }?.let { it * 1000L },
            ),
        )
    }

    private fun TelegramSyncTrack.isLossless(): Boolean = isLosslessAudio(mimeType, fileName)
}

private fun inviteLink(query: String): String? {
    val link = TELEGRAM_INVITE_REGEX.find(query.trim())?.groupValues?.get(1) ?: return null
    return if (link.startsWith("http")) link else "https://$link"
}

private fun username(query: String): String? {
    val trimmed = query.trim()
    val fromLink = TELEGRAM_USERNAME_LINK_REGEX.find(trimmed)?.groupValues?.get(1)
    if (fromLink != null) return fromLink
    if (trimmed.startsWith("@")) {
        return trimmed.removePrefix("@").takeIf { it.matches(TELEGRAM_USERNAME_VALUE_REGEX) }
    }
    return null
}

private val TELEGRAM_INVITE_REGEX =
    Regex("((?:https?://)?t(?:elegram)?\\.me/(?:\\+|joinchat/|add/)[A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
private val TELEGRAM_USERNAME_LINK_REGEX =
    Regex("(?:https?://)?t(?:elegram)?\\.me/([A-Za-z0-9_]{3,})", RegexOption.IGNORE_CASE)
private val TELEGRAM_USERNAME_VALUE_REGEX = Regex("[A-Za-z0-9_]{3,}")
private val LOSSLESS_MIME_TYPES = setOf(
    "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave",
    "audio/aiff", "audio/x-aiff", "audio/x-ape", "audio/x-monkeys-audio", "audio/x-wavpack",
    "audio/wavpack", "audio/x-tak", "audio/x-tta", "audio/dsd", "audio/x-dsd", "audio/x-dsf", "audio/alac",
)
private val LOSSLESS_EXTENSIONS = setOf("flac", "wav", "wave", "aiff", "aif", "ape", "alac", "wv", "tak", "tta", "dsf", "dff", "shn")
private val AUDIO_EXTENSIONS = LOSSLESS_EXTENSIONS + setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wma", "mka")
private val NOISE_SUFFIX_REGEX = Regex("\\((?:official|lyric|audio|video|hd|hq|visualizer)[^)]*\\)", RegexOption.IGNORE_CASE)
private val BRACKET_TAG_REGEX = Regex("\\[[^\\]]*\\]")
private val LEADING_TRACK_NUMBER_REGEX = Regex("^\\s*\\d{1,3}\\s*[.\\-]\\s*")
private val WHITESPACE_REGEX = Regex("\\s+")

internal fun fileExtension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase(Locale.US)
private fun isLosslessAudio(mimeType: String, fileName: String): Boolean {
    val mime = mimeType.trim().lowercase(Locale.US)
    return mime in LOSSLESS_MIME_TYPES || fileExtension(fileName) in LOSSLESS_EXTENSIONS
}
internal fun isAudioDocument(mimeType: String, fileName: String): Boolean =
    mimeType.trim().lowercase(Locale.US).startsWith("audio/") || fileExtension(fileName) in AUDIO_EXTENSIONS
internal fun cleanTrackName(raw: String): String =
    raw.replace(NOISE_SUFFIX_REGEX, " ")
        .replace(BRACKET_TAG_REGEX, " ")
        .replace(LEADING_TRACK_NUMBER_REGEX, "")
        .replace(WHITESPACE_REGEX, " ")
        .trim()
internal fun deriveTrackMetadata(tagTitle: String, tagPerformer: String?, fileName: String): Pair<String, String?> {
    val performer = tagPerformer?.trim()?.takeIf(String::isNotEmpty)
    if (tagTitle.isNotBlank()) return cleanTrackName(tagTitle).ifBlank { tagTitle.trim() } to performer
    val base = cleanTrackName(fileName.substringBeforeLast('.').ifBlank { fileName })
    if (performer == null) {
        for (separator in listOf(" - ", " – ", " — ")) {
            val index = base.indexOf(separator)
            if (index > 0 && index < base.length - separator.length) {
                val artist = base.substring(0, index).trim()
                val track = base.substring(index + separator.length).trim()
                if (artist.isNotEmpty() && track.isNotEmpty()) return track to artist
            }
        }
    }
    return base.ifBlank { fileName } to performer
}

private fun decodeThumbnail(bytes: ByteArray?): ImageBitmap? =
    bytes?.let { runCatching { SkiaImage.makeFromEncoded(it)?.toComposeImageBitmap() }.getOrNull() }

private fun formatMemberCount(count: Int): String = NumberFormat.getIntegerInstance().format(count)

@Composable
private fun telegramChannelAvatar(channel: TelegramChannelUi, channels: StateFlow<List<TelegramChannelInfo>>, modifier: Modifier) {
    val infos by channels.collectAsState()
    val bitmap = remember(infos, channel.chatId) {
        decodeThumbnail(infos.firstOrNull { it.chatId == channel.chatId }?.photoMinithumbnail)
    }
    if (bitmap != null) {
        androidx.compose.foundation.Image(bitmap = bitmap, contentDescription = channel.title, modifier = modifier)
    } else {
        DesktopListenTogetherAvatar(channel.title, modifier)
    }
}

@Composable
internal fun desktopTelegramBrowsePage(graph: DesktopSessionGraph, back: () -> Unit): ArchiveGraphPage {
    val search = remember { TdlightChannelSearch(graph.telegram) }
    val library = remember { TdlightChannelLibrary(graph, graph.telegram) }
    val owner = remember {
        TelegramBrowseOwner(
            channelSearch = search,
            channelLibrary = library,
            losslessOnly = { graph.settings.snapshots.value?.get(TelegramLosslessOnlyKey) ?: false },
            scope = graph.scope,
        )
    }
    // One binding for the page visit. Rebuilding it on recomposition would close a search or an open that is running.
    val binding = remember { TelegramBrowseOwnerBinding(owner, graph.scope, ::formatMemberCount, back) }
    DisposableEffect(binding) { onDispose { binding.close() } }
    return ArchiveGraphPage.TelegramBrowse(
        state = binding.state.collectAsState().value,
        actions = binding.actions,
        avatar = { channel, modifier -> telegramChannelAvatar(channel, binding.channels, modifier) },
    )
}

@Composable
internal fun desktopTelegramBotsPage(graph: DesktopSessionGraph, back: () -> Unit, openRoute: (String) -> Unit): ArchiveGraphPage {
    val directory = remember { TdlightBotDirectory(graph.telegram) }
    val store = remember { SettingsTelegramBotStore(graph) }
    val ready = remember {
        graph.telegram.admission
            .map { it is TelegramNativeAdmission.Admitted }
            .stateIn(
                graph.scope,
                SharingStarted.Eagerly,
                graph.telegram.admission.value is TelegramNativeAdmission.Admitted,
            )
    }
    val owner = remember {
        TelegramBotsOwner(
            store = store,
            directory = directory,
            ready = ready,
            scope = graph.scope,
            nowMs = { System.currentTimeMillis() },
            newId = { UUID.randomUUID().toString() },
        )
    }
    // One binding for the page visit, so a recomposition does not cancel a running add or the saved-list load.
    val binding = remember {
        TelegramBotsOwnerBinding(owner, graph.scope, back) { bot -> openRoute("telegram/bot/${bot.id}") }
    }
    DisposableEffect(binding) {
        val job: Job = graph.scope.launch { binding.load() }
        onDispose {
            job.cancel()
            binding.close()
        }
    }
    return ArchiveGraphPage.TelegramBots(binding.state.collectAsState().value, binding.actions)
}
