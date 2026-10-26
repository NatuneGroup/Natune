package moe.rukamori.archivetune.desktop

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.data.persistence.PortableStoredSongRow
import moe.rukamori.archivetune.shared.data.persistence.storedOccurrence
import moe.rukamori.archivetune.shared.domain.mediainfo.MediaHeaderParser
import moe.rukamori.archivetune.shared.domain.mediainfo.MediaTechnicalInfo
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.MediaInfoSongUi
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.MediaInfoStreamFormat
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.MediaInfoUi
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.TrackInfoUi

private const val MEDIA_HEADER_BYTES = 256 * 1024

/**
 * The facts behind `track_info/<id>` and `media_info/<id>` on this host. Song facts come from the local
 * library; the stream format comes from the file itself — the stored download or the local file the id
 * names — parsed by [MediaHeaderParser], never a quality-tier guess. Provider video details do not load
 * here because this host has no browse-item transport for them, so `video*` stays null rather than
 * display nothing as something. The Android-only live pipeline and AudioTrack sections are null for the
 * same reason the screen hides them.
 */
internal class DesktopMediaInfoOwner(
    private val library: RoomLibraryRepository,
    private val downloads: DesktopDownloadsOwner,
    private val localMedia: DesktopLocalMediaOwner,
    private val playingMediaId: () -> String?,
) {
    /** The library item the id names, or null when neither the library nor a stored row knows it. */
    suspend fun itemOf(provider: String?, mediaId: String): ContentItem? {
        library.findSong(mediaId, provider)?.let { return it }
        return downloads.listedRows()
            .firstOrNull { it.mediaId == mediaId && (provider == null || it.provider == provider) }
            ?.storedOccurrence()?.item
    }

    /** The stored file the id's audio lives in: a local-file path, else a committed download file. */
    suspend fun fileOf(provider: String?, mediaId: String): File? {
        if (provider == DesktopLocalMediaOwner.LOCAL_PROVIDER) {
            return localMedia.localPathOf(mediaId)?.let(::File)
        }
        val rows = downloads.listedRows()
            .filter { it.mediaId == mediaId && (provider == null || it.provider == provider) }
        for (row in rows) {
            val source = row.source.toAudioSource() ?: continue
            val path = downloads.fileOf(row.provider, row.mediaId, source, row.quality ?: continue) ?: continue
            return path.toFile()
        }
        return null
    }

    /** The header facts of [file], or null when the header states none. */
    fun technicalOf(file: File, fallbackBytes: Long? = null): MediaTechnicalInfo? = try {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val header = ByteArray(minOf(MEDIA_HEADER_BYTES.toLong(), length).toInt())
            val read = raf.read(header)
            if (read <= 0) null else MediaHeaderParser.parse(header.copyOf(read), length)
        }
    } catch (_: Exception) {
        null
    }

    suspend fun trackInfo(trackId: String): TrackInfoUi {
        val item = itemOf(null, trackId)
        val file = fileOf(item?.ref?.provider, trackId)
        val technical = file?.let { technicalOf(it) }
        return TrackInfoUi(
            trackId = trackId,
            title = item?.title ?: "",
            artists = item?.artists?.joinToString(", ") { it.name } ?: item?.subtitle ?: "",
            album = item?.album,
            durationMs = item?.durationMs ?: technical?.durationMs,
            artworkUrl = item?.artworkUri,
            format = technical?.let { streamFormat(it, file.length()) },
        )
    }

    suspend fun mediaInfo(mediaId: String, isLive: Boolean): MediaInfoUi {
        val item = itemOf(null, mediaId)
        val file = fileOf(item?.ref?.provider, mediaId)
        val technical = file?.let { technicalOf(it) }
        return MediaInfoUi(
            mediaId = mediaId,
            song = item?.let {
                MediaInfoSongUi(
                    title = it.title,
                    artists = it.artists.map { a -> a.name },
                    thumbnailUrl = it.artworkUri,
                    durationSeconds = (it.durationMs ?: technical?.durationMs ?: 0L) / 1_000L,
                )
            },
            format = technical?.let { streamFormat(it, file?.length()) },
            fileSizeLabel = file?.takeIf { it.isFile }?.let { binaryBytes(it.length()) },
            isLocal = item?.ref?.provider == DesktopLocalMediaOwner.LOCAL_PROVIDER ||
                providerIsLocal(item?.ref?.provider, mediaId),
            isLiveTrack = isLive,
            playbackVolume = null,
            live = null,
            outputStats = null,
        )
    }

    /** A local ref's media id is its absolute path; a gone library row can still name a local file. */
    private fun providerIsLocal(provider: String?, mediaId: String): Boolean =
        provider == DesktopLocalMediaOwner.LOCAL_PROVIDER ||
            (provider == null && localMedia.localPathOf(mediaId) != null)

    /** Maps the file's own header facts to the format model every surface formats the same way. */
    private fun streamFormat(technical: MediaTechnicalInfo, contentLength: Long?): MediaInfoStreamFormat =
        MediaInfoStreamFormat(
            mimeType = technical.container.name,
            codecs = technical.codec.takeIf { it != moe.rukamori.archivetune.shared.domain.mediainfo.MediaCodec.UNKNOWN }?.name,
            bitrateBps = technical.bitrateBitsPerSecond?.toInt(),
            sampleRateHz = technical.sampleRate,
            contentLengthBytes = contentLength,
        )

    private fun binaryBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    private fun String.toAudioSource(): AudioSourceType? =
        AudioSourceType.entries.firstOrNull { it.name == this }
}
