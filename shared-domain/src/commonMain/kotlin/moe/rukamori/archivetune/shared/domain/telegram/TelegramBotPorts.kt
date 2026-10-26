package moe.rukamori.archivetune.shared.domain.telegram

/**
 * The resolved identity of a bot, as the Telegram client reports it. [TelegramBotDirectory.resolve] returns null
 * when the bot cannot be resolved.
 */
data class TelegramBotProfile(
    val chatId: Long,
    /** The bot's first name. Null or blank falls back to @username in the saved bot. */
    val title: String?,
    val photoMinithumbnail: ByteArray?,
    val photoFileId: Int,
)

/**
 * Resolves a bot @username to its chat through the signed-in Telegram client. A failed lookup returns null or
 * throws.
 */
interface TelegramBotDirectory {
    suspend fun resolve(username: String): TelegramBotProfile?
}

/**
 * Persistence for the bot list and the forward-to-channel choice. The bot list is kept as the
 * [TelegramBotCodec] JSON under the existing `telegramBots` preference; the host supplies the durable store.
 */
interface TelegramBotStore {
    /** The encoded list, or blank when none has been saved. */
    suspend fun readBots(): String

    suspend fun writeBots(encoded: String)

    /** The saved forward choice; true when none has been saved. */
    suspend fun readForwardToChannel(): Boolean

    suspend fun writeForwardToChannel(enabled: Boolean)
}

/** Why an add did not produce a saved bot. Mapped to the screen's notices by the host. */
sealed interface TelegramBotAddIssue {
    data object InvalidUsername : TelegramBotAddIssue

    data class AlreadyAdded(val username: String) : TelegramBotAddIssue

    data object ResolveFailed : TelegramBotAddIssue
}

/**
 * The bot management state. [ready] is false while the Telegram account is signed out; the host must not offer
 * add in that state.
 */
data class TelegramBotsState(
    val ready: Boolean,
    val input: String,
    val adding: Boolean,
    val bots: List<TelegramBot>,
    val forwardToChannel: Boolean,
    val issue: TelegramBotAddIssue?,
)
