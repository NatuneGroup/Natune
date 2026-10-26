@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.coroutines.*
import moe.rukamori.archivetune.shared.domain.platform.*
import platform.AuthenticationServices.*
import platform.Foundation.*
import platform.UIKit.*
import platform.WebKit.*
import platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyAllow
import platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyCancel
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Exact redirect and state validation shared by both native authentication presenters. */
internal object IosAuthCallback {
    fun validateRequest(request: BrowserAuthRequest) {
        require(request.provider.isNotBlank() && request.state.isNotBlank())
        val url = requireNotNull(NSURL.URLWithString(request.authorizationUrl))
        require(url.scheme?.lowercase() == "https" && !url.host.isNullOrBlank())
        require(url.user == null && url.password == null)
        val redirect = requireNotNull(NSURL.URLWithString(request.redirectUri))
        require(!redirect.scheme.isNullOrBlank())
        require(redirect.scheme?.lowercase() !in setOf("http", "file", "data", "javascript"))
        require(redirect.user == null && redirect.password == null)
        require(redirect.scheme?.lowercase() != "https" || !redirect.host.isNullOrBlank())
        require(redirect.query == null && redirect.fragment == null)
        require(singleState(request.authorizationUrl) == request.state)
    }

    fun matchesRedirect(callback: String, expected: String): Boolean {
        val actual = NSURLComponents.componentsWithString(callback) ?: return false
        val target = NSURLComponents.componentsWithString(expected) ?: return false
        return actual.scheme?.lowercase() == target.scheme?.lowercase() &&
            actual.host?.lowercase() == target.host?.lowercase() &&
            actual.port == target.port && actual.path == target.path &&
            actual.user == null && actual.password == null
    }

    fun result(callback: String, request: BrowserAuthRequest): BrowserAuthResult {
        require(matchesRedirect(callback, request.redirectUri)) { "Unexpected authentication redirect" }
        require(singleState(callback) == request.state) { "Authentication state mismatch" }
        return BrowserAuthResult(callback, request.state)
    }

    private fun singleState(url: String): String? {
        val items = NSURLComponents.componentsWithString(url)?.queryItems
            ?.filterIsInstance<NSURLQueryItem>()?.filter { it.name == "state" }.orEmpty()
        return items.singleOrNull()?.value
    }
}

/**
 * OS OAuth sheet with a fresh ephemeral session per attempt: neither catalog, streaming nor Pool
 * inherits another scope's cookies. OS versions/providers that ignore ephemeral preference must
 * not be advertised as a persistent isolated browser profile; no credential commit occurs here.
 */
class IosBrowserAuthPort(
    private val window: () -> UIWindow,
) : BrowserAuthPort {
    private val attempts = mutableMapOf<Pair<String, CredentialScope>, ASWebAuthenticationSession>()
    private val anchors = mutableMapOf<Pair<String, CredentialScope>, AnchorProvider>()
    private var closed = false

    override suspend fun authorize(request: BrowserAuthRequest): BrowserAuthResult =
        withContext(Dispatchers.Main) {
            check(!closed) { "Authentication host closed" }
            IosAuthCallback.validateRequest(request)
            val key = request.provider to request.scope
            check(key !in attempts) { "Authentication already active for profile" }
            try {
                withTimeout(120_000) {
                    suspendCancellableCoroutine { continuation ->
                        val session = ASWebAuthenticationSession(
                            requireNotNull(NSURL.URLWithString(request.authorizationUrl)),
                            callbackURLScheme = NSURL.URLWithString(request.redirectUri)?.scheme,
                        ) { url, error ->
                            if (continuation.isActive) {
                                if (error != null || url?.absoluteString == null) {
                                    continuation.resumeWithException(IllegalStateException("Authentication cancelled or failed"))
                                } else {
                                    try {
                                        continuation.resume(IosAuthCallback.result(url.absoluteString!!, request))
                                    } catch (failure: IllegalArgumentException) {
                                        continuation.resumeWithException(failure)
                                    }
                                }
                            }
                        }
                        val anchor = AnchorProvider(window())
                        anchors[key] = anchor
                        attempts[key] = session
                        session.presentationContextProvider = anchor
                        session.prefersEphemeralWebBrowserSession = true
                        continuation.invokeOnCancellation {
                            dispatch_async(dispatch_get_main_queue()) { session.cancel() }
                        }
                        if (!session.start() && continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("Authentication sheet unavailable"))
                        }
                    }
                }
            } finally {
                attempts.remove(key)?.cancel()
                anchors.remove(key)
            }
        }

    override suspend fun clearProfile(provider: String, scope: CredentialScope) = withContext(Dispatchers.Main) {
        // There is no persistent cookie profile to clear. Cancels exactly this ephemeral attempt.
        attempts[provider to scope]?.cancel()
        Unit
    }

    fun close() {
        dispatch_async(dispatch_get_main_queue()) {
            closed = true
            attempts.values.toList().forEach { it.cancel() }
        }
    }

    private class AnchorProvider(private val window: UIWindow) : NSObject(),
        ASWebAuthenticationPresentationContextProvidingProtocol {
        override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor = window
    }
}

