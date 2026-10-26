package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * The JSON envelope the BetterLyrics endpoints may return instead of a bare XML body. The `lyrics`
 * alias is read as `ttml` too, as on Android. The Android search models (`Track`, `SearchResponse`)
 * are not ported: nothing on the lyrics path reads them.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class BetterLyricsTtmlResponse(
    @JsonNames("ttml", "lyrics")
    val ttml: String = "",
    @SerialName("provider")
    val provider: String? = null,
)
