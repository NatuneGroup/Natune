package moe.rukamori.archivetune.shared.domain.lastfm

import kotlinx.coroutines.CancellationException

/** What one love mirror did. Only [Sent] means that Last.fm acknowledged the change. */
sealed interface LastFmLoveOutcome {
    /** Last.fm acknowledged track.love when [loved] is true, or track.unlove when it is false. */
    data class Sent(
        val loved: Boolean,
    ) : LastFmLoveOutcome

    /** Nothing was sent. A skipped change is not queued and is not retried later. */
    data class Skipped(
        val reason: LastFmLoveSkipReason,
    ) : LastFmLoveOutcome

    /** The request failed, so Last.fm did not record the change. [error] is the real failure, never a success. */
    data class Failed(
        val error: Throwable,
    ) : LastFmLoveOutcome
}

/** Why a love change was not sent. */
enum class LastFmLoveSkipReason {
    /** Sending loves is turned off in settings. */
    SENDING_DISABLED,

    /** No session is stored, so there is no account to write to. */
    NOT_SIGNED_IN,

    /** The build has no usable credentials, so no signed request can be made. */
    NOT_CONFIGURED,

    /** Podcast episodes are never loved on Last.fm. */
    PODCAST,

    /** The artist or title cleans to nothing that Last.fm accepts. */
    UNNAMEABLE_TRACK,
}

/**
 * Mirrors a local like change to Last.fm, as the baseline's love sync did. The caller decides when a like changed and
 * passes the new state, so each call is one request. Nothing is queued or retried here. A failure goes to
 * [onRequestFailed] together with the session key that was used, so the scrobble owner can clear a revoked session only
 * while it still holds that key.
 */
class LastFmLoveMirror(
    private val service: LastFmService,
    private val sessions: LastFmSessionPort,
    private val config: () -> LastFmApiConfig,
    private val sendLovesEnabled: suspend () -> Boolean,
    private val onRequestFailed: (Throwable, String?) -> Unit,
) {
    /**
     * Sends the love state of one song. A failure while reading the ports comes back as [LastFmLoveOutcome.Failed],
     * and cancellation is rethrown.
     */
    suspend fun mirror(
        artistNames: List<String>,
        title: String,
        albumTitle: String?,
        isPodcast: Boolean,
        loved: Boolean,
    ): LastFmLoveOutcome =
        try {
            send(artistNames, title, albumTitle, isPodcast, loved)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LastFmLoveOutcome.Failed(failure)
        }

    private suspend fun send(
        artistNames: List<String>,
        title: String,
        albumTitle: String?,
        isPodcast: Boolean,
        loved: Boolean,
    ): LastFmLoveOutcome {
        if (!sendLovesEnabled()) return LastFmLoveOutcome.Skipped(LastFmLoveSkipReason.SENDING_DISABLED)
        if (isPodcast) return LastFmLoveOutcome.Skipped(LastFmLoveSkipReason.PODCAST)
        if (!config().credentials.isUsable) return LastFmLoveOutcome.Skipped(LastFmLoveSkipReason.NOT_CONFIGURED)
        val session =
            sessions.currentSession()?.takeIf { it.sessionKey.isNotBlank() }
                ?: return LastFmLoveOutcome.Skipped(LastFmLoveSkipReason.NOT_SIGNED_IN)
        val track =
            LastFmTrackMetadata.from(artistNames, title, albumTitle, durationSeconds = null)
                ?: return LastFmLoveOutcome.Skipped(LastFmLoveSkipReason.UNNAMEABLE_TRACK)
        val failure = service.setLoved(track.artist, track.track, loved, session.sessionKey).exceptionOrNull()
        if (failure == null) return LastFmLoveOutcome.Sent(loved)
        onRequestFailed(failure, session.sessionKey)
        return LastFmLoveOutcome.Failed(failure)
    }
}
