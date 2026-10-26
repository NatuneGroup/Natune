/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

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
