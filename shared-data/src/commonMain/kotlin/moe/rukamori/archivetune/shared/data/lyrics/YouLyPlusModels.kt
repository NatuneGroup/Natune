package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.serialization.Serializable

/** Wire models of the YouLyPlus mirrors, mirroring the Android `YouLyPlusModels.kt`. */
@Serializable
internal data class YouLyPlusTtmlResponse(
    val ttml: String? = null,
)

@Serializable
internal data class YouLyPlusLyricsResponse(
    val type: String? = null,
    val lyrics: List<YouLyPlusLine> = emptyList(),
)

@Serializable
internal data class YouLyPlusLine(
    val time: Long? = null,
    val duration: Long? = null,
    val text: String? = null,
    val syllabus: List<YouLyPlusSyllable>? = null,
)

@Serializable
internal data class YouLyPlusSyllable(
    val time: Long? = null,
    val duration: Long? = null,
    val text: String? = null,
    val isBackground: Boolean = false,
)
