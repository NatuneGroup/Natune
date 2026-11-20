package app.natune.playback

import app.natune.constants.AudioSourceType
import app.natune.constants.SpotifyStreamResolveMethod
import app.natune.deezer.DeezerAudioProvider
import app.natune.qobuz.QobuzAudioProvider
import app.natune.qobuz.QobuzBackupProvider
import app.natune.shared.domain.source.StreamRecoveryTracker
import app.natune.spotify.SpotifyWebStreamer
import app.natune.spotify.echo.SpotifyEchoResolver
import app.natune.tidal.TidalAudioProvider

/** External HTTP rejection is URL recovery, not a YouTube client failure or a cache-corruption retry. */
internal class ExternalStreamRecovery {
    private val attempts = StreamRecoveryTracker<AudioSourceType>()

    fun handles(source: AudioSourceType?, status: Int): Boolean =
        source != null && source != AudioSourceType.YOUTUBE && status in RESPONSE_CODES

    fun onHttpFailure(mediaId: String, source: AudioSourceType?, status: Int, invalidate: () -> Unit): Boolean {
        if (source == null || !handles(source, status)) return false
        invalidate()
        if (source in attempts.rejectedSources(mediaId)) return false
        attempts.onFailure(mediaId, source)
        return true
    }

    fun refreshSource(mediaId: String): AudioSourceType? = attempts.refreshSource(mediaId)
    fun rejectedSources(mediaId: String): Set<AudioSourceType> = attempts.rejectedSources(mediaId)
    fun reject(mediaId: String, source: AudioSourceType) = attempts.reject(mediaId, source)
    fun resolved(mediaId: String) = attempts.resolved(mediaId)
    fun clear(mediaId: String) = attempts.clear(mediaId)
    fun clear() = attempts.clear()
    fun retainSongs(keep: (String) -> Boolean) = attempts.retainSongs(keep)

    fun invalidateProviderUrls(
        mediaId: String,
        source: AudioSourceType,
        spotifyTrackUri: String?,
        spotifyMethod: SpotifyStreamResolveMethod?,
    ) {
        when (source) {
            AudioSourceType.TIDAL -> TidalAudioProvider.invalidate(mediaId)
            AudioSourceType.DEEZER -> DeezerAudioProvider.invalidate(mediaId)
            AudioSourceType.QOBUZ -> QobuzAudioProvider.invalidate(mediaId)
            AudioSourceType.QOBUZ_BACKUP -> QobuzBackupProvider.invalidate()
            AudioSourceType.SPOTIFY -> if (spotifyTrackUri != null) {
                when (spotifyMethod) {
                    SpotifyStreamResolveMethod.ECHO -> SpotifyEchoResolver.invalidateTrack(spotifyTrackUri)
                    SpotifyStreamResolveMethod.STOPIFY -> SpotifyWebStreamer.invalidateTrack(spotifyTrackUri)
                    null -> Unit
                }
            }
            else -> Unit // These providers do not retain a resolved URL.
        }
    }

    private companion object {
        val RESPONSE_CODES = setOf(401, 403, 404, 410, 416)
    }
}
