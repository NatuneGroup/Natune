package moe.rukamori.archivetune.shared.domain.presence

import kotlinx.coroutines.flow.Flow

/**
 * Posts one listener-presence payload built by [ListenerPresencePolicy]. [available] is false when the host has no
 * HTTPS endpoint, and the feature must then stay silent: nothing is sent. A failure is returned, never retried.
 */
interface ListenerPresenceTransport {
    val available: Boolean

    suspend fun send(payloadJson: String): Result<Unit>
}

/**
 * The persisted opt-in. [listenerId] is a random pseudonym that is sent only as a keyed hash on the server. An opt-in
 * recorded under an older [consentVersion] is shown as off, so a changed disclosure asks the user again.
 */
data class ListenerPresenceState(
    val enabled: Boolean,
    val listenerId: String?,
    val consentVersion: Int,
) {
    val active: Boolean
        get() = enabled && consentVersion == ListenerPresencePolicy.CONSENT_VERSION && !listenerId.isNullOrEmpty()

    companion object {
        val Off: ListenerPresenceState = ListenerPresenceState(enabled = false, listenerId = null, consentVersion = 0)
    }
}

/**
 * The platform-owned opt-in store. Each transition is one atomic edit that publishes only after durable commit.
 * Implementations must keep the identifier out of backed-up storage.
 */
interface ListenerPresenceStore {
    val state: Flow<ListenerPresenceState>

    /** Opts in with a fresh identifier. Does nothing when the opt-in is already current. */
    suspend fun enable()

    /** Opts out and deletes the identifier. The controller stops heartbeats from the state change. */
    suspend fun disable()

    /** Replaces the identifier while the opt-in is active. Does nothing otherwise. */
    suspend fun resetId()
}

/** The host that sends a heartbeat. The wire names are exactly the values the backend accepts. */
enum class ListenerPresencePlatform(
    val wireName: String,
) {
    ANDROID("android"),
    DESKTOP("desktop"),
    IOS("ios"),
}
