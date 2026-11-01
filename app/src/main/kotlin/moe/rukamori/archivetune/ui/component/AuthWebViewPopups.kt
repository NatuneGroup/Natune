package moe.rukamori.archivetune.ui.component

import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout

private const val BLANK_POPUP_TIMEOUT_MS = 5_000L
private const val POPUP_REVEAL_INTERVAL_MS = 250L
private const val MAX_LOGGED_BLOCKED_POPUPS = 3

/**
 * The popup slot used by sign-ins that genuinely need gesture popups (Spotify's social buttons).
 * A page that opened windows on its own used to get a full-size opaque WebView stacked over the
 * sign-in form — an invisible overlay that swallowed every tap. Popups are now gated on a real
 * user gesture, so script-driven window.open calls die here; a gesture popup starts INVISIBLE —
 * invisible views take no input and draw nothing, so even a dead about:blank window cannot cover
 * the form — and only appears once it commits a real navigation. [moveToParent] hands verified
 * main-frame GET navigations back to the parent; everything else stays and is captured in place.
 * The popup shares its parent's WebView profile and cookie jar; it is never a second account and
 * never a second session.
 */
internal class AuthPopupWebChromeClient(
    private val container: FrameLayout,
    val parentWebView: WebView,
    private val provider: String,
    private val onActiveWebViewChanged: (WebView) -> Unit,
    // Receiver-typed so the factory's popup client can call moveToParent on the chrome client.
    private val createPopup: AuthPopupWebChromeClient.(parent: WebView) -> WebView = {
        createAuthWebView(it.context).apply {
            webViewClient = AuthSignInWebViewClient(provider)
        }
    },
    private val consoleLoggedHosts: Set<String> = emptySet(),
    private val blankPopupTimeoutMs: Long = BLANK_POPUP_TIMEOUT_MS,
) : WebChromeClient() {
    private var blockedPopups = 0

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        if (!authPopupAllowed(isUserGesture)) {
            if (blockedPopups++ < MAX_LOGGED_BLOCKED_POPUPS) {
                signInDiagnostic(provider, "blocked script-initiated popup #$blockedPopups")
            }
            return false
        }
        closePopups()
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val popup = createPopup(this, view)
        // Invisible until it proves it is a page: an invisible window cannot cover the form or
        // take its taps while it sits on about:blank.
        popup.visibility = View.INVISIBLE
        container.addView(
            popup,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        popup.webChromeClient = this
        popup.bringToFront()
        onActiveWebViewChanged(popup)
        transport.webView = popup
        resultMsg.sendToTarget()
        popup.postDelayed(PopupReveal(popup), POPUP_REVEAL_INTERVAL_MS)
        return true
    }

    override fun onCloseWindow(window: WebView) {
        destroyPopup(window)
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val host = authLogHost(consoleMessage.sourceId())
        if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR &&
            hostInLoginFlow(host, consoleLoggedHosts)
        ) {
            signInDiagnostic(
                provider,
                authConsoleLine(
                    consoleMessage.messageLevel().name.lowercase(),
                    consoleMessage.sourceId(),
                    consoleMessage.lineNumber(),
                ),
            )
        }
        // Always consumed: returning false lets Chromium echo the raw message and its source URL
        // to logcat, outside every redaction boundary.
        return true
    }

    /**
     * A gesture popup that lands back on the sign-in flow's own hosts continues in the parent:
     * the popup closes, focus returns to the form and the user keeps a single window. Callers must
     * have already verified this is a main-frame GET — see [authPopupHandoffAllowed]; replaying a
     * POST as loadUrl would silently drop the OAuth body.
     */
    fun moveToParent(
        view: WebView,
        url: String?,
    ): Boolean {
        if (url.isNullOrBlank()) return false
        view.stopLoading()
        parentWebView.loadUrl(url)
        // Posted so the popup's own navigation callback finishes before the window is torn down.
        container.post { closePopups() }
        return true
    }

    /** Destroys every popup and puts the parent back in front. */
    fun closePopups() {
        for (index in container.childCount - 1 downTo 0) {
            val child = container.getChildAt(index) as? WebView ?: continue
            if (child !== parentWebView) {
                destroyPopup(child)
            }
        }
        onActiveWebViewChanged(parentWebView)
    }

    fun destroyPopup(window: WebView) {
        window.stopLoading()
        window.loadUrl("about:blank")
        (window.parent as? ViewGroup)?.removeView(window)
        window.destroy()
        onActiveWebViewChanged(parentWebView)
    }

    /** Reveals the popup on its first real navigation, or tears it down once it is proven dead. */
    private inner class PopupReveal(
        private val popup: WebView,
    ) : Runnable {
        private var waited = 0L

        override fun run() {
            if (popup.parent !== container) return
            if (!isBlankPopupUrl(popup.url)) {
                popup.visibility = View.VISIBLE
                popup.requestFocus()
                return
            }
            waited += POPUP_REVEAL_INTERVAL_MS
            if (waited >= blankPopupTimeoutMs) {
                signInDiagnostic(provider, "closing popup that stayed about:blank")
                destroyPopup(popup)
                return
            }
            popup.postDelayed(this, POPUP_REVEAL_INTERVAL_MS)
        }
    }
}
