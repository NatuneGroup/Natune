package moe.rukamori.archivetune.shared.data.lyrics

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Finds the Apple Music web player bearer in the pages Apple serves. Port of the canvas
 * `AppleWebPlayToken` rules, which the Android Paxsenix Apple Music path relied on through the app.
 *
 * Two properties of Apple's pages shape the rules:
 *  - The entry chunk has been renamed (`index-<hash>.js` became `index~<hash>.js`), so both separators
 *    are accepted.
 *  - A JWT-shaped string is not the bearer by itself. Only an `AMPWebPlay` token whose expiry is
 *    readable and at least a minute in the future is accepted. Anything else is reported as no token,
 *    because a token that cannot be read would be refreshed on every use.
 *
 * Expiry is supplied by the caller as epoch seconds, so this stays free of any clock.
 */
@OptIn(ExperimentalEncodingApi::class)
object AppleWebPlayJwt {
    private const val ISSUER = "AMPWebPlay"

    // A candidate has to outlive the request it is for, not merely the clock.
    private const val MIN_LIFETIME_SEC = 60L

    // Each miss costs a multi-megabyte download, so only the first few candidate bundles are tried.
    private const val MAX_BUNDLE_CANDIDATES = 4

    private val JWT_REGEX = Regex("""eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")

    private val INDEX_BUNDLE_REGEX =
        Regex("""(?:src|href)=["']([^"']*index[~\-][A-Za-z0-9._~-]+\.js)["']""")

    private val ANY_BUNDLE_REGEX = Regex("""(?:src|data-src)=["']([^"']*/assets/[^"']+\.js)["']""")

    // Both claim patterns end on the capture group, never on a quote, so a live token decodes its expiry.
    private val ISSUER_CLAIM_REGEX = Regex(""""iss"\s*:\s*"([^"]+)""")
    private val EXP_CLAIM_REGEX = Regex(""""exp"\s*:\s*(\d+)""")

    /** Script URLs in [html], entry chunk first, as absolute URLs. */
    fun bundleUrls(html: String): List<String> {
        val indexBundles = INDEX_BUNDLE_REGEX.findAll(html).map { it.groupValues[1] }
        val anyBundles = ANY_BUNDLE_REGEX.findAll(html).map { it.groupValues[1] }
        return (indexBundles + anyBundles)
            .distinct()
            .map(::absoluteUrl)
            .take(MAX_BUNDLE_CANDIDATES)
            .toList()
    }

    /** The first `AMPWebPlay` JWT in [text] that is still valid at [nowSec], or null. */
    fun select(
        text: String,
        nowSec: Long,
    ): String? =
        JWT_REGEX
            .findAll(text)
            .map { it.value }
            .distinct()
            .firstOrNull { jwt -> issuer(jwt) == ISSUER && expSec(jwt) > nowSec + MIN_LIFETIME_SEC }

    /** The `iss` claim of [jwt], or null when its payload is not readable. */
    fun issuer(jwt: String): String? = payload(jwt)?.let { ISSUER_CLAIM_REGEX.find(it)?.groupValues?.get(1) }

    /** The `exp` claim of [jwt] in epoch seconds, or 0 when it is absent or unreadable. */
    fun expSec(jwt: String): Long =
        payload(jwt)
            ?.let { EXP_CLAIM_REGEX.find(it)?.groupValues?.get(1) }
            ?.toLongOrNull()
            ?: 0L

    private fun payload(jwt: String): String? {
        val parts = jwt.split(".")
        if (parts.size != 3) return null
        return try {
            val normalized = parts[1].replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            Base64.Default.decode(padded).decodeToString()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun absoluteUrl(rawUrl: String): String =
        when {
            rawUrl.startsWith("http") -> rawUrl
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("/") -> "https://music.apple.com$rawUrl"
            else -> "https://music.apple.com/$rawUrl"
        }
}
