package moe.rukamori.archivetune.ui.component

import android.os.Message
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout

private const val BLANK_POPUP_TIMEOUT_MS = 5_000L
private const val MAX_LOGGED_BLOCKED_POPUPS = 3
private const val CONSOLE_MESSAGE_LIMIT = 200

/**
 * The popup slot every sign-in WebView shares. A page that opened windows on its own used to get a
 * full-size opaque WebView stacked over the sign-in form — an invisible overlay that swallowed the
 * taps meant for the email field. Popups are now gated on a real user gesture, so script-driven
 * window.open calls die here, and a gesture popup runs in the same container where [moveToParent]
 * can hand same-flow navigations back to the parent and dead about:blank windows are closed instead
 * of lingering. The popup shares its parent's WebView profile and cookie jar; it is never a second
 * account and never a second session.
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
        container.addView(
            popup,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        popup.webChromeClient = this
        popup.bringToFront()
        popup.requestFocus()
        onActiveWebViewChanged(popup)
        transport.webView = popup
        resultMsg.sendToTarget()
        popup.postDelayed({ destroyIfStillBlank(popup) }, blankPopupTimeoutMs)
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
                "console.error $host:${consoleMessage.lineNumber()} " +
                    consoleMessage.message()?.take(CONSOLE_MESSAGE_LIMIT).orEmpty(),
            )
        }
        return false
    }

    /**
     * A gesture popup that lands back on the sign-in flow's own hosts continues in the parent:
     * the popup closes, focus returns to the form and the user keeps a single window. Used by
     * providers whose OAuth substitutes return onto their own pages.
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

    private fun destroyIfStillBlank(popup: WebView) {
        if (popup.parent !== container || !isBlankPopupUrl(popup.url)) return
        signInDiagnostic(provider, "closing popup that stayed about:blank")
        destroyPopup(popup)
    }
}
