@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlinx.cinterop.ObjCAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.data.platform.ios.IosProviderCredentials
import moe.rukamori.archivetune.shared.data.platform.ios.authPopupNavigationPolicy
import moe.rukamori.archivetune.shared.data.provider.KtorAmazonAuthorization
import moe.rukamori.archivetune.shared.data.provider.amazonTurnstileExpiry
import moe.rukamori.archivetune.shared.domain.settings.AmazonBypassTokenKey
import moe.rukamori.archivetune.shared.domain.settings.amazonInstanceList
import moe.rukamori.archivetune.shared.domain.settings.commitAmazonTurnstileJwt
import platform.Foundation.NSDictionary
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURL
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIBarButtonItemStyle
import platform.UIKit.UIColor
import platform.UIKit.UINavigationController
import platform.UIKit.UIPresentationController
import platform.UIKit.UIScrollViewContentInsetAdjustmentBehavior
import platform.UIKit.UIViewController
import platform.UIKit.bottomAnchor
import platform.UIKit.keyboardLayoutGuide
import platform.UIKit.leadingAnchor
import platform.UIKit.navigationItem
import platform.UIKit.presentationController
import platform.UIKit.safeAreaLayoutGuide
import platform.UIKit.topAnchor
import platform.UIKit.trailingAnchor
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Long enough for an interactive challenge, short enough that a sheet nobody answers releases its caller. */
private const val CHALLENGE_TIMEOUT_MS = 5L * 60L * 1000L

/** The script-message channel the challenge page reports on. */
private const val TURNSTILE_MESSAGE = "turnstile"

/** How one iOS Amazon authorization ended. Cancellation is the user closing the sheet, never an error. */
internal sealed interface IosAmazonAuthorizeOutcome {
    data class Authorized(val expiresAtMs: Long) : IosAmazonAuthorizeOutcome
    data object Cancelled : IosAmazonAuthorizeOutcome

    /**
     * This build carries no Turnstile sitekey or no valid origin for it, so no challenge page is loaded and no request
     * is sent.
     */
    data object NotConfigured : IosAmazonAuthorizeOutcome

    /** The settings page already refuses the row without an instance; this is the same refusal behind the sheet. */
    data object NoInstance : IosAmazonAuthorizeOutcome
    data object Busy : IosAmazonAuthorizeOutcome
    data class Failed(val reason: String) : IosAmazonAuthorizeOutcome
}

/** What a challenge page reported through its script-message channel. */
private sealed interface IosAmazonChallengeReport {
    data class Token(val value: String) : IosAmazonChallengeReport
    data class Failure(val reason: String) : IosAmazonChallengeReport

    /** The only report that carries no page value: the sheet was closed or its Cancel button was pressed. */
    data object Cancelled : IosAmazonChallengeReport

    /** A message that is not a well-formed report, so the sheet keeps waiting instead of failing. */
    data object Ignored : IosAmazonChallengeReport
}

/**
 * The Cloudflare Turnstile page the authorization sheet runs — the same document the desktop window loads, because the
 * sitekey is domain-locked to the sign-in origin and a challenge from any other origin is refused by Cloudflare. The
 * sheet therefore loads this document *as* the origin (`loadHTMLString` with the origin as its base URL, the same
 * contract Android's `loadDataWithBaseURL` gives it), and the page reports back through the `turnstile` script message
 * with the same `{kind, value}` vocabulary the desktop page navigates with: a token, or a short reason. No cookie and
 * no other page value is read.
 */
private object IosAmazonChallenge {
    /** The document the sheet loads: the root of the sign-in origin the build names, which the sitekey is locked to. */
    fun documentUrl(origin: String): String = "$origin/"

    /** Cloudflare renders the widget in a frame from this origin, so the frame's navigations must be allowed too. */
    const val CHALLENGE_FRAME_HOST = "challenges.cloudflare.com"

    /** A Turnstile token is a few kilobytes; anything longer is not one and is refused before it is used. */
    const val MAX_TOKEN_LENGTH = 4096
    private const val MAX_REASON_LENGTH = 160

    private val TOKEN_CHARS = Regex("^[A-Za-z0-9._~-]+$")
    private val REASON_CHARS = Regex("^[A-Za-z0-9 ._:()\\[\\]-]+$")

    /** A plain https origin, without a trailing slash — the same shape the desktop host requires. */
    private val HTTPS_ORIGIN = Regex("^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$")

