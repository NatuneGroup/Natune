package moe.rukamori.archivetune.shared.domain.telegram

/**
 * The address of one Telegram channel track: the chat and message it lives in, and the TDLib file that carries its
 * audio. The chat and message identify the track across TDLib database rebuilds. The file id is valid only for the
 * database that issued it, so [fileUniqueId] lets the host notice a stale id and resolve the message again.
 */
data class TelegramTrackRef(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileUniqueId: String = "",
) {
    /** The `telegram://track/...` media id that the retained queue stores. An empty [fileUniqueId] is omitted. */
    fun toMediaId(): String =
        buildString {
            append(TRACK_PREFIX)
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
        /** The URI scheme of Telegram media ids, kept as the retained `telegram://` route name. */
        const val SCHEME = "telegram"

        private const val TRACK_PREFIX = "$SCHEME://track/"

        /**
         * Parses a media id. Returns null unless the chat, message and file segments are numeric. A missing unique id
         * is empty, as in the retained codec, so an id saved before the unique id was stored still parses.
         */
        fun fromMediaId(mediaId: String): TelegramTrackRef? {
            if (!mediaId.startsWith(TRACK_PREFIX)) return null
            val segments = mediaId.removePrefix(TRACK_PREFIX).split('/')
            if (segments.size < 3) return null
            return TelegramTrackRef(
                chatId = segments[0].toLongOrNull() ?: return null,
                messageId = segments[1].toLongOrNull() ?: return null,
                fileId = segments[2].toIntOrNull() ?: return null,
                fileUniqueId = segments.getOrNull(3).orEmpty(),
            )
        }
    }
}
