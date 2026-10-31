package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.domain.platform.PrivateCookie
import moe.rukamori.archivetune.shared.ui.home.HomeSource

/**
 * Artwork providers the user can prioritise. The enum order is not the priority — the stored order
 * decides which provider is tried first. Ported from the Android `PreferredArtworkProvider` so every
 * host reads the same `artworkProviderOrder` value.
 */
enum class PreferredArtworkProvider {
    LOCAL_EMBEDDED,
    ORIGINAL_METADATA,
    TIDAL,
    SPOTIFY_CANVAS,
    ARCHIVETUNE_CANVAS,
}

val DefaultArtworkProviderOrder: List<PreferredArtworkProvider> = listOf(
    PreferredArtworkProvider.LOCAL_EMBEDDED,
    PreferredArtworkProvider.ORIGINAL_METADATA,
    PreferredArtworkProvider.TIDAL,
    PreferredArtworkProvider.SPOTIFY_CANVAS,
    PreferredArtworkProvider.ARCHIVETUNE_CANVAS,
)

/**
 * Decodes a stored provider order. A blank value is the default order, unknown and repeated names are
 * dropped, and providers missing from the stored order are appended in default order, so an upgrade
 * never hides a provider.
 */
fun deserializeArtworkProviderOrder(order: String?): List<PreferredArtworkProvider> {
    if (order.isNullOrBlank()) return DefaultArtworkProviderOrder
    val parsed = order.split(",")
        .mapNotNull { name -> PreferredArtworkProvider.entries.firstOrNull { it.name == name.trim() } }
        .distinct()
    return parsed + DefaultArtworkProviderOrder.filterNot(parsed::contains)
}

/** Encodes a provider order as stored: repeats dropped, missing providers appended in default order. */
fun List<PreferredArtworkProvider>.serializeArtworkProviderOrder(): String =
    (distinct() + DefaultArtworkProviderOrder.filterNot(distinct()::contains)).joinToString(",") { it.name }

/**
 * Decodes a stored playlist-tag order against the tags that exist. Unknown ids are dropped and tags
 * missing from the stored order keep their catalog order at the end, so a restored or stale value never
 * hides a tag.
 */
fun String.toPlaylistTagOrder(availableTagIds: List<String>): List<String> {
    val available = availableTagIds.toSet()
    val saved = split(",").map(String::trim).filter { it.isNotEmpty() && it in available }.distinct()
    return saved + availableTagIds.filterNot(saved::contains)
}

/** Encodes a playlist-tag order as stored; repeats are dropped. */
fun List<String>.toPlaylistTagPreference(): String = distinct().joinToString(",")

/** Parses the stored home-source set. An unset or unparseable value is empty, which reads as "not configured". */
fun homeSourcesFromPreference(stored: String): List<HomeSource> =
    stored.split(',')
        .mapNotNull { name -> HomeSource.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }
        .distinct()

/**
 * The set to store, in [HomeSource.entries] order: the switcher lists rows in the stored order, so a set
 * assembled by toggling would otherwise shuffle rows as pages are removed and re-added.
 */
fun canonicalHomeSources(selected: List<HomeSource>): List<HomeSource> = HomeSource.entries.filter { it in selected }

/**
 * The value of one cookie from a login context. A host-only cookie is more specific than the shared
 * domain one, so the longest matching domain wins when both are present.
 */
fun preferredCookieValue(cookies: List<PrivateCookie>, name: String): String =
    cookies.filter { it.name == name }.maxByOrNull { it.domain.removePrefix(".").length }?.value?.reveal().orEmpty()
