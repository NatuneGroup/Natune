/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lastfm

/**
 * The Last.fm web-service calls that the sign-in flow, the playback driver and the scrobble owner need. Every call
 * returns a [Result]. Implementations rethrow cancellation instead of turning it into a failure.
 */
interface LastFmService {
    /** auth.getToken. The web flow then sends the user to [authorizationUrl] and exchanges the approved token. */
    suspend fun requestWebToken(): Result<String>

    /** The address that asks the user to approve [token], built for the configured endpoint. */
    fun authorizationUrl(token: String): String

    /** auth.getSession for a token the user has approved. */
    suspend fun exchangeWebToken(token: String): Result<LastFmSession>

    /** auth.getMobileSession with a username and password, sent in the form that [mode] selects. */
    suspend fun signInWithPassword(
        username: String,
        password: String,
        mode: LastFmMobileAuthMode,
    ): Result<LastFmSession>

    /** track.updateNowPlaying. It is transient by design and is never queued or retried. */
    suspend fun updateNowPlaying(
        track: LastFmTrackMetadata,
        sessionKey: String,
    ): Result<Unit>

    /** track.scrobble for 1 to [LastFmEndpoints.MAX_SCROBBLES_PER_BATCH] plays. */
    suspend fun scrobbleBatch(
        scrobbles: List<LastFmScrobble>,
        sessionKey: String,
    ): Result<LastFmBatchResult>

    /** track.love when [loved] is true, otherwise track.unlove. */
    suspend fun setLoved(
        artist: String,
        track: String,
        loved: Boolean,
        sessionKey: String,
    ): Result<Unit>
}

/**
 * Host-owned session storage. [clearSessionIfCurrent] must compare and clear in one step, so a request that failed
 * with an old key never removes a newer sign-in.
 */
interface LastFmSessionPort {
    /** The stored session, or null when signed out. */
    suspend fun currentSession(): LastFmSession?

    /** Clears the stored session only while it still holds [sessionKeyUsed]. */
    suspend fun clearSessionIfCurrent(sessionKeyUsed: String)
}

/**
 * Host persistence for the pending-scrobble document. The owner encodes the JSON text. Writes must be atomic: a crash
 * during a write must leave either the previous document or the new one, never a partial file.
 */
interface LastFmScrobbleStore {
    /** The last written document, or null when nothing has been written yet. */
    suspend fun read(): String?

    /** Replaces the stored document. */
    suspend fun write(document: String)
}