    /** The build's origin, or null when it is missing or not a plain https origin the sitekey can be locked to. */
    fun validatedOrigin(): String? =
        IosProviderCredentials.amazonTurnstileOrigin.trimEnd('/').takeIf { HTTPS_ORIGIN.matches(it) }

    /** Whether the navigation stays inside the sign-in origin or the challenge frame's origin. */
    fun allows(url: NSURL?, origin: String): Boolean {
        if (url == null || url.scheme?.lowercase() != "https") return false
        val host = url.host?.lowercase() ?: return false
        val port = url.port?.intValue ?: 443
        if (host == CHALLENGE_FRAME_HOST && port == 443) return true
        val target = origin.removePrefix("https://")
        val targetHost = target.substringBefore(':').lowercase()
        val targetPort = target.substringAfter(':', "").toIntOrNull() ?: 443
        return host == targetHost && port == targetPort
    }

    /**
     * Reads a reported script message: the page posts `{kind, value}` where the kind is `token` or `error`. A token
     * must look like one and stay inside the length bound, so a page cannot hand the exchange a header or a body
     * fragment; a reason is bounded and reduced to characters a log line can hold.
     */
    fun report(body: Any?): IosAmazonChallengeReport {
        val fields = body as? NSDictionary ?: return IosAmazonChallengeReport.Ignored
        val kind = fields.objectForKey("kind") as? String ?: return IosAmazonChallengeReport.Ignored
        val value = fields.objectForKey("value") as? String ?: ""
        return when (kind) {
            "token" -> value.takeIf { it.length in 1..MAX_TOKEN_LENGTH && TOKEN_CHARS.matches(it) }
                ?.let(IosAmazonChallengeReport::Token)
                ?: IosAmazonChallengeReport.Failure("token-invalid")
            "error" -> IosAmazonChallengeReport.Failure(
                value.take(MAX_REASON_LENGTH).takeIf { it.isNotBlank() && REASON_CHARS.matches(it) }
                    ?: "turnstile-failed",
            )
            else -> IosAmazonChallengeReport.Ignored
        }
    }

