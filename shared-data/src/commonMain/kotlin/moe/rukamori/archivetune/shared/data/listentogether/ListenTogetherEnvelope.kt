@file:OptIn(ExperimentalEncodingApi::class)

package moe.rukamori.archivetune.shared.data.listentogether

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json

/**
 * Chat-channel envelopes. The room protocol has no field for a chat control, a shared song, a reply or a custom
 * avatar, so each one rides in the chat text behind a zero-width marker. The marker is invisible, which lets a client
 * that does not understand an envelope show nothing odd. Prefixes, suffixes and body encodings match the Android
 * client byte for byte, so messages from either side decode on the other.
 */
object ListenTogetherEnvelope {
    const val CHAT_CONTROL_PREFIX = "\u200B[LTC:"
    const val SHARED_TRACK_PREFIX = "\u200B[LTS:"
    const val REPLY_PREFIX = "\u200B[RPLY:"
    const val AVATAR_PREFIX = "\u200B[LTA:"
    const val ENVELOPE_SUFFIX = "]\u200B"

    /** Avatar index that means the member chose a custom picture. */
    const val CUSTOM_AVATAR_INDEX = 14

    /** Largest decoded custom picture a client accepts from the room. */
    const val MAX_AVATAR_BYTES = 96 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private class Opened(val body: String, val rest: String)

    /** Splits [message] at [prefix] and the first suffix after it. Null when the prefix is absent or the body is empty. */
    private fun open(message: String, prefix: String): Opened? {
        if (!message.startsWith(prefix)) return null
        val end = message.indexOf(ENVELOPE_SUFFIX, prefix.length)
        if (end <= prefix.length) return null
        return Opened(message.substring(prefix.length, end), message.substring(end + ENVELOPE_SUFFIX.length))
    }

    private fun wrap(prefix: String, body: ByteArray): String =
        prefix + Base64.Default.encode(body) + ENVELOPE_SUFFIX

    private fun decodeBody(body: String): String = Base64.Default.decode(body).decodeToString()

    /** The chat text for a reactions, edit, delete, pin or typing event. */
    fun encodeChatControl(event: ChatControlEvent): String =
        wrap(CHAT_CONTROL_PREFIX, json.encodeToString(ChatControlEvent.serializer(), event).encodeToByteArray())

    /** Parses a chat-control envelope; null when the text is not one or does not decode. */
    fun decodeChatControl(message: String): ChatControlEvent? {
        val opened = open(message, CHAT_CONTROL_PREFIX) ?: return null
        return runCatching {
            json.decodeFromString(ChatControlEvent.serializer(), decodeBody(opened.body))
        }.getOrNull()
    }

    /** The shared-song envelope that leads a chat message; the message text follows it. */
    fun encodeSharedTrack(track: TrackInfo): String =
        wrap(SHARED_TRACK_PREFIX, json.encodeToString(TrackInfo.serializer(), track).encodeToByteArray())

    /** Splits a shared-song envelope off [message]. Returns the track and the text after the envelope. */
    fun decodeSharedTrack(message: String): Pair<TrackInfo, String>? {
        val opened = open(message, SHARED_TRACK_PREFIX) ?: return null
        val track = runCatching {
            json.decodeFromString(TrackInfo.serializer(), decodeBody(opened.body))
        }.getOrNull() ?: return null
        return track to opened.rest
    }

    /**
     * The reply embed that leads a chat message. It is written before the shared-song envelope, so a receiver reads the
     * reply first and then the song from the text that remains.
     */
    fun encodeReply(replyTo: RepliedMessage): String =
        wrap(REPLY_PREFIX, "${replyTo.username}|${replyTo.message}".encodeToByteArray())

    /**
     * Splits a reply embed off [message]. The author and the quoted text are separated by the first bar, so quoted text
     * may itself contain bars. Returns null when the embed is absent or malformed.
     */
    fun decodeReply(message: String): Pair<RepliedMessage, String>? {
        val opened = open(message, REPLY_PREFIX) ?: return null
        val parts = runCatching { decodeBody(opened.body).split("|", limit = 2) }.getOrNull() ?: return null
        if (parts.size != 2) return null
        return RepliedMessage(parts[0], parts[1]) to opened.rest
    }

    /** The custom profile picture broadcast. The whole chat message is the envelope. */
    fun encodeAvatar(jpeg: ByteArray): String = wrap(AVATAR_PREFIX, jpeg)

    /** Decodes a custom profile picture. Null when the text is not one, is empty, or exceeds [MAX_AVATAR_BYTES]. */
    fun decodeAvatar(message: String): ByteArray? {
        val opened = open(message, AVATAR_PREFIX) ?: return null
        val bytes = runCatching { Base64.Default.decode(opened.body) }.getOrNull() ?: return null
        return bytes.takeIf { it.isNotEmpty() && it.size <= MAX_AVATAR_BYTES }
    }
}
