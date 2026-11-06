package moe.rukamori.archivetune.shared.domain.lastfm

/**
 * Last.fm application credentials the host bakes into its build. Shared code never stores them: the host passes them
 * in through [LastFmApiConfig]. [toString] hides both values so a diagnostic dump cannot leak them.
 */
class LastFmAppCredentials(
    val apiKey: String,
    val sharedSecret: String,
) {
    /** Signed requests need both values; without them scrobbling stays off rather than sending unsigned calls. */
    val isUsable: Boolean
        get() = apiKey.isNotBlank() && sharedSecret.isNotBlank()

    override fun toString(): String = "LastFmAppCredentials(apiKey=<redacted>, sharedSecret=<redacted>)"
}

/** Where signed calls go and which application signs them. The session key is read separately, per call. */
data class LastFmApiConfig(
    val endpoint: String,
    val credentials: LastFmAppCredentials,
)

/** A signed-in Last.fm account. [sessionKey] is a secret and is redacted from [toString]. */
class LastFmSession(
    val username: String,
    val sessionKey: String,
    val subscriber: Int = 0,
) {
    override fun toString(): String = "LastFmSession(username=$username, sessionKey=<redacted>, subscriber=$subscriber)"
}

/**
 * How a password sign-in is sent. Libre.fm (GNU FM) accepts only the pre-2.0 token, md5(username + md5(password)), so
 * it must never receive the plain password in that form.
 */
enum class LastFmMobileAuthMode {
    PASSWORD,
    LEGACY_AUTH_TOKEN,
}

/** One play for `track.scrobble`. [timestampEpochSeconds] is the UTC second that playback started. */
data class LastFmScrobble(
    val artist: String,
    val track: String,
    val timestampEpochSeconds: Long,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val durationSeconds: Int? = null,
    val chosenByUser: Boolean = true,
)

/**
 * Last.fm answers `status="ok"` for a batch even when it drops entries, and says so per entry in `ignoredMessage`.
 * Ignored entries are final: sending them again is ignored again.
 */
data class LastFmBatchResult(
    val accepted: Int,
    val ignored: Int,
    val ignoredCodes: List<Int>,
)

/**
 * A failed Last.fm call. When [isApiError] is true, [code] is the Last.fm error code. Otherwise [code] is a local code
 * and [httpStatus] is the status of a response that carried no API error body (a proxy or an outage page).
 */
class LastFmApiException(
    val code: Int,
    override val message: String,
    val httpStatus: Int? = null,
    val isApiError: Boolean = true,
) : Exception(message)

/** Last.fm error codes the scrobble owner acts on. */
object LastFmErrorCodes {
    const val INVALID_PARAMETERS = 6
    const val INVALID_RESOURCE = 7

    /** The session was revoked. Also used locally, with `isApiError = false`, when no session is stored. */
    const val INVALID_SESSION = 9
}

/** Endpoints and the batch limit shared by the client and the scrobble owner. */
object LastFmEndpoints {
    const val DEFAULT = "https://ws.audioscrobbler.com/2.0/"
    const val LIBREFM = "https://libre.fm/2.0/"
    const val MAX_SCROBBLES_PER_BATCH = 50
}
