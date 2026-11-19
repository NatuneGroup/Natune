
package moe.rukamori.archivetune.telegram

const val TELEGRAM_MEDIA_ID_SCHEME = "telegram"

private const val PREFIX = "$TELEGRAM_MEDIA_ID_SCHEME://track/"

data class TelegramMediaId(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileUniqueId: String = "",
) {
    fun encode(): String =
        buildString {
            append(PREFIX)
            append(chatId)
            append('/')
            append(messageId)
            append('/')
            append(fileId)
            if (fileUniqueId.isNotEmpty()) {
                append('/')
                append(fileUniqueId)
            }
        }

    companion object {
        fun decode(mediaId: String): TelegramMediaId? {
            if (!mediaId.startsWith(PREFIX)) return null
            val segments = mediaId.removePrefix(PREFIX).split('/')
            if (segments.size < 3) return null
            val chatId = segments[0].toLongOrNull() ?: return null
            val messageId = segments[1].toLongOrNull() ?: return null
            val fileId = segments[2].toIntOrNull() ?: return null
            return TelegramMediaId(
                chatId = chatId,
                messageId = messageId,
                fileId = fileId,
                fileUniqueId = segments.getOrNull(3).orEmpty(),
            )
        }
    }
}

/**
 * True when this media id addresses a Telegram channel track. Telegram ids behave like local media
 * ids for everything YouTube-specific (no YT metadata fetch, no remote scrobble id, no YT playlist
 * sync) but are streamed through [TelegramDataSource] instead of the content resolver.
 */
fun String.isTelegramMediaId(): Boolean = startsWith(PREFIX) && TelegramMediaId.decode(this) != null
