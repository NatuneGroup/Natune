package moe.rukamori.archivetune.playback

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.audiosource.AudioSourceAttemptScope
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.constants.AudioSourceType
import moe.rukamori.archivetune.constants.SpotifyStreamResolveMethod
import moe.rukamori.archivetune.shared.domain.source.SpotifyLibrespotAudioPolicy
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamEngine
import moe.rukamori.archivetune.shared.domain.source.StreamUrlExpiry
import moe.rukamori.archivetune.spotify.SpotifyStreamingAccount
import moe.rukamori.archivetune.spotify.SpotifyWebStreamer
import moe.rukamori.archivetune.spotify.echo.SpotifyEchoResolver
import moe.rukamori.archivetune.spotify.librespot.SpotifyLibrespotAudio
import moe.rukamori.archivetune.spotify.librespot.SpotifyLibrespotAudioException
import moe.rukamori.archivetune.utils.dataStore
import timber.log.Timber

internal class SpotifyAudioResolver(
    private val context: Context,
    private val librespot: SpotifyLibrespotAudio,
    private val onResolved: (String, String?, SpotifyStreamResolveMethod?, String) -> Unit,
) {
    fun resolve(
        mediaId: String,
        query: SourceQuery,
        trusted: Boolean,
        lowData: Boolean,
    ): DirectStream? {
        return runCatching {
            runBlocking<DirectStream?>(Dispatchers.IO + AudioSourceAttemptScope.coroutineContextElement()) {
                if (SpotifyStreamingAccount.effectiveEngine(context.dataStore) == SpotifyStreamEngine.LIBRESPOT) {
                    val resolved = librespot.resolve(query, trusted, lowData) ?: return@runBlocking null
                    onResolved(mediaId, null, null, resolved.second)
                    return@runBlocking resolved.first
                }
                // Also the separation check: no token comes back for a signed-out account or one
                // that has turned out to be the catalog account.
                if (SpotifyStreamingAccount.accessToken(context) == null) {
                    Timber.tag("MusicService").d("Spotify streaming: no usable streaming account; skipping")
                    return@runBlocking null
                }
                val directTrackId = query.spotifyTrackId
                val match =
                    if (directTrackId != null) {
                        SpotifyStreamingAccount.TrackMatch(
                            trackId = directTrackId,
                            title = query.title,
                            artist = query.artists.joinToString(", ").ifBlank { null },
                            durationMs = query.durationMs,
                        )
                    } else {
                        SpotifyStreamingAccount.searchTrack(context, query.title, query.artists, query.durationMs)
                    }
                if (match == null) {
                    Timber.tag("MusicService").d("Spotify streaming: no track match for \"%s\"", query.title)
                    return@runBlocking null
                }
                val trackUri = "spotify:track:${match.trackId}"
                val trustedId = directTrackId != null || trusted
                if (SpotifyStreamingAccount.resolveMethod(context) == SpotifyStreamResolveMethod.ECHO) {
                    val echo =
                        runCatching {
                            SpotifyEchoResolver.resolve(SpotifyStreamingAccount.spDc(context), match.trackId, match.durationMs)
                        }.onFailure { error ->
                            Timber.tag("MusicService").w(error, "Spotify Echo resolution failed for \"%s\"", query.title)
                        }.getOrNull()
                    if (echo != null) {
                        onResolved(mediaId, trackUri, SpotifyStreamResolveMethod.ECHO, SpotifyStreamResolveMethod.ECHO.name)
                        val isFlac = SpotifyEchoResolver.isFlac(echo.chosenFile.format)
                        return@runBlocking DirectStream(
                            uri = SpotifyEchoResolver.streamUriFor(echo).toString(),
                            mimeType = if (isFlac) "audio/flac" else "audio/ogg",
                            codecs = if (isFlac) "flac" else "vorbis",
                            contentLength = null,
                            label = "Spotify Echo ${SpotifyEchoResolver.formatLabel(echo.chosenFile.format)}",
                            source = AudioSourceType.SPOTIFY,
                            matchedTitle = match.title,
                            matchedArtist = match.artist,
                            matchedAlbum = query.album,
                            matchedDurationMs = match.durationMs,
                            trustedDirectId = trustedId,
                            sampleRate = null,
                            bitDepth = null,
                            expiresAtEpochMs = echo.cdnUrls.mapNotNull(StreamUrlExpiry::fromUrl).minOrNull(),
                        )
                    }
                    Timber.tag("MusicService").w("Spotify Echo: falling back to the web-player method for \"%s\"", query.title)
                }
                val cdnUrl =
                    SpotifyWebStreamer.resolveStreamUrl(
                        trackUri = trackUri,
                        deviceId = SpotifyStreamingAccount.deviceId(context),
                    ) ?: return@runBlocking null
                onResolved(mediaId, trackUri, SpotifyStreamResolveMethod.STOPIFY, SpotifyStreamResolveMethod.STOPIFY.name)
                DirectStream(
                    uri = cdnUrl,
                    mimeType = "audio/mp4",
                    codecs = "mp4a.40.2",
                    contentLength = null,
                    label = "Spotify Direct AAC 128",
                    source = AudioSourceType.SPOTIFY,
                    matchedTitle = match.title,
                    matchedArtist = match.artist,
                    matchedAlbum = query.album,
                    matchedDurationMs = match.durationMs,
                    trustedDirectId = trustedId,
                    sampleRate = null,
                    bitDepth = null,
                    expiresAtEpochMs = StreamUrlExpiry.fromUrl(cdnUrl),
                )
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Timber.tag("MusicService").w("Spotify source failed category=%s",
                (error as? SpotifyLibrespotAudioException)?.failure?.name ?: error.javaClass.simpleName)
            null
        }
    }

    fun cacheAllowed(tag: String?, lowData: Boolean): Boolean =
        if (SpotifyStreamingAccount.effectiveEngine(context.dataStore) == SpotifyStreamEngine.LIBRESPOT) {
            librespot.cacheAllowed(tag, lowData)
        } else {
            tag == SpotifyStreamingAccount.resolveMethod(context.dataStore).name
        }

    fun streamAllowed(stream: DirectStream, lowData: Boolean): Boolean {
        val uri = Uri.parse(stream.uri)
        return if (SpotifyStreamingAccount.effectiveEngine(context.dataStore) == SpotifyStreamEngine.LIBRESPOT) {
            librespot.stream(uri)?.let { librespot.cacheAllowed(it.tag, lowData) } == true
        } else uri.scheme != SpotifyLibrespotAudioPolicy.SCHEME
    }
}
