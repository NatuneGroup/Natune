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
 * scheme/host/path as separate fields and nothing else: no query (OAuth codes, tokens), no
 * fragment, no userinfo. Field form rather than "https://…" because the shared redactor blanks
 * whole URLs — this keeps the failing endpoint readable while staying credential-free.
 */
internal fun authLogTarget(rawUrl: String?): String {
    if (rawUrl.isNullOrBlank()) return "url=(none)"
    val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return "url=(unparseable)"
    val scheme = uri.scheme?.lowercase() ?: return "url=(unparseable)"
    val host = uri.host?.lowercase() ?: return "scheme=$scheme"
    return "scheme=$scheme host=$host path=${uri.rawPath.orEmpty()}"
}

internal fun authNavErrorLine(
    errorCode: Int,
    isMainFrame: Boolean,
    url: String?,
): String = "error code=$errorCode frame=${if (isMainFrame) "main" else "sub"} ${authLogTarget(url)}"

internal fun authHttpErrorLine(
    statusCode: Int,
    isMainFrame: Boolean,
    url: String?,
): String = "http=$statusCode frame=${if (isMainFrame) "main" else "sub"} ${authLogTarget(url)}"

// The console *message* never enters the line: it is page text and can carry anything the site
// chooses to print, including data a redactor cannot classify.
internal fun authConsoleLine(
    level: String,
    sourceUrl: String?,
    lineNumber: Int,
): String = "console.$level ${authLogTarget(sourceUrl)} line=$lineNumber"

private val SIGN_IN_POPUP_PROVIDERS = setOf("spotify")

/**
 * Multi-window belongs only to sign-ins that need gesture popups (Spotify's social-login buttons).
 * Every other provider keeps the original single window: a popup with the shared diagnostic client
 * would bypass the provider's own redirect interception, cookie capture and JS bridges.
 */
internal fun authPopupsEnabled(provider: String): Boolean = provider in SIGN_IN_POPUP_PROVIDERS

/**
 * Only a verified main-frame GET may be replayed into the parent window. A form_post OAuth return
 * or an unknown-method navigation reissued as loadUrl would arrive as a bare GET and silently lose
 * its body — it stays in the popup, which shares the session jar and cookie capture.
 */
internal fun authPopupHandoffAllowed(
    isMainFrame: Boolean,
    method: String?,
): Boolean = isMainFrame && method?.equals("GET", ignoreCase = true) == true
