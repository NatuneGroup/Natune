package moe.rukamori.archivetune.shared.data.telegram

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef

/**
 * One TDLib JSON client, as a host that speaks the TDLib JSON interface exposes it. [request] sends one query, whose
 * `@type` names the function, and returns the reply object. A TDLib `error` reply or a missing reply throws.
 */
interface TdJsonRequester {
    suspend fun request(query: JsonObject, timeoutMs: Long): JsonObject
}

/** Sends the TDLib function [type] with the fields [build] adds. */
internal suspend fun TdJsonRequester.call(type: String, timeoutMs: Long, build: JsonObjectBuilder.() -> Unit = {}): JsonObject =
    request(
        buildJsonObject {
            put("@type", type)
            build()
        },
        timeoutMs,
    )

/** One audio message: a `messageAudio`, or a `messageDocument` whose file is audio. */
data class TdJsonAudioTrack(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileUniqueId: String,
    val title: String,
    val performer: String?,
    val fileName: String,
    val mimeType: String,
    val durationSeconds: Int,
    val sizeBytes: Long,
    val minithumbnail: ByteArray?,
    val thumbnailFileId: Int,
) {
    val mediaId: String get() = TelegramTrackRef(chatId, messageId, fileId, fileUniqueId).toMediaId()

    /** The occurrence id every host uses for a Telegram message, so a re-sync never adds the same message twice. */
    val occurrenceId: String get() = "telegram:$chatId:$messageId"

    val isLossless: Boolean get() = TelegramAudioMetadata.isLossless(mimeType, fileName)

    /** The song row for this message. A track with no artist tag or name falls back to [fallbackArtist]. */
    fun item(fallbackArtist: String, album: String? = null, artworkUri: String? = null): ContentItem {
        val (displayTitle, artist) = TelegramAudioMetadata.derive(title, performer, fileName)
        val artistName = artist ?: fallbackArtist.ifBlank { TELEGRAM_LABEL }
        return ContentItem(
            ref = ContentRef(TelegramTrackRef.SCHEME, ContentKind.SONG, mediaId),
            title = displayTitle,
            subtitle = artistName,
            artworkUri = artworkUri,
            durationMs = durationSeconds.takeIf { it > 0 }?.let { it * 1000L },
            album = album?.takeIf(String::isNotBlank),
            artists = listOf(ContentCredit(artistName)),
        )
    }

    fun occurrence(fallbackArtist: String, album: String? = null, artworkUri: String? = null): ContentOccurrence =
        ContentOccurrence(occurrenceId, item(fallbackArtist, album, artworkUri))

    // The thumbnail bytes are a payload, not part of the message identity.
    override fun equals(other: Any?): Boolean =
        other is TdJsonAudioTrack && other.chatId == chatId && other.messageId == messageId && other.fileId == fileId

    override fun hashCode(): Int = (chatId * 31 + messageId).hashCode() * 31 + fileId

    private companion object {
        /** The service name, shown where a channel or bot has no title of its own. */
        const val TELEGRAM_LABEL = "Telegram"
    }
}

/**
 * One inline keyboard button a bot sent. [callbackData] is TDLib's base64 payload, passed back unchanged when the
 * button is pressed. [url] is set for link buttons.
 */
data class TdJsonInlineButton(val text: String, val callbackData: String?, val url: String?)

/** A bot message that carries an inline keyboard and no audio. Rows keep the bot's grouping. */
data class TdJsonInlinePrompt(
    val chatId: Long,
    val messageId: Long,
    val text: String,
    val rows: List<List<TdJsonInlineButton>>,
) {
    /** Android treats any cancel-looking chip as closing the prompt locally instead of pressing it. */
    fun isCancel(button: TdJsonInlineButton): Boolean {
        val label = button.text.lowercase()
        return label == "x" || label == "\u2715" || label.contains("cancel")
    }
}

/** What one bot message means to the chat: an audio reply, or a keyboard to choose from. */
sealed interface TdJsonBotReply {
    val messageId: Long

    data class Track(val track: TdJsonAudioTrack) : TdJsonBotReply {
        override val messageId: Long get() = track.messageId
    }

    data class Prompt(val prompt: TdJsonInlinePrompt) : TdJsonBotReply {
        override val messageId: Long get() = prompt.messageId
    }
}

/** A slash command a bot registered, without the slash. */
data class TdJsonBotCommand(val command: String, val description: String)

