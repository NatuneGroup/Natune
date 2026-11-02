package moe.rukamori.archivetune.shared.domain.presence

import kotlin.random.Random

/**
 * The whole listener-presence payload: a random identifier, the platform, the app version and a playing or stopped
 * state. Nothing about tracks, playlists, searches, accounts or the device's location is part of the contract, and the
 * backend rejects any extra field.
 */
object ListenerPresencePolicy {
    const val CONSENT_VERSION: Int = 1
    const val SCHEMA_VERSION: Int = 1
    const val HEARTBEAT_INTERVAL_MS: Long = 900_000L

    /** Uniform jitter added to every beat so recovered fleets do not hit the endpoint in lockstep. */
    const val HEARTBEAT_JITTER_MS: Long = 120_000L
    const val MAX_PAYLOAD_BYTES: Int = 1024
    const val STATE_PLAYING: String = "playing"
    const val STATE_STOPPED: String = "stopped"

    private val listenerIdPattern = Regex("^[a-f0-9]{32}$")
    private val appVersionPattern = Regex("^[0-9A-Za-z._+-]{1,32}$")

    /** Sixteen random bytes as 32 lowercase hex characters. Android passes SecureRandom through [randomBytes]. */
    fun newListenerId(randomBytes: (Int) -> ByteArray = { Random.nextBytes(it) }): String =
        randomBytes(LISTENER_ID_BYTES).joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(radix = 16).padStart(2, '0')
        }

    fun isValidListenerId(id: String): Boolean = listenerIdPattern.matches(id)

    /** An unusual version string is replaced by a fixed token rather than sent as free text. */
    fun sanitizedAppVersion(versionName: String): String =
        if (appVersionPattern.matches(versionName)) versionName else UNKNOWN_APP_VERSION

    fun payloadJson(
        listenerId: String,
        platform: ListenerPresencePlatform,
        appVersion: String,
        state: String,
    ): String {
        require(isValidListenerId(listenerId)) { "Listener presence id must be 32 lowercase hex characters" }
        require(state == STATE_PLAYING || state == STATE_STOPPED) { "Unknown listener presence state" }
        val json =
            """{"schema":$SCHEMA_VERSION,"id":"$listenerId","platform":"${platform.wireName}",""" +
                """"app":"${sanitizedAppVersion(appVersion)}","state":"$state"}"""
        check(json.encodeToByteArray().size <= MAX_PAYLOAD_BYTES) { "Listener presence payload exceeds its limit" }
        return json
    }

    /** The opt-in after the user switches it on: a fresh identifier and the current consent version. */
    fun enable(
        current: ListenerPresenceState,
        newId: () -> String,
    ): ListenerPresenceState =
        if (current.active) {
            current
        } else {
            ListenerPresenceState(enabled = true, listenerId = newId(), consentVersion = CONSENT_VERSION)
        }

    /** The opt-in after the user switches it off. The identifier is removed, so a later opt-in starts fresh. */
    fun disable(): ListenerPresenceState = ListenerPresenceState.Off

    /** Replaces the identifier of an active opt-in. An inactive opt-in is returned unchanged. */
    fun resetId(
        current: ListenerPresenceState,
        newId: () -> String,
    ): ListenerPresenceState = if (current.active) current.copy(listenerId = newId()) else current

    private const val LISTENER_ID_BYTES: Int = 16
    private const val UNKNOWN_APP_VERSION: String = "unknown"
}
