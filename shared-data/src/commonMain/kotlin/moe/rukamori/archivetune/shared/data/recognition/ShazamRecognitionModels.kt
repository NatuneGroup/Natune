/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 *
 * Wire shapes of the Shazam discovery endpoint, ported from the baseline shazamkit models. Only the fields the
 * recognition mapping reads are declared, so the decoder ignores everything else the provider sends. Provider links
 * (Apple Music, Spotify, YouTube) are not declared because the domain model never carried them.
 */

package moe.rukamori.archivetune.shared.data.recognition

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The discovery request body. Field names are the provider's wire names. */
@Serializable
internal data class ShazamDiscoveryRequest(
    @SerialName("geolocation")
    val geolocation: ShazamDiscoveryGeolocation,
    @SerialName("signature")
    val signature: ShazamDiscoverySignature,
    @SerialName("timestamp")
    val timestamp: Long,
    @SerialName("timezone")
    val timezone: String,
)

@Serializable
internal data class ShazamDiscoveryGeolocation(
    @SerialName("altitude")
    val altitude: Double,
    @SerialName("latitude")
    val latitude: Double,
    @SerialName("longitude")
    val longitude: Double,
)

@Serializable
internal data class ShazamDiscoverySignature(
    @SerialName("samplems")
    val samplems: Long,
    @SerialName("timestamp")
    val timestamp: Long,
    @SerialName("uri")
    val uri: String,
)

/**
 * The discovery reply. A reply without a [track] is an unmatched answer, as in the baseline; the mapping decides every
 * other shape.
 */
@Serializable
internal data class ShazamDiscoveryReply(
    @SerialName("tagid")
    val tagid: String? = null,
    @SerialName("track")
    val track: ShazamDiscoveryTrack? = null,
)

@Serializable
internal data class ShazamDiscoveryTrack(
    @SerialName("key")
    val key: String? = null,
    @SerialName("title")
    val title: String? = null,
    @SerialName("subtitle")
    val subtitle: String? = null,
    @SerialName("images")
    val images: ShazamDiscoveryImages? = null,
    @SerialName("sections")
    val sections: List<ShazamDiscoverySection?>? = null,
    @SerialName("url")
    val url: String? = null,
    @SerialName("isrc")
    val isrc: String? = null,
    @SerialName("genres")
    val genres: ShazamDiscoveryGenres? = null,
)

@Serializable
internal data class ShazamDiscoveryImages(
    @SerialName("coverart")
    val coverart: String? = null,
    @SerialName("coverarthq")
    val coverarthq: String? = null,
)

@Serializable
internal data class ShazamDiscoverySection(
    @SerialName("type")
    val type: String? = null,
    @SerialName("metadata")
    val metadata: List<ShazamDiscoveryMetadata?>? = null,
    @SerialName("text")
    val text: List<String>? = null,
)

@Serializable
internal data class ShazamDiscoveryMetadata(
    @SerialName("title")
    val title: String? = null,
    @SerialName("text")
    val text: String? = null,
)

@Serializable
internal data class ShazamDiscoveryGenres(
    @SerialName("primary")
    val primary: String? = null,
)