/** Reads TDLib JSON `message` objects. Anything that is not an audio track or a usable keyboard reads as null. */
object TdJsonTelegramMessages {
    /** The message objects of a `messages` or `foundChatMessages` reply. TDLib may leave a null in the list. */
    fun messages(reply: JsonObject): List<JsonObject> =
        (reply["messages"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    fun audioTrack(message: JsonObject): TdJsonAudioTrack? {
        val chatId = message.tdLong("chat_id") ?: return null
        val messageId = message.tdLong("id") ?: return null
        val content = message.tdObject("content") ?: return null
        return when (content.tdText("@type")) {
            "messageAudio" -> {
                val audio = content.tdObject("audio") ?: return null
                val file = audio.tdObject("audio") ?: return null
                TdJsonAudioTrack(
                    chatId = chatId,
                    messageId = messageId,
                    fileId = file.tdInt("id")?.takeIf { it > 0 } ?: return null,
                    fileUniqueId = file.tdObject("remote")?.tdText("unique_id").orEmpty(),
                    title = audio.tdText("title").orEmpty(),
                    performer = audio.tdText("performer")?.takeIf(String::isNotBlank),
                    fileName = audio.tdText("file_name").orEmpty(),
                    mimeType = audio.tdText("mime_type").orEmpty(),
                    durationSeconds = audio.tdInt("duration") ?: 0,
                    sizeBytes = file.tdLong("size") ?: 0L,
                    minithumbnail = audio.tdObject("album_cover_minithumbnail")?.tdBytes("data"),
                    thumbnailFileId = audio.tdObject("album_cover_thumbnail")?.tdObject("file")?.tdInt("id") ?: 0,
                )
            }
            "messageDocument" -> {
                val document = content.tdObject("document") ?: return null
                val file = document.tdObject("document") ?: return null
                val fileName = document.tdText("file_name").orEmpty()
                val mimeType = document.tdText("mime_type").orEmpty()
                if (!TelegramAudioMetadata.isAudioDocument(mimeType, fileName)) return null
                TdJsonAudioTrack(
                    chatId = chatId,
                    messageId = messageId,
                    fileId = file.tdInt("id")?.takeIf { it > 0 } ?: return null,
                    fileUniqueId = file.tdObject("remote")?.tdText("unique_id").orEmpty(),
                    title = "",
                    performer = null,
                    fileName = fileName,
                    mimeType = mimeType,
                    durationSeconds = 0,
                    sizeBytes = file.tdLong("size") ?: 0L,
                    minithumbnail = document.tdObject("minithumbnail")?.tdBytes("data"),
                    thumbnailFileId = document.tdObject("thumbnail")?.tdObject("file")?.tdInt("id") ?: 0,
                )
            }
            else -> null
        }
    }

    /**
     * A bot message as the chat reads it. Audio wins; otherwise an inline keyboard with at least one callback or link
     * button is a prompt. Other button kinds (login, games, payments) cannot be answered here, so they are left out.
     */
    fun botReply(message: JsonObject): TdJsonBotReply? {
        audioTrack(message)?.let { return TdJsonBotReply.Track(it) }
        val markup = message.tdObject("reply_markup") ?: return null
        if (markup.tdText("@type") != "replyMarkupInlineKeyboard") return null
        val rows = (markup["rows"] as? JsonArray).orEmpty().mapNotNull { row ->
            (row as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::inlineButton) }?.takeIf { it.isNotEmpty() }
        }
        if (rows.isEmpty()) return null
        val content = message.tdObject("content")
        val text = if (content?.tdText("@type") == "messageText") {
            content.tdObject("text")?.tdText("text").orEmpty()
        } else {
            content?.tdObject("caption")?.tdText("text").orEmpty()
        }
        return TdJsonBotReply.Prompt(
            TdJsonInlinePrompt(
                chatId = message.tdLong("chat_id") ?: return null,
                messageId = message.tdLong("id") ?: return null,
                text = text,
                rows = rows,
            ),
        )
    }

    private fun inlineButton(button: JsonObject): TdJsonInlineButton? {
        val text = button.tdText("text")?.takeIf(String::isNotBlank) ?: return null
        val type = button.tdObject("type") ?: return null
        return when (type.tdText("@type")) {
            "inlineKeyboardButtonTypeCallback" -> TdJsonInlineButton(text, type.tdText("data").orEmpty(), null)
            "inlineKeyboardButtonTypeUrl" -> type.tdText("url")?.let { TdJsonInlineButton(text, null, it) }
            else -> null
        }
    }
}

internal fun JsonObject.tdText(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.tdObject(key: String): JsonObject? = this[key] as? JsonObject

/** TDLib writes int64 values as JSON strings and int53 values as numbers, so both forms are read. */
internal fun JsonObject.tdLong(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

internal fun JsonObject.tdInt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.tdFlag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

/** TDLib JSON writes `bytes` fields as base64. An unreadable payload is null, never a failed message. */
@OptIn(ExperimentalEncodingApi::class)
internal fun JsonObject.tdBytes(key: String): ByteArray? =
    tdText(key)?.let { encoded -> runCatching { Base64.Default.decode(encoded) }.getOrNull() }