    /**
     * The challenge page, identical to the desktop document except that `report` posts to the `turnstile` script
     * message instead of navigating to a capture prefix. A hidden interaction-only widget is tried first, exactly as
     * the Android screen does, and a failure escalates once to the visible widget the user can act on. [checking] is
     * the host's status line.
     */
    fun page(sitekey: String, checking: String): String {
        val status = jsString(checking)
        val site = jsString(sitekey)
        val channel = jsString(TURNSTILE_MESSAGE)
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit" async defer></script>
              <style>
                html,body{margin:0;height:100%;background:#101014;color:#e6e6e6;font:14px sans-serif}
                #cf{display:flex;align-items:center;justify-content:center;min-height:100%}
                #msg{position:fixed;left:0;right:0;bottom:16px;text-align:center;color:#a0a0a8}
              </style>
            </head>
            <body>
              <div id="cf"></div>
              <div id="msg"></div>
              <script>
                var channel = $channel;
                var attempts = 0;
                var usedVisible = false;
                var settled = false;
                document.getElementById('msg').textContent = $status;
                function report(kind, value) {
                  if (settled) { return; }
                  settled = true;
                  document.getElementById('msg').textContent = '';
                  window.webkit.messageHandlers[channel].postMessage({kind: kind, value: String(value)});
                }
                function renderWidget(visible) {
                  var options = {
                    sitekey: $site,
                    action: 'auth',
                    theme: 'dark',
                    callback: function (token) { report('token', token); },
                    'error-callback': function () {
                      if (!usedVisible) {
                        usedVisible = true;
                        document.getElementById('cf').innerHTML = '';
                        renderWidget(true);
                      } else {
                        report('error', 'turnstile-error');
                      }
                    },
                    'expired-callback': function () { report('error', 'turnstile-expired'); }
                  };
                  if (!visible) { options.appearance = 'interaction-only'; options.execution = 'execute'; }
                  try {
                    var id = window.turnstile.render('#cf', options);
                    if (!visible) { window.turnstile.execute(id); }
                  } catch (error) {
                    report('error', 'render-exception');
                  }
                }
                function start() {
                  attempts++;
                  if (window.turnstile && window.turnstile.render) { renderWidget(false); return; }
                  if (attempts > 80) { report('error', 'turnstile-script-timeout'); return; }
                  setTimeout(start, 150);
                }
                start();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    /*
     * The status line and the sitekey are interpolated into a script element, so a quote, a backslash, an angle bracket
     * or a line separator must not end the literal or the element.
     */
    private fun jsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("<", "\\u003C")
            .replace(">", "\\u003E")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("\u2028", " ")
            .replace("\u2029", " ")
        return "'$escaped'"
    }
}

/**
 * The build's Turnstile sign-in origin, validated to a plain https origin the sitekey can be locked to,
 * or null when the build names none. Shared with the playback resolver so the exchange and the stream
 * requests send the same Origin.
 */
internal fun iosAmazonTurnstileOrigin(): String? = IosAmazonChallenge.validatedOrigin()

/**
 * The iOS Amazon authorization: the sheet runs the Turnstile challenge on the sign-in origin, the token is exchanged
 * against the configured primary instance through the shared [KtorAmazonAuthorization], and the returned JWT plus its
 * expiry are committed together through the one settings owner — the same keys, the same expiry margin and the same
 * exchange request the desktop window uses. Nothing is committed unless the exchange returns a token.
 */
internal class IosAmazonAuthorize(
    private val graph: IosSessionGraph,
    private val presenter: () -> UIViewController,
) {
    private val gate = Mutex()

    suspend fun authorize(title: String, checking: String, cancelLabel: String): IosAmazonAuthorizeOutcome {
        if (!gate.tryLock()) return IosAmazonAuthorizeOutcome.Busy
        try {
            // The sitekey is a build-time value, so a build without it has no challenge to run and loads nothing.
            val sitekey = IosProviderCredentials.amazonTurnstileSitekey
            if (sitekey.isBlank()) return IosAmazonAuthorizeOutcome.NotConfigured
            // The origin is paired with the sitekey; without a valid one the challenge is refused, so nothing loads.
            val origin = IosAmazonChallenge.validatedOrigin() ?: return IosAmazonAuthorizeOutcome.NotConfigured
            val snapshot = graph.settings.snapshots.value ?: return IosAmazonAuthorizeOutcome.Failed("settings-unavailable")
            val instance = snapshot.amazonInstanceList().firstOrNull() ?: return IosAmazonAuthorizeOutcome.NoInstance
            val operatorToken = snapshot[AmazonBypassTokenKey, ""].trim().ifBlank { null }
            val report = runChallenge(sitekey, origin, title, checking, cancelLabel)
            val token = when (report) {
                is IosAmazonChallengeReport.Token -> report.value
                IosAmazonChallengeReport.Cancelled -> return IosAmazonAuthorizeOutcome.Cancelled
                is IosAmazonChallengeReport.Failure -> return IosAmazonAuthorizeOutcome.Failed(report.reason)
                IosAmazonChallengeReport.Ignored -> return IosAmazonAuthorizeOutcome.Failed("challenge-unreported")
            }
            val jwt = try {
                KtorAmazonAuthorization(graph.http, instance, origin).exchangeTurnstile(token, operatorToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("amazon token exchange failed: ${failure::class.simpleName}")
                return IosAmazonAuthorizeOutcome.Failed("exchange")
            }
            if (jwt.isBlank()) return IosAmazonAuthorizeOutcome.Failed("exchange")
            val expiresAtMs = amazonTurnstileExpiry(jwt, Clock.System.now().toEpochMilliseconds())
            try {
                graph.settings.edit { commitAmazonTurnstileJwt(jwt, expiresAtMs) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("amazon authorization was not stored: ${failure::class.simpleName}")
                return IosAmazonAuthorizeOutcome.Failed("store")
            }
            return IosAmazonAuthorizeOutcome.Authorized(expiresAtMs)
        } finally {
            gate.unlock()
        }
    }

    /*
     * Presents the challenge sheet on the main thread and suspends until the page reports, the user cancels or the
     * challenge times out. The message handler and the delegates are kept in [keepAlive] until the sheet is dismissed:
     * WKUserContentController is the only owner of its handler, and the navigation/presentation delegates and the
     * bar-button target are all held weakly by UIKit.
     */
    private suspend fun runChallenge(
        sitekey: String,
        origin: String,
        title: String,
        checking: String,
        cancelLabel: String,
    ): IosAmazonChallengeReport = withContext(Dispatchers.Main) {
        val configuration = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
        }
        val controller = UIViewController()
        val webView = controller.installChallengeWebView(configuration)
        val keepAlive = mutableListOf<Any>()
        try {
            withTimeoutOrNull(CHALLENGE_TIMEOUT_MS) {
                suspendCancellableCoroutine<IosAmazonChallengeReport> { continuation ->
                    fun finish(report: IosAmazonChallengeReport) {
                        if (continuation.isActive) continuation.resume(report)
                    }
                    fun cancel() = finish(IosAmazonChallengeReport.Cancelled)
                    val messageHandler = object : NSObject(), WKScriptMessageHandlerProtocol {
                        override fun userContentController(
                            userContentController: WKUserContentController,
                            didReceiveScriptMessage: WKScriptMessage,
                        ) {
                            val report = IosAmazonChallenge.report(didReceiveScriptMessage.body)
                            if (report != IosAmazonChallengeReport.Ignored) finish(report)
                        }
                    }
                    val actions = ChallengeSheetActions(onCancel = ::cancel)
                    val delegate = object : NSObject(), WKNavigationDelegateProtocol,
                        UIAdaptivePresentationControllerDelegateProtocol {
                        override fun webView(
                            webView: WKWebView,
                            decidePolicyForNavigationAction: WKNavigationAction,
                            decisionHandler: (WKNavigationActionPolicy) -> Unit,
                        ) {
                            decisionHandler(
                                webView.authPopupNavigationPolicy(
                                    decidePolicyForNavigationAction,
                                    IosAmazonChallenge.allows(decidePolicyForNavigationAction.request.URL, origin),
                                )
                            )
                        }
                        override fun presentationControllerDidDismiss(presentationController: UIPresentationController) = cancel()
                    }
                    webView.navigationDelegate = delegate
                    val navigation = UINavigationController(rootViewController = controller)
                    controller.navigationItem.title = title
                    controller.navigationItem.setLeftBarButtonItem(
                        UIBarButtonItem(
                            title = cancelLabel, style = UIBarButtonItemStyle.UIBarButtonItemStylePlain,
                            target = actions, action = NSSelectorFromString("cancelTapped"),
                        ),
                        animated = false,
                    )
                    keepAlive += messageHandler
                    keepAlive += actions
                    keepAlive += delegate
                    configuration.userContentController.addScriptMessageHandler(messageHandler, TURNSTILE_MESSAGE)
                    continuation.invokeOnCancellation {
                        dispatch_async(dispatch_get_main_queue()) { webView.stopLoading() }
                    }
                    presenter().presentViewController(navigation, animated = true) {
                        navigation.presentationController?.delegate = delegate
                    }
                    webView.loadHTMLString(
                        IosAmazonChallenge.page(sitekey, checking),
                        baseURL = NSURL.URLWithString(IosAmazonChallenge.documentUrl(origin)),
                    )
                }
            } ?: IosAmazonChallengeReport.Failure("challenge-timeout")
        } finally {
            configuration.userContentController.removeScriptMessageHandlerForName(TURNSTILE_MESSAGE)
            keepAlive.clear()
            webView.stopLoading()
            webView.navigationDelegate = null
            controller.dismissViewControllerAnimated(true, completion = null)
        }
    }

    /** The bar-button target. @ObjCAction is what exports a Kotlin method to Objective-C dispatch under its own name. */
    private class ChallengeSheetActions(val onCancel: () -> Unit) : NSObject() {
        @ObjCAction
        fun cancelTapped() = onCancel()
    }
}

/** Appearance/layout only, mirroring the sign-in sheet's web view; [configuration] still owns its data store. */
private fun UIViewController.installChallengeWebView(configuration: WKWebViewConfiguration): WKWebView {
    val root = view
    root.backgroundColor = UIColor.whiteColor
    val webView = WKWebView(frame = root.bounds, configuration = configuration).apply {
        opaque = true
        backgroundColor = UIColor.whiteColor
        scrollView.backgroundColor = UIColor.whiteColor
        scrollView.contentInsetAdjustmentBehavior =
            UIScrollViewContentInsetAdjustmentBehavior.UIScrollViewContentInsetAdjustmentNever
        translatesAutoresizingMaskIntoConstraints = false
    }
    root.addSubview(webView)
    val safeArea = root.safeAreaLayoutGuide
    val keyboard = root.keyboardLayoutGuide
    keyboard.followsUndockedKeyboard = false
    NSLayoutConstraint.activateConstraints(
        listOf(
            webView.leadingAnchor.constraintEqualToAnchor(safeArea.leadingAnchor),
            webView.trailingAnchor.constraintEqualToAnchor(safeArea.trailingAnchor),
            webView.topAnchor.constraintEqualToAnchor(safeArea.topAnchor),
            webView.bottomAnchor.constraintEqualToAnchor(keyboard.topAnchor),
        ),
    )
    return webView
}