/**
 * Embedded OAuth only. A nonpersistent WKWebsiteDataStore belongs to exactly one attempt. Cookie
 * extraction/provider private-context verification require a richer common contract, not globals.
 */
class IosEmbeddedBrowserAuthPort(
    private val presenter: () -> UIViewController,
) : BrowserAuthPort {
    private class Attempt(val delegate: NSObject, val cancel: () -> Unit)
    private val attempts = mutableMapOf<Pair<String, CredentialScope>, Attempt>()
    private var closed = false

    override suspend fun authorize(request: BrowserAuthRequest): BrowserAuthResult = withContext(Dispatchers.Main) {
        check(!closed) { "Authentication host closed" }
        IosAuthCallback.validateRequest(request)
        val key = request.provider to request.scope
        check(key !in attempts) { "Authentication already active for profile" }
        val configuration = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
        }
        val controller = UIViewController()
        val webView = WKWebView(frame = controller.view.bounds, configuration = configuration)
        webView.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        controller.view.addSubview(webView)
        try {
            withTimeout(120_000) {
                suspendCancellableCoroutine { continuation ->
                    fun cancel() {
                        if (continuation.isActive) continuation.cancel(CancellationException("Embedded authentication cancelled"))
                    }
                    val delegate = object : NSObject(), WKNavigationDelegateProtocol,
                        UIAdaptivePresentationControllerDelegateProtocol {
                        override fun webView(
                            webView: WKWebView,
                            decidePolicyForNavigationAction: WKNavigationAction,
                            decisionHandler: (WKNavigationActionPolicy) -> Unit,
                        ) {
                            val url = decidePolicyForNavigationAction.request.URL?.absoluteString
                            if (url != null && IosAuthCallback.matchesRedirect(url, request.redirectUri)) {
                                decisionHandler(WKNavigationActionPolicyCancel)
                                if (continuation.isActive) {
                                    try { continuation.resume(IosAuthCallback.result(url, request)) }
                                    catch (failure: IllegalArgumentException) { continuation.resumeWithException(failure) }
                                }
                            } else {
                                // No arbitrary app schemes, file/javascript/data top-level redirects or popups.
                                val scheme = decidePolicyForNavigationAction.request.URL?.scheme?.lowercase()
                                decisionHandler(if (scheme == "https") WKNavigationActionPolicyAllow else WKNavigationActionPolicyCancel)
                            }
                        }
                        override fun presentationControllerDidDismiss(presentationController: UIPresentationController) = cancel()
                        override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Embedded authentication navigation failed"))
                        }
                    }
                    webView.navigationDelegate = delegate
                    attempts[key] = Attempt(delegate, ::cancel)
                    continuation.invokeOnCancellation {
                        dispatch_async(dispatch_get_main_queue()) { webView.stopLoading() }
                    }
                    presenter().presentViewController(controller, animated = true) {
                        controller.presentationController?.delegate = delegate
                    }
                    webView.loadRequest(NSURLRequest(requireNotNull(NSURL.URLWithString(request.authorizationUrl))))
                }
            }
        } finally {
            attempts.remove(key)
            webView.stopLoading()
            webView.navigationDelegate = null
            controller.dismissViewControllerAnimated(true, completion = null)
        }
    }

    override suspend fun clearProfile(provider: String, scope: CredentialScope) = withContext(Dispatchers.Main) {
        attempts[provider to scope]?.cancel?.invoke()
        Unit
    }

    fun close() {
        dispatch_async(dispatch_get_main_queue()) {
            closed = true
            attempts.values.toList().forEach { it.cancel() }
        }
    }
}
