package app.natune.acoustics

import app.natune.constants.AudioSourceType

// Internal source identity may contain a signed URI. Never put it in state, logs or exceptions.
class AcousticSourceSnapshot(
    val mediaId: String,
    val source: AudioSourceType,
    val cacheKey: String,
    val uri: String?,
    val expiresAtMs: Long,
    val contentLength: Long?,
    val advertisedSampleRate: Int?,
    val claimedLossless: Boolean,
) {
    fun matches(other: AcousticSourceSnapshot?): Boolean =
        other != null && mediaId == other.mediaId && source == other.source &&
            cacheKey == other.cacheKey && uri == other.uri && expiresAtMs == other.expiresAtMs &&
            contentLength == other.contentLength && advertisedSampleRate == other.advertisedSampleRate &&
            claimedLossless == other.claimedLossless
}

data class AcousticCacheExtent(
    val position: Long,
    val length: Long,
    val fileLength: Long,
    val regular: () -> Boolean,
    val cached: Boolean,
)

object AcousticSourcePolicy {
    fun selectedSnapshot(mediaId: String, snapshot: AcousticSourceSnapshot?): AcousticSourceSnapshot? =
        snapshot?.takeIf { it.mediaId == mediaId }

    fun cacheMatches(
        source: AudioSourceType,
        length: Long,
        sampleRate: Int?,
        lossless: Boolean,
        current: AcousticSourceSnapshot?,
    ): Boolean =
        length > 0 && (current == null ||
            (current.source == source &&
                (current.contentLength == null || current.contentLength == length) &&
                (current.advertisedSampleRate == null || current.advertisedSampleRate == sampleRate) &&
                current.claimedLossless == lossless))

    fun completeExtents(length: Long, extents: List<AcousticCacheExtent>, checkActive: () -> Unit): Boolean {
        if (length <= 0 || extents.size > 2048) return false
        var end = 0L
        for (extent in extents) {
            checkActive()
            if (!extent.cached || extent.position != end || extent.length <= 0 ||
                extent.length > length - end || extent.fileLength != extent.length || !extent.regular()
            ) return false
            end += extent.length
        }
        return end == length
    }
}
