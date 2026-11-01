package moe.rukamori.archivetune.ui.component

internal data class AuthWebViewDarkeningPolicy(
    val disableViewForceDark: Boolean,
    val disableAlgorithmicDarkening: Boolean,
    val disableLegacyForceDark: Boolean,
)

/** WebView capabilities, unlike View's force-dark property, are not determined by the OS version. */
internal fun authWebViewDarkeningPolicy(
    sdkInt: Int,
    algorithmicDarkeningSupported: Boolean,
    legacyForceDarkSupported: Boolean,
): AuthWebViewDarkeningPolicy = AuthWebViewDarkeningPolicy(
    disableViewForceDark = sdkInt >= 29,
    disableAlgorithmicDarkening = algorithmicDarkeningSupported,
    disableLegacyForceDark = legacyForceDarkSupported,
)

/** Ignore initial/empty layout and keyboard dismissal; only a smaller or reflowed viewport needs help. */
internal fun shouldRevealFocusedAuthInput(
    oldWidth: Int,
    oldHeight: Int,
    width: Int,
    height: Int,
): Boolean = oldWidth > 0 && oldHeight > 0 && width > 0 && height > 0 &&
    (height < oldHeight || width != oldWidth)

private val WEBVIEW_UA_MARKER = Regex(""";?\s+wv\)""")

/**
 * Turns the engine's own default UA into a plain Chrome Mobile UA. The frozen UA the sign-in used
 * to send ("Chrome/131.0.0.0" on every device) disagreed with the Sec-CH-UA client hints the real
 * WebView emits, which is the kind of mismatch Spotify's bot check answers with its generic
 * "Something went wrong" page. Deriving from [android.webkit.WebSettings.getDefaultUserAgent] keeps
 * the UA and the hints on the same engine build; the `; wv` in-app marker and the `Version/4.0`
 * token are stripped because desktop/mobile Chrome never sends them.
 */
internal fun sanitizeAuthWebViewUserAgent(userAgent: String): String =
    userAgent
        .replace(WEBVIEW_UA_MARKER, ")")
        .replace("Version/4.0 ", "")

/** A window.open the page fired itself is never a sign-in step the user asked for. */
internal fun authPopupAllowed(isUserGesture: Boolean): Boolean = isUserGesture

/** A popup that never navigated is a dead window stacked over the form, not part of the flow. */
internal fun isBlankPopupUrl(url: String?): Boolean =
    url.isNullOrBlank() || url == "about:blank"

/**
 * Whether [host] is one of the flow's own hosts, including subdomains. A gesture popup that
 * navigates back into the sign-in flow continues in the parent view instead of staying stacked
 * over it; external provider popups keep their own window.
 */
internal fun hostInLoginFlow(host: String?, flowHosts: Collection<String>): Boolean {
    if (host.isNullOrBlank()) return false
    val normalized = host.lowercase().trimEnd('.')
    return flowHosts.any { suffix ->
        normalized == suffix || normalized.endsWith(".$suffix")
    }
}

/** Lowercase host of a page/script URL for log filtering, or null when it has none. */
internal fun authLogHost(rawUrl: String?): String? {
    if (rawUrl.isNullOrBlank()) return null
    return runCatching { java.net.URI(rawUrl).host?.lowercase() }.getOrNull()
}

/**
 * scheme+host+path and nothing else: no query (OAuth codes, tokens), no fragment, no userinfo.
 * Diagnostic lines keep enough of the failing URL to identify the endpoint and nothing in it.
 */
internal fun authLogUrl(rawUrl: String?): String {
    if (rawUrl.isNullOrBlank()) return "(none)"
    val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return "(unparseable)"
    val scheme = uri.scheme?.lowercase() ?: return "(unparseable)"
    val host = uri.host?.lowercase() ?: return "$scheme://"
    return "$scheme://$host${uri.rawPath.orEmpty()}"
}
