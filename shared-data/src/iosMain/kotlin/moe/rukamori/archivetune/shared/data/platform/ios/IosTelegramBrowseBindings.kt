package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.settings.TelegramBotForwardToChannelKey
import moe.rukamori.archivetune.shared.domain.settings.TelegramBotsKey
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotDirectory
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotProfile
import moe.rukamori.archivetune.shared.domain.telegram.TelegramBotStore
import moe.rukamori.archivetune.shared.domain.telegram.TelegramChannelInfo
import moe.rukamori.archivetune.shared.domain.telegram.TelegramChannelLibrary
import moe.rukamori.archivetune.shared.domain.telegram.TelegramChannelSearch

/** How long a single TDLib search/lookup may take on iOS, matching the client's own request bound. */
private const val REQUEST_TIMEOUT_MS = 15_000L

/**
 * The iOS channel search behind [TelegramChannelSearch], over the running TDLib client. Mirrors the Android
 * `searchChannels` exactly: an exact `@username`/t.me lookup first so pasting a link always works, then
 * `searchPublicChats` on the trimmed query, then `searchChats` over the user's own chat list so a joined private
 * channel is still reachable. Only supergroups/channels become results, same as the Android `toChannel` filter.
 */
class IosTelegramChannelSearch(
    private val client: IosTelegramClient,
) : TelegramChannelSearch {

    override suspend fun searchChannels(query: String): List<TelegramChannelInfo> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val chatIds = LinkedHashSet<Long>()
        extractUsername(trimmed)?.let { username ->
            runCatching {
                client.request("searchPublicChat", buildJsonObject { put("username", username) }, REQUEST_TIMEOUT_MS)
            }.onSuccess { chat -> chat.telegramLong("id")?.let(chatIds::add) }
        }
        runCatching {
            client.request("searchPublicChats", buildJsonObject { put("query", trimmed) }, REQUEST_TIMEOUT_MS)
        }.onSuccess { chats -> chats.chatIds(chatIds) }
        // searchPublicChats only ever returns chats with a public username, so a private channel the user already
        // joined could not be found by name at all — searchChats searches the user's own chat list locally.
        runCatching {
            client.request(
                "searchChats",
                buildJsonObject {
                    put("query", trimmed)
                    put("limit", LOCAL_CHAT_SEARCH_LIMIT)
                },
                REQUEST_TIMEOUT_MS,
            )
        }.onSuccess { chats -> chats.chatIds(chatIds) }
        return chatIds.mapNotNull { chatId -> runCatching { toChannel(getChat(chatId)) }.getOrNull() }
    }

    private suspend fun getChat(chatId: Long): JsonObject =
        client.request("getChat", buildJsonObject { put("chat_id", chatId) }, REQUEST_TIMEOUT_MS)

    private suspend fun toChannel(chat: JsonObject): TelegramChannelInfo? {
        val type = chat.telegramObject("type") ?: return null
        if (type.telegramText("@type") != "chatTypeSupergroup") return null
        val supergroupId = type.telegramLong("supergroup_id") ?: return null
        val supergroup =
            runCatching {
                client.request(
                    "getSupergroup",
                    buildJsonObject { put("supergroup_id", supergroupId) },
                    REQUEST_TIMEOUT_MS,
                )
            }.getOrNull()
        val photo = chat.telegramObject("photo")
        return TelegramChannelInfo(
            chatId = chat.telegramLong("id") ?: return null,
            title = chat.telegramText("title").orEmpty(),
            username =
                supergroup?.telegramObject("usernames")
                    ?.let { it["active_usernames"] as? JsonArray }
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?.firstOrNull(),
            memberCount = supergroup?.telegramInt("member_count") ?: 0,
            isBroadcast = type.telegramFlag("is_channel"),
            photoMinithumbnail = photo?.telegramObject("minithumbnail")?.telegramBytes("data"),
            photoFileId = photo?.telegramObject("small")?.telegramInt("id") ?: 0,
        )
    }

    private fun JsonObject.chatIds(into: MutableCollection<Long>) {
        (this["chat_ids"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
            ?.let(into::addAll)
    }

    private companion object {
        const val LOCAL_CHAT_SEARCH_LIMIT = 30
    }
}

/**
 * The iOS bot directory behind [TelegramBotDirectory]. Resolution is the Android flow: `searchPublicChat` on the
 * stripped username, keep only private chats, then `getUser` and keep only `userTypeBot` identities.
 */
class IosTelegramBotDirectory(
    private val client: IosTelegramClient,
) : TelegramBotDirectory {

    override suspend fun resolve(username: String): TelegramBotProfile? {
        val chat =
            client.request(
                "searchPublicChat",
                buildJsonObject { put("username", username.trim().removePrefix("@")) },
                REQUEST_TIMEOUT_MS,
            )
        val type = chat.telegramObject("type") ?: return null
        if (type.telegramText("@type") != "chatTypePrivate") return null
        val userId = type.telegramLong("user_id") ?: return null
        val user =
            client.request("getUser", buildJsonObject { put("user_id", userId) }, REQUEST_TIMEOUT_MS)
        if (user.telegramObject("type")?.telegramText("@type") != "userTypeBot") return null
        val photo = chat.telegramObject("photo")
        return TelegramBotProfile(
            chatId = chat.telegramLong("id") ?: return null,
            title = user.telegramText("first_name"),
            photoMinithumbnail = photo?.telegramObject("minithumbnail")?.telegramBytes("data"),
            photoFileId = photo?.telegramObject("small")?.telegramInt("id") ?: 0,
        )
    }
}

/**
 * The iOS bot list store: the `telegramBots` codec JSON and the forward-to-channel choice, held by the same
 * vault-backed settings owner as every other Telegram preference.
 */
class IosTelegramBotStore(
    private val settings: SettingsStore,
) : TelegramBotStore {

    override suspend fun readBots(): String =
        settings.snapshots.value?.get(TelegramBotsKey, "").orEmpty()

    override suspend fun writeBots(encoded: String) {
        settings.edit { this[TelegramBotsKey] = encoded }
    }

    override suspend fun readForwardToChannel(): Boolean =
        settings.snapshots.value?.get(TelegramBotForwardToChannelKey, true) ?: true

    override suspend fun writeForwardToChannel(enabled: Boolean) {
        settings.edit { this[TelegramBotForwardToChannelKey] = enabled }
    }
}

/**
 * The honest iOS channel-library state. Channel browse search is live, but no `LPtg<chatId>` playlist materialiser or
 * channel-sync engine exists on the iOS host yet, so opening a channel reports failure to the owner instead of
 * creating an empty playlist that looks synced.
 */
class IosTelegramChannelLibrary : TelegramChannelLibrary {
    override suspend fun ensurePlaylist(chatId: Long, title: String): String =
        throw UnsupportedOperationException("Telegram channel playlists are not wired on the iOS host")

    override fun startSync(chatId: Long, title: String, losslessOnly: Boolean) = Unit
}

private val TELEGRAM_LINK_USERNAME =
    Regex("(?:https?://)?t(?:elegram)?\\.me/([A-Za-z0-9_]{3,})", RegexOption.IGNORE_CASE)

private fun extractUsername(query: String): String? {
    val trimmed = query.trim()
    TELEGRAM_LINK_USERNAME.find(trimmed)?.groupValues?.get(1)?.let { return it }
    if (trimmed.startsWith("@")) {
        val candidate = trimmed.drop(1)
        if (candidate.matches(Regex("[A-Za-z0-9_]{3,}"))) return candidate
    }
    return null
}

/** Decodes a TDLib `bytes`/`minithumbnail.data` field, which TDLib JSON encodes as base64. */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
internal fun JsonObject.telegramBytes(key: String): ByteArray? =
    telegramText(key)?.let { text ->
        runCatching {
            kotlin.io.encoding.Base64.decode(text)
        }.getOrNull()
    }
