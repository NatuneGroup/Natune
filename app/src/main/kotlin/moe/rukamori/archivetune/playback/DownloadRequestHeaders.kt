package moe.rukamori.archivetune.playback

import androidx.core.net.toUri

/**
 * Headers a resolved stream URL cannot be fetched without.
 *
 * Only the Qobuz backup mirror needs one today: `mlc-ytify.kouzu.in` rate-limits any
 * request that arrives without `x-request-source`, and that applies to the byte fetch as
 * much as to the resolve call. Every other source serves plain authenticated-by-URL CDN
 * links, so the map stays empty for them.
 */
internal fun requiredHeadersFor(uri: String): Map<String, String> =
    if (runCatching { uri.toUri().host }.getOrNull()?.endsWith("kouzu.in") == true) {
        mapOf("x-request-source" to "muzo")
    } else {
        emptyMap()
    }
