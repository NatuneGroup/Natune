package moe.rukamori.archivetune.shared.domain.settings

/**
 * One started TIDAL device authorization. The user enters [userCode] at [verificationUri] on any device. [deviceCode] is
 * the secret the host polls with: it is never displayed, logged or stored, and it dies with the attempt.
 */
class TidalDeviceAuthorization(
    val userCode: String,
    val verificationUri: String,
    /** How long the code stays valid from the moment [TidalDeviceLink.start] returned it. */
    val expiresInMillis: Long,
    val intervalMillis: Long,
    val deviceCode: String,
) {
    init {
        require(userCode.isNotBlank() && deviceCode.isNotBlank()) { "Device authorization is incomplete" }
        require(verificationUri.startsWith("https://")) { "Device authorization must point at an HTTPS page" }
    }

    override fun toString(): String = "TidalDeviceAuthorization(REDACTED)"
}

/** One poll of the token endpoint for a device authorization, as the OAuth device grant reports it. */
sealed interface TidalDeviceLinkPoll {
    /** The user has not finished yet. Poll again after the interval. */
    data object Pending : TidalDeviceLinkPoll

    /** The server asked for a slower poll. The interval grows before the next poll. */
    data object SlowDown : TidalDeviceLinkPoll

    /** The code expired before the user finished. A new code is needed. */
    data object Expired : TidalDeviceLinkPoll

    /** The user declined, or the server refused the grant. */
    data object Denied : TidalDeviceLinkPoll

    /** The token or session check failed in a way a new poll would not fix. */
    data object Failed : TidalDeviceLinkPoll

    /**
     * The user authorized this device and the host checked the session with TIDAL. [session] carries the verified user id,
     * so it can be committed as that account.
     */
    class Authorized(val session: TidalSessionFacts) : TidalDeviceLinkPoll {
        override fun toString(): String = "Authorized(REDACTED)"
    }
}

/**
 * The host side of the TIDAL device sign-in. [start] asks for a new code, or returns null when this build has no device
 * client. [poll] makes one token request. The settings page owns the loop, its interval and its cancellation, so the host
 * never polls in the background.
 */
interface TidalDeviceLink {
    suspend fun start(): TidalDeviceAuthorization?

    suspend fun poll(authorization: TidalDeviceAuthorization): TidalDeviceLinkPoll
}

/** The poll interval after a slow-down answer: five more seconds, as RFC 8628 section 3.5 asks. */
fun tidalDeviceSlowDownInterval(intervalMillis: Long): Long = intervalMillis + 5_000L
