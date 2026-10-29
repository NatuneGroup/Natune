package moe.rukamori.archivetune.shared.data.playback

import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentItem

/** Artwork follows media and its actual source, not the queue occurrence or the playback poll timestamp. */
internal data class NowPlayingArtworkKey(val mediaId: String, val uri: String) {
    companion object {
        fun from(mediaId: String, uri: String?): NowPlayingArtworkKey? {
            val source = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if (mediaId.isBlank()) null else NowPlayingArtworkKey(mediaId, source)
        }
    }
}

internal data class NowPlayingMetadata(
    val occurrenceId: String,
    val mediaId: String,
    val playbackGeneration: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val artworkKey: NowPlayingArtworkKey?,
)

internal data class NowPlayingTransport(
    val playbackGeneration: Long,
    val discontinuityGeneration: Long,
    val rate: Double,
    val defaultRate: Double,
    val playWhenReady: Boolean,
)

/**
 * Only values the system needs to be told about. Elapsed time, sample timestamps and buffered ranges deliberately
 * do not participate: the system advances its own elapsed-time anchor between transport changes.
 */
internal data class NowPlayingProjection(
    val metadata: NowPlayingMetadata,
    val transport: NowPlayingTransport,
    val seekable: Boolean,
) {
    fun changesFrom(previous: NowPlayingProjection?): NowPlayingChanges = NowPlayingChanges(
        metadata = metadata != previous?.metadata,
        transport = transport != previous?.transport,
        seekability = seekable != previous?.seekable,
    )
}

internal data class NowPlayingChanges(val metadata: Boolean, val transport: Boolean, val seekability: Boolean) {
    val publishInfo: Boolean get() = metadata || transport
}

internal fun nowPlayingProjection(snapshot: PlaybackSnapshot, item: ContentItem?): NowPlayingProjection =
    NowPlayingProjection(
        metadata = NowPlayingMetadata(
            occurrenceId = snapshot.occurrenceId,
            mediaId = snapshot.mediaId,
            playbackGeneration = snapshot.playbackGeneration,
            title = item?.title,
            artist = item?.subtitle,
            album = item?.album,
            durationMs = snapshot.durationMs,
            artworkKey = NowPlayingArtworkKey.from(snapshot.mediaId, item?.artworkUri),
        ),
        transport = NowPlayingTransport(
            playbackGeneration = snapshot.playbackGeneration,
            discontinuityGeneration = snapshot.discontinuityGeneration,
            rate = if (snapshot.isAudioAdvancing && !snapshot.isBuffering) snapshot.playbackSpeed.toDouble() else 0.0,
            defaultRate = snapshot.playbackSpeed.toDouble(),
            playWhenReady = snapshot.playWhenReady,
        ),
        seekable = snapshot.isSeekable,
    )
