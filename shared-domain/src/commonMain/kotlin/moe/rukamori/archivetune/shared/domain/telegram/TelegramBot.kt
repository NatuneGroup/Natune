/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.telegram

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A user-saved Telegram bot. Ported from the Android `telegram/TelegramBots` model: the id is the stable client
 * id used by bot-chat routes, the chat id is resolved lazily and then kept, and equality follows the id.
 */
data class TelegramBot(
    /** Stable client-side id. Used as the route argument when opening a bot's chat. */
    val id: String,
    /** Bot username without the leading @, lower-cased to dedupe case variants. */
    val username: String,
    /** Telegram chat id of the resolved bot; 0 until first resolution. */
    val chatId: Long,
    /** Display title: the bot's first name, falling back to @username. */
    val title: String,
    /** Epoch millis when the user added the bot. */
    val addedAtMs: Long,
    /** Inline JPEG minithumbnail of the bot's profile photo. May be null. */
    val photoMinithumbnail: ByteArray? = null,
    /** Telegram file id of the bot's full-size profile photo; 0 when the bot has no photo. */
    val photoFileId: Int = 0,
) {
    val displayHandle: String
        get() = "@$username"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TelegramBot) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

/**
 * Encodes and decodes the bot list in the JSON array shape already persisted under the `telegramBots` preference.
 * Keys, optional fields and the base64 minithumbnail match the Android codec, so a list written by either host
 * reads on the other.
 */
object TelegramBotCodec {
    private const val KEY_ID = "id"
    private const val KEY_USERNAME = "username"
    private const val KEY_CHAT_ID = "chatId"
    private const val KEY_TITLE = "title"
    private const val KEY_ADDED_AT = "addedAtMs"
    private const val KEY_PHOTO_MINI = "photoMini"
    private const val KEY_PHOTO_FILE_ID = "photoFileId"

    @OptIn(ExperimentalEncodingApi::class)
    fun encode(bots: List<TelegramBot>): String =
        buildJsonArray {
            for (bot in bots) {
                add(
                    buildJsonObject {
                        put(KEY_ID, bot.id)
                        put(KEY_USERNAME, bot.username)
                        put(KEY_CHAT_ID, bot.chatId)
                        put(KEY_TITLE, bot.title)
                        put(KEY_ADDED_AT, bot.addedAtMs)
                        bot.photoMinithumbnail?.let { put(KEY_PHOTO_MINI, Base64.Default.encode(it)) }
                        if (bot.photoFileId != 0) put(KEY_PHOTO_FILE_ID, bot.photoFileId)
                    },
                )
            }
        }.toString()

    /**
     * Decodes [raw]. A blank or unreadable list is empty, and an entry without an id gets [newId]. Entries with a
     * blank username are dropped, as on Android.
     */
    fun decode(
        raw: String,
        newId: () -> String,
    ): List<TelegramBot> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = Json.parseToJsonElement(raw) as? JsonArray ?: return@runCatching emptyList()
            array
                .mapNotNull { element -> (element as? JsonObject)?.toBot(newId) }
                .filter { it.username.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun JsonObject.toBot(newId: () -> String): TelegramBot {
        val photoMini =
            text(KEY_PHOTO_MINI).takeIf { it.isNotBlank() }?.let { encoded ->
                runCatching { Base64.Default.decode(encoded) }.getOrNull()
            }
        val id = text(KEY_ID).ifBlank { newId() }
        return TelegramBot(
            id = id,
            username = text(KEY_USERNAME).trim(),
            chatId = (this[KEY_CHAT_ID] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            title = text(KEY_TITLE),
            addedAtMs = (this[KEY_ADDED_AT] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            photoMinithumbnail = photoMini,
            photoFileId = (this[KEY_PHOTO_FILE_ID] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
        )
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()
}

private val botLinkPattern =
    Regex("(?:https?://)?t(?:elegram)?\\.me/([A-Za-z][A-Za-z0-9_]{3,})", RegexOption.IGNORE_CASE)
private val botUsernamePattern = Regex("[A-Za-z][A-Za-z0-9_]{3,}")

/**
 * Parses raw user input ("@BotFather", "https://t.me/BotFather", "t.me/BotFather", "BotFather") to a clean
 * username, or null when the input is not a Telegram bot reference. Bots have no invite links, so only the public
 * @-handle forms are accepted, as on Android.
 */
fun parseTelegramBotUsername(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    botLinkPattern.find(trimmed)?.let { return it.groupValues[1].lowercase() }
    if (trimmed.startsWith("@")) {
        return trimmed.removePrefix("@").takeIf { botUsernamePattern.matches(it) }?.lowercase()
    }
    return trimmed.takeIf { botUsernamePattern.matches(it) }?.lowercase()
}
