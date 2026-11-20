package app.natune.shared.data.download

import app.natune.shared.data.provider.AudioResolution
import app.natune.shared.data.provider.OrderedAudioResolver
import app.natune.shared.domain.download.DownloadSourceResolver
import app.natune.shared.domain.download.DownloadTask
import app.natune.shared.domain.repository.AudioPurpose
import app.natune.shared.domain.repository.AudioResolveRequest
import app.natune.shared.domain.repository.ContentItem
import app.natune.shared.domain.repository.ContentRef
import app.natune.shared.domain.repository.PreparedAudio
import app.natune.shared.domain.source.AudioSourceType
import app.natune.shared.domain.source.PlaybackSourceSnapshot
import app.natune.shared.domain.source.TrackMatching

/**
 * The download side of the shared provider chain, the portable form of the retained download path through
 * `YTPlayerUtils`/`InnerTubeX`/native providers: each task resolves through [OrderedAudioResolver] with
 * [AudioPurpose.DOWNLOAD] under the user's download source order, YouTube last. A non-progressive outcome
 * (native recipe, encrypted Deezer, Tidal manifest) fails the task honestly because the file store never writes
 * a format it cannot play back.
 */
class ManagedDownloadResolver(
    private val resolver: OrderedAudioResolver,
    private val snapshot: suspend () -> PlaybackSourceSnapshot,
    private val itemOf: suspend (ContentRef) -> ContentItem?,
    private val directIds: suspend (ContentRef) -> Map<AudioSourceType, String> = { emptyMap() },
    private val isrc: suspend (ContentRef) -> String? = { null },
    private val qualities: suspend (ContentRef) -> Map<AudioSourceType, String> = { emptyMap() },
) : DownloadSourceResolver {
    override suspend fun resolve(task: DownloadTask): PreparedAudio? {
        val item = itemOf(task.ref) ?: error("Unknown song ${task.mediaId}")
        val target = TrackMatching.Target(
            title = item.title,
            artists = item.artists.map { it.name }.ifEmpty { listOf(item.subtitle) }.filter { it.isNotBlank() },
            album = item.album,
            durationMs = item.durationMs,
        )
        val request = AudioResolveRequest(
            mediaId = task.mediaId, target = target, directIds = directIds(task.ref),
            isrc = isrc(task.ref), qualities = qualities(task.ref), purpose = AudioPurpose.DOWNLOAD,
        )
        return when (val outcome = resolver.resolve(request, snapshot())) {
            is AudioResolution.Resolved -> outcome.audio
            is AudioResolution.Unavailable -> null
            is AudioResolution.NativeRoute -> error("Native routes cannot feed the download file store")
        }
    }
}
