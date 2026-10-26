package moe.rukamori.archivetune.shared.data.lastfm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One queued play in the pending-scrobble document. The field names are the retained Android queue file's, so a KMP
 * host can read the file that the app wrote. [account] is `endpoint|username` in lowercase, so a queue filled on one
 * account is never sent to another.
 */
@Serializable
internal data class PendingLastFmScrobble(
    val artist: String,
    val track: String,
    val timestamp: Long,
    val album: String? = null,
    val duration: Int? = null,
    val account: String,
)

/** JSON text codec for the pending-scrobble document. Unknown keys are ignored and default-valued keys are omitted. */
internal object LastFmScrobbleDocument {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    fun decode(text: String): List<PendingLastFmScrobble> = json.decodeFromString(text)

    fun encode(entries: List<PendingLastFmScrobble>): String = json.encodeToString(entries)
}
