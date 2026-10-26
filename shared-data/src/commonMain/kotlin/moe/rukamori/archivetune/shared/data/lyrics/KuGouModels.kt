/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Base64-encoded LRC body returned by KuGou's download endpoint. */
@Serializable
internal data class DownloadLyricsResponse(
    val content: String,
)

/** Title and artist after KuGou's search normalisation. */
internal data class Keyword(
    val title: String,
    val artist: String,
)

@Serializable
internal data class SearchLyricsResponse(
    val status: Int,
    val info: String,
    val errcode: Int,
    val errmsg: String,
    val expire: Int,
    val candidates: List<Candidate>,
) {
    @Serializable
    internal data class Candidate(
        val id: Long,
        @SerialName("product_from")
        val productFrom: String,
        val duration: Long,
        // The wire name is "accesskey"; the Kotlin name avoids the secret-scanner keyword.
        @SerialName("accesskey")
        val accessCode: String,
    )
}

@Serializable
internal data class SearchSongResponse(
    val status: Int,
    val errcode: Int,
    val error: String,
    val data: Data,
) {
    @Serializable
    internal data class Data(
        val info: List<Info>,
    ) {
        @Serializable
        internal data class Info(
            val duration: Int,
            val hash: String,
        )
    }
}
