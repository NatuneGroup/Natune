@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIPresentationController
import platform.UIKit.UIViewController
import platform.UIKit.presentationController
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyAllow
import platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyCancel
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * The MusicKit pair a finished Apple Music sign-in yields: the iTunes-store media user token (the
 * `0.` shape, never a JWT) and the developer token the page hands out, or null when the page kept it
 * to itself and the caller has to scrape one instead.
 */
data class IosAppleMusicTokens(val mediaUserToken: String, val developerToken: String?)

/**
 * The iOS form of the Android `settings/applemusic/login` WebView: a nonpersistent WKWebView sheet
 * on `https://music.apple.com/login`. Android polls the page's JavaScript every half second for
 * MusicKit's `musicUserToken`, falling back to the `media-user-token` localStorage entry; this sheet
 * evaluates the same probe until it returns a `0.`-shaped token, the user dismisses the sheet or the
 * two-minute bound runs out. HTTPS is the only navigable scheme, as on the other sheets.
 */
class IosAppleMusicLoginSheet(private val presenter: () -> UIViewController) {
    private var active = false

    /**
     * The captured pair, or null when the sheet closed before MusicKit exposed a user token. The
     * two-minute bound throws [kotlinx.coroutines.TimeoutCancellationException], so a caller that
     * only wants to report it must catch it before [kotlinx.coroutines.CancellationException].
     */
    suspend fun tokens(): IosAppleMusicTokens? = withContext(Dispatchers.Main) {
        check(!active) { "Apple Music sign-in already active" }
        active = true
        val configuration = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
        }
        val controller = UIViewController()
        val webView = controller.installAuthWebView(configuration)
        var dismissed = false
        try {
            val delegate = object : NSObject(), WKNavigationDelegateProtocol,
                UIAdaptivePresentationControllerDelegateProtocol {
                override fun webView(
                    webView: WKWebView,
                    decidePolicyForNavigationAction: WKNavigationAction,
                    decisionHandler: (WKNavigationActionPolicy) -> Unit,
                ) {
                    val scheme = decidePolicyForNavigationAction.request.URL?.scheme?.lowercase()
                    decisionHandler(
                        if (scheme == "https") WKNavigationActionPolicyAllow else WKNavigationActionPolicyCancel
                    )
                }

                override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
                    dismissed = true
                }
            }
            webView.navigationDelegate = delegate
            presenter().presentViewController(controller, animated = true) {
                controller.presentationController?.delegate = delegate
            }
            webView.loadRequest(NSURLRequest(requireNotNull(NSURL.URLWithString(LOGIN_URL))))
            withTimeout(TIMEOUT_MS) {
                var found: IosAppleMusicTokens? = null
                var attempts = 0
                while (found == null && !dismissed && attempts < MAX_ATTEMPTS) {
                    delay(POLL_MS)
                    attempts += 1
                    if (dismissed) break
                    found = evaluateTokens(webView)
                }
                found
            }
        } finally {
            active = false
            webView.stopLoading()
            webView.navigationDelegate = null
            controller.dismissViewControllerAnimated(true, completion = null)
        }
    }

    /** One evaluation of the Android probe; a page or script error just means "not signed in yet". */
    private suspend fun evaluateTokens(webView: WKWebView): IosAppleMusicTokens? =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavaScript(TOKEN_POLL_JS) { result, _ ->
                if (continuation.isActive) continuation.resume(parseAppleMusicTokens(result as? String))
            }
        }

    private companion object {
        const val LOGIN_URL = "https://music.apple.com/login"
        const val POLL_MS = 500L
        const val MAX_ATTEMPTS = 240
        const val TIMEOUT_MS = 120_000L

        /** The same probe the Android screen injects: MusicKit first, the localStorage entry second. */
        const val TOKEN_POLL_JS = """
        (function() {
            var musicUser = null;
            var developer = null;
            try {
                if (window.MusicKit && typeof MusicKit.getInstance === 'function') {
                    var instance = MusicKit.getInstance();
                    if (instance) {
                        musicUser = instance.musicUserToken || null;
                        developer = instance.developerToken || null;
                    }
                }
            } catch (error) {}
            if (musicUser === null) {
                try {
                    var stored = window.localStorage.getItem('media-user-token');
                    if (stored) musicUser = stored;
                } catch (error) {}
            }
            if (musicUser === null || String(musicUser).indexOf('0.') !== 0) return '';
            return String(musicUser) + '\n' + (developer === null ? '' : String(developer));
        })()
        """
    }
}

/** The probe answers "user\ndev"; anything else — an error, an empty string — means not yet. */
internal fun parseAppleMusicTokens(result: String?): IosAppleMusicTokens? {
    if (result.isNullOrBlank()) return null
    val user = result.substringBefore('\n').trim()
    if (!user.startsWith("0.")) return null
    val developer = result.substringAfter('\n', "").trim().ifBlank { null }
    return IosAppleMusicTokens(user, developer)
}
