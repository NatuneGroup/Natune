package moe.rukamori.archivetune.modules

import moe.rukamori.archivetune.shared.domain.playback.PlaybackHeaders
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderRepository
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android binding for OrderedAudioResolver consumers. The shared progressive transport can accept
 * direct audio with its declared headers. Segmented modules remain with ModuleMediaSourceFactory,
 * which owns their inline manifests, refresh, cache identities and download bindings.
 */
@Singleton
class ModuleAudioProviderRepository @Inject constructor(private val resolver: ResolveModuleAudioUseCase) : AudioProviderRepository {
    override val source = AudioSourceType.MODULES
    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val target = request.target
        val selection = resolver.resolve(request.mediaId, ModuleMatchMetadata(target.title, target.artists, target.album,
            (target.durationMs?.div(1000) ?: 0).toInt(), null, true)) ?: return null
        val value = selection.stream
        if (value.inlineManifest != null || value.mimeType.substringBefore(';').lowercase() in
            setOf("application/dash+xml", "application/x-mpegurl", "application/vnd.apple.mpegurl")) return null
        return PreparedAudio.Progressive(DirectStream(value.url, value.mimeType, value.codecs,
            value.contentLength.takeIf { it > 0 }, selection.module.manifest.name, source,
            matchedTitle = selection.metadata?.title, matchedArtist = selection.metadata?.artists?.joinToString(", "),
            matchedAlbum = selection.metadata?.album, matchedDurationMs = selection.metadata?.durationSeconds?.toLong()?.times(1000),
            sampleRate = value.sampleRate, headers = PlaybackHeaders.of(value.headers), expiresAtEpochMs = value.expiresAtMs))
    }
}
