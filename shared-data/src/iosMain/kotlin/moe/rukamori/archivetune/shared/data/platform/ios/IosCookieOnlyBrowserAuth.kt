@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.platform.AuthorizedHttpsOrigin
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyBrowserAuthPort
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginFailure
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginOutcome
import moe.rukamori.archivetune.shared.domain.platform.CookieOnlyLoginRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.platform.PlatformSecret
import moe.rukamori.archivetune.shared.domain.platform.PrivateCookie
import moe.rukamori.archivetune.shared.domain.platform.PrivateReusableContext
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSDate
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIBarButtonItemStyle
import platform.UIKit.UINavigationController
import platform.UIKit.UIPresentationController
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.navigationItem
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

private const val COOKIE_LOGIN_TIMEOUT_MS = 600_000L

/**
 * The iOS cookie-only login port, mirroring `IosEmbeddedBrowserAuthPort`: one attempt owns one in-memory
 * WKWebView presented over the host, navigation is gated to the attempt's authorized HTTPS origins exactly like
 * the desktop native window, and the cookie store dies with the sheet. The user presses Done once the flow looks
 * signed in — only then are the store's cookies read into the attempt's private context; Cancel, swipe-down, a
 * timeout or a torn-down coroutine end the attempt without committing anything.
 */
class IosCookieOnlyBrowserAuth(
    private val presenter: () -> UIViewController,
) : CookieOnlyBrowserAuthPort {
    private class Attempt(val delegate: NSObject, val cancel: () -> Unit)
    private val attempts = mutableMapOf<Pair<String, CredentialScope>, Attempt>()
    private var closed = false

    override suspend fun login(request: CookieOnlyLoginRequest): CookieOnlyLoginOutcome =
        withContext(Dispatchers.Main) {
            check(!closed) { "Authentication host closed" }
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
                withTimeoutOrNull(COOKIE_LOGIN_TIMEOUT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        fun finish(outcome: CookieOnlyLoginOutcome) {
                            if (continuation.isActive) continuation.resume(outcome)
                        }
                        fun cancel() = finish(CookieOnlyLoginOutcome.Cancelled)
                        val delegate = object : NSObject(), WKNavigationDelegateProtocol,
                            UIAdaptivePresentationControllerDelegateProtocol {
                            override fun webView(
                                webView: WKWebView,
                                decidePolicyForNavigationAction: WKNavigationAction,
                                decisionHandler: (WKNavigationActionPolicy) -> Unit,
                            ) {
                                decisionHandler(
                                    if (allows(decidePolicyForNavigationAction.request.URL, request.authorizedOrigins)) {
                                        WKNavigationActionPolicyAllow
                                    } else {
                                        WKNavigationActionPolicyCancel
                                    }
                                )
                            }
                            override fun presentationControllerDidDismiss(presentationController: UIPresentationController) = cancel()
                        }
                        val actions = CookieSheetActions(
                            onDone = {
                                configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies ->
                                    finish(
                                        CookieOnlyLoginOutcome.Completed(
                                            PrivateReusableContext(
                                                request.provider, request.scope, request.generation,
                                                request.authorizedOrigins, cookies.orEmpty().mapNotNull(::privateCookie),
                                            )
                                        )
                                    )
                                }
                            },
                            onCancel = ::cancel,
                        )
                        webView.navigationDelegate = delegate
                        val navigation = UINavigationController(rootViewController = controller)
                        controller.navigationItem.setRightBarButtonItem(
                            UIBarButtonItem(
                                title = "Done", style = UIBarButtonItemStyle.UIBarButtonItemStyleDone,
                                target = actions, action = NSSelectorFromString("doneTapped"),
                            ),
                            animated = false,
                        )
                        controller.navigationItem.setLeftBarButtonItem(
                            UIBarButtonItem(
                                title = "Cancel", style = UIBarButtonItemStyle.UIBarButtonItemStylePlain,
                                target = actions, action = NSSelectorFromString("cancelTapped"),
                            ),
                            animated = false,
                        )
                        attempts[key] = Attempt(actions, ::cancel)
                        continuation.invokeOnCancellation {
                            dispatch_async(dispatch_get_main_queue()) { webView.stopLoading() }
                        }
                        presenter().presentViewController(navigation, animated = true) {
                            navigation.presentationController?.delegate = delegate
                        }
                        val start = NSURL.URLWithString(request.startUrl)
                        if (start == null) finish(CookieOnlyLoginOutcome.Failed(CookieOnlyLoginFailure.NAVIGATION_REFUSED))
                        else webView.loadRequest(NSURLRequest(start))
                    }
                } ?: CookieOnlyLoginOutcome.Failed(CookieOnlyLoginFailure.TIMED_OUT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                attempts.remove(key)
                webView.stopLoading()
                webView.navigationDelegate = null
                controller.dismissViewControllerAnimated(true, completion = null)
            }
        }

    override suspend fun clearProfile(provider: String, scope: CredentialScope) = withContext(Dispatchers.Main) {
        // The cookie store is in-memory and dies with the sheet; clearing ends exactly this open attempt.
        attempts[provider to scope]?.cancel?.invoke()
        Unit
    }

    fun close() {
        dispatch_async(dispatch_get_main_queue()) {
            closed = true
            attempts.values.toList().forEach { it.cancel() }
        }
    }

    /** The bar-button target. Methods of a named NSObject subclass are ObjC-visible under their Kotlin names. */
    private class CookieSheetActions(
        val onDone: () -> Unit,
        val onCancel: () -> Unit,
    ) : NSObject() {
        fun doneTapped() = onDone()
        fun cancelTapped() = onCancel()
    }

    private fun allows(url: NSURL?, origins: Set<AuthorizedHttpsOrigin>): Boolean {
        if (url == null || url.scheme?.lowercase() != "https") return false
        val host = url.host ?: return false
        val port = url.port?.intValue ?: 443
        return origins.any { it.host.equals(host, ignoreCase = true) && it.port == port }
    }

    private fun privateCookie(raw: Any?): PrivateCookie? = (raw as? NSHTTPCookie)?.let { cookie ->
        PrivateCookie(
            name = cookie.name,
            value = PlatformSecret(cookie.value),
            domain = cookie.domain,
            path = cookie.path.ifBlank { "/" },
            expiresAtUnixMillis = cookie.expiresDate?.timeIntervalSince1970?.let { (it * 1000).toLong() },
            secure = cookie.isSecure(),
            httpOnly = cookie.isHTTPOnly(),
            sameSite = null,
        )
    }
}
