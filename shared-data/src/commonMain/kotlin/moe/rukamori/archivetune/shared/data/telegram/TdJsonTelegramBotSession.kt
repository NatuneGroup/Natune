package moe.rukamori.archivetune.shared.data.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The TDLib side of one bot chat over a TDLib JSON client: resolving the bot, its commands, sending text, pressing
 * a keyboard button, collecting the bot's answer, forwarding a track and fetching its file.
 *
 * A JSON host may not route `updateNewMessage`, so [collectReplies] reads the chat history instead: it polls every
 * [pollIntervalMs] for messages newer than the one it answers, waits up to [replyTimeoutMs] for the first reply and
 * then until [graceMs] pass without another, the same windows the Android chat uses.
 */
class TdJsonTelegramBotSession(
    private val td: TdJsonRequester,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val replyTimeoutMs: Long = DEFAULT_REPLY_TIMEOUT_MS,
    private val graceMs: Long = DEFAULT_GRACE_MS,
) {
    init {
        require(pollIntervalMs > 0 && replyTimeoutMs > 0 && graceMs >= 0)
    }

    /** A resolved bot: its private chat and the first name Telegram reports for it. */
    data class ResolvedBot(val chatId: Long, val title: String?)

    /** The bot behind [username], or null when the name is not a bot's private chat. A failed lookup throws. */
    suspend fun resolveBot(username: String): ResolvedBot? {
        val cleaned = username.trim().removePrefix("@").lowercase()
        if (cleaned.isEmpty()) return null
        val chat = td.call("searchPublicChat", REQUEST_TIMEOUT_MS) { put("username", cleaned) }
        val type = chat.tdObject("type") ?: return null
        if (type.tdText("@type") != "chatTypePrivate") return null
        val userId = type.tdLong("user_id") ?: return null
        val user = td.call("getUser", REQUEST_TIMEOUT_MS) { put("user_id", userId) }
        if (user.tdObject("type")?.tdText("@type") != "userTypeBot") return null
        return ResolvedBot(
            chatId = chat.tdLong("id") ?: return null,
            title = user.tdText("first_name")?.takeIf(String::isNotBlank),
        )
    }

    /** The commands the bot registered for this chat. A bot without any, or a failed read, has none. */
    suspend fun commands(chatId: Long): List<TdJsonBotCommand> =
        bestEffort(emptyList()) {
            val reply = td.call("getCommands", REQUEST_TIMEOUT_MS) {
                putJsonObject("scope") {
                    put("@type", "botCommandScopeChat")
                    put("chat_id", chatId)
                }
                put("language_code", "")
            }
            (reply["commands"] as? JsonArray).orEmpty().mapNotNull { element ->
                val command = (element as? JsonObject)?.tdText("command")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                TdJsonBotCommand(command, element.tdText("description").orEmpty())
            }
        }

    /** Tells TDLib the chat is on screen, so it keeps the history current. */
    suspend fun open(chatId: Long) {
        bestEffort(Unit) { td.call("openChat", REQUEST_TIMEOUT_MS) { put("chat_id", chatId) } }
    }

    suspend fun close(chatId: Long) {
        bestEffort(Unit) { td.call("closeChat", REQUEST_TIMEOUT_MS) { put("chat_id", chatId) } }
    }

    /** Sends [text] and returns the id of the sent message, which [collectReplies] answers. */
    suspend fun sendText(chatId: Long, text: String): Long {
        val sent = td.call("sendMessage", REQUEST_TIMEOUT_MS) {
            put("chat_id", chatId)
            putJsonObject("input_message_content") {
                put("@type", "inputMessageText")
                putJsonObject("text") {
                    put("@type", "formattedText")
                    put("text", text)
                }
            }
        }
        return sent.tdLong("id") ?: throw IllegalStateException("sendMessage returned no message id")
    }

    /**
     * Presses a callback button. Android ignores the callback answer (a toast at most) and always waits for the
     * bot's next message, so a failed or slow answer is ignored here too.
     */
    suspend fun press(prompt: TdJsonInlinePrompt, button: TdJsonInlineButton) {
        val data = button.callbackData ?: return
        bestEffort(Unit) {
            td.call("getCallbackQueryAnswer", REQUEST_TIMEOUT_MS) {
                put("chat_id", prompt.chatId)
                put("message_id", prompt.messageId)
                putJsonObject("payload") {
                    put("@type", "callbackQueryPayloadData")
                    put("data", data)
                }
            }
        }
    }

    /** The bot's replies after [afterMessageId], oldest first. An empty list means the bot did not answer in time. */
    suspend fun collectReplies(chatId: Long, afterMessageId: Long): List<TdJsonBotReply> {
        val replies = LinkedHashMap<Long, TdJsonBotReply>()
        var seen = afterMessageId
        var waited = 0L
        var quiet = 0L
        while (true) {
            val fresh = newerReplies(chatId, seen)
            fresh.forEach { reply -> replies.getOrPut(reply.messageId) { reply } }
            seen = maxOf(seen, fresh.maxOfOrNull { it.messageId } ?: seen)
            if (replies.isEmpty()) {
                if (waited >= replyTimeoutMs) break
            } else if (fresh.isNotEmpty()) {
                quiet = 0L
            } else if (quiet >= graceMs) {
                break
            }
            delay(pollIntervalMs)
            waited += pollIntervalMs
            if (replies.isNotEmpty() && fresh.isEmpty()) quiet += pollIntervalMs
        }
        return replies.values.sortedBy { it.messageId }
    }

    /** Forwards one track message into [toChatId]. A refused forward throws, so the caller can report it. */
    suspend fun forward(toChatId: Long, track: TdJsonAudioTrack) {
        td.call("forwardMessages", REQUEST_TIMEOUT_MS) {
            put("chat_id", toChatId)
            put("from_chat_id", track.chatId)
            putJsonArray("message_ids") { add(kotlinx.serialization.json.JsonPrimitive(track.messageId)) }
            put("send_copy", false)
            put("remove_caption", false)
        }
    }

    /** Downloads the whole file of [track] and returns its local path. A failed or empty download throws. */
    suspend fun download(track: TdJsonAudioTrack): String {
        val file = td.call("downloadFile", DOWNLOAD_TIMEOUT_MS) {
            put("file_id", track.fileId)
            put("priority", DOWNLOAD_PRIORITY)
            put("offset", 0)
            put("limit", 0)
            put("synchronous", true)
        }
        val local = file.tdObject("local")
        if (local?.tdFlag("is_downloading_completed") != true) throw IllegalStateException("download incomplete")
        return local.tdText("path")?.takeIf(String::isNotBlank) ?: throw IllegalStateException("download has no path")
    }

    /** Incoming bot messages newer than [afterMessageId] in the newest page of the history. */
    private suspend fun newerReplies(chatId: Long, afterMessageId: Long): List<TdJsonBotReply> {
        val page = bestEffort(null) {
            td.call("getChatHistory", REQUEST_TIMEOUT_MS) {
                put("chat_id", chatId)
                put("from_message_id", 0)
                put("offset", 0)
                put("limit", HISTORY_PAGE)
                put("only_local", false)
            }
        } ?: return emptyList()
        return TdJsonTelegramMessages.messages(page)
            .filter { (it.tdLong("id") ?: 0L) > afterMessageId && !it.tdFlag("is_outgoing") }
            .mapNotNull(TdJsonTelegramMessages::botReply)
    }

    private suspend fun <T> bestEffort(fallback: T, block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fallback
        }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 1_000L
        const val DEFAULT_REPLY_TIMEOUT_MS = 60_000L
        const val DEFAULT_GRACE_MS = 5_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val DOWNLOAD_TIMEOUT_MS = 300_000L
        private const val DOWNLOAD_PRIORITY = 32
        private const val HISTORY_PAGE = 30
    }
}
