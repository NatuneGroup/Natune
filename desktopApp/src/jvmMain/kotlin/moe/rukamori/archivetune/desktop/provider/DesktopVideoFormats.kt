package moe.rukamori.archivetune.desktop.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** A music video's stream for the player's video artwork, or the answer that the id is not a music video. */
internal sealed interface DesktopVideoArtworkResult {
    class Stream(val url: String, val userAgent: String?, val height: Int) : DesktopVideoArtworkResult

    /** YouTube serves this id as a song (an art track), so the player keeps the artwork, as Android does. */
    data object NotAMusicVideo : DesktopVideoArtworkResult
}

/** One video-only adaptive format of a player response. */
internal data class DesktopVideoFormat(
    val itag: Int,
    val url: String?,
    val signatureCipher: String?,
    val cipher: String?,
    val mimeType: String,
    val height: Int,
    val bitrate: Int,
) {
    val codec: String?
        get() = extractCodec(mimeType)
}

/** Video adaptive formats with a usable height, in the order YouTube lists them. */
internal fun JsonObject.videoFormats(): List<DesktopVideoFormat> {
    val streamingData = this["streamingData"] as? JsonObject ?: return emptyList()
    val adaptive = streamingData["adaptiveFormats"] as? JsonArray ?: return emptyList()
    return adaptive.mapNotNull { element ->
        val format = element as? JsonObject ?: return@mapNotNull null
        val mimeType = format.stringOf("mimeType")?.takeIf { it.startsWith("video/") } ?: return@mapNotNull null
        DesktopVideoFormat(
            itag = format.intOf("itag") ?: return@mapNotNull null,
            url = format.stringOf("url"),
            signatureCipher = format.stringOf("signatureCipher"),
            cipher = format.stringOf("cipher"),
            mimeType = mimeType,
            height = format.intOf("height")?.takeIf { it > 0 } ?: return@mapNotNull null,
            bitrate = format.intOf("bitrate") ?: 0,
        )
    }
}

/**
 * The formats to try, best first, under Android's rule: the tallest frame within [maxHeight], or the smallest one when
 * every frame is taller. Only H.264 and VP9 are kept, the codecs the bundled FFmpeg decodes in software; H.264 wins a
 * tie because it costs the least to decode, and a format with a direct URL wins over one that needs deciphering.
 */
internal fun rankVideoFormats(formats: List<DesktopVideoFormat>, maxHeight: Int): List<DesktopVideoFormat> {
    val decodable = formats.filter { it.codecRank() != null && (it.url != null || it.signatureCipher != null || it.cipher != null) }
    val withinCeiling = decodable.filter { it.height <= maxHeight }
    val pool = withinCeiling.ifEmpty { decodable.minByOrNull { it.height }?.let(::listOf).orEmpty() }
    return pool.sortedWith(
        compareByDescending<DesktopVideoFormat> { it.height }
            .thenBy { it.codecRank() }
            .thenByDescending { it.url != null }
            .thenByDescending { it.bitrate },
    )
}

private fun DesktopVideoFormat.codecRank(): Int? {
    val name = codec?.lowercase() ?: return null
    return when {
        name.startsWith("avc1") -> 0
        name.startsWith("vp9") || name.startsWith("vp09") -> 1
        else -> null
    }
}

/**
 * Frames up to 720 lines, below Android's 1080-line Auto ceiling: the bundled FFmpeg is built without x86 assembly, so
 * a 1080p software decode would cost several times the CPU for a picture shown at cover size.
 */
internal const val DESKTOP_VIDEO_MAX_HEIGHT = 720
