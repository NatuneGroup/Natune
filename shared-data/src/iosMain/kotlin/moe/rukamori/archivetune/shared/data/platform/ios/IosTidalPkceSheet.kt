@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLQueryItem
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
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The iOS form of the Android TIDAL PKCE WebView: a nonpersistent WKWebView sheet that watches every
 * navigation for the fixed `https://tidal.com/android/login/auth` return, exactly like the Android
 * screen's `shouldOverrideUrlLoading`. TIDAL's real PKCE URL carries `client_unique_key` rather than
 * OAuth `state`, so the shared `IosAuthCallback.result` validation does not apply — the sheet returns
 * the `code` query parameter itself and HTTPS is the only navigable scheme.
 */
class IosTidalPkceSheet(
    private val presenter: () -> UIViewController,
    private val redirectUri: String,
) {
    private var active = false

    /** The `code` parameter of the intercepted redirect; null on cancel, timeout or window loss. */
    suspend fun authorizeCode(authorizeUrl: String): String? = withContext(Dispatchers.Main) {
        check(!active) { "TIDAL sign-in already active" }
        active = true
        val configuration = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
        }
        val controller = UIViewController()
        val webView = controller.installAuthWebView(configuration)
        try {
            withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    fun cancel() {
                        if (continuation.isActive) continuation.cancel(CancellationException("TIDAL sign-in cancelled"))
                    }
                    val delegate = object : NSObject(), WKNavigationDelegateProtocol,
                        UIAdaptivePresentationControllerDelegateProtocol {
                        override fun webView(
                            webView: WKWebView,
                            decidePolicyForNavigationAction: WKNavigationAction,
                            decisionHandler: (WKNavigationActionPolicy) -> Unit,
                        ) {
                            val url = decidePolicyForNavigationAction.request.URL?.absoluteString
                            if (url != null && IosAuthCallback.matchesRedirect(url, redirectUri)) {
                                decisionHandler(WKNavigationActionPolicyCancel)
                                val code = queryParam(url, "code")
                                if (continuation.isActive) continuation.resume(code)
                            } else {
                                val scheme = decidePolicyForNavigationAction.request.URL?.scheme?.lowercase()
                                decisionHandler(if (scheme == "https") WKNavigationActionPolicyAllow else WKNavigationActionPolicyCancel)
                            }
                        }
                        override fun presentationControllerDidDismiss(presentationController: UIPresentationController) = cancel()
                        override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("TIDAL sign-in navigation failed"))
                        }
                    }
                    webView.navigationDelegate = delegate
                    continuation.invokeOnCancellation {
                        dispatch_async(dispatch_get_main_queue()) { webView.stopLoading() }
                    }
                    presenter().presentViewController(controller, animated = true) {
                        controller.presentationController?.delegate = delegate
                    }
                    webView.loadRequest(NSURLRequest(requireNotNull(NSURL.URLWithString(authorizeUrl))))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            active = false
            webView.stopLoading()
            webView.navigationDelegate = null
            controller.dismissViewControllerAnimated(true, completion = null)
        }
    }

    private fun queryParam(url: String, name: String): String? =
        NSURLComponents.componentsWithString(url)?.queryItems
            ?.filterIsInstance<NSURLQueryItem>()
            ?.firstOrNull { it.name == name }
            ?.value

    private companion object {
        const val TIMEOUT_MS = 120_000L
    }
}
