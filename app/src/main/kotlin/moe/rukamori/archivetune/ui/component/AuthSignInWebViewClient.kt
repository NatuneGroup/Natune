package moe.rukamori.archivetune.ui.component

import android.webkit.HttpAuthHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import timber.log.Timber
import moe.rukamori.archivetune.logcat.DiagnosticRedaction
import moe.rukamori.archivetune.utils.AppProxy

/** "Diagnostics" in the tag routes the line through the redacting diagnostic facility, not only logcat. */
internal const val SIGN_IN_DIAGNOSTIC_TAG = "SignInDiagnostics"

internal fun signInDiagnostic(provider: String, message: String) {
    // The DebugTree half of Timber writes raw text to logcat, so the line must be redacted here,
    // not only inside the diagnostic facility. Builders keep it to bounded fields already.
    Timber.tag(SIGN_IN_DIAGNOSTIC_TAG).w("%s sign-in %s", provider, DiagnosticRedaction.redact(message))
}

/**
 * The shared WebViewClient for every provider sign-in: proxy authentication plus bounded, redacted
 * navigation-failure diagnostics. Main-frame failures always log; subresource failures log only for
 * the flow's own hosts ([extraLoggedHosts]) so a tracker's broken beacon does not drown the real
 * error. Lines carry the error code or HTTP status and the failing scheme/host/path — never a
 * description, console text, query, cookie or form field.
 */
internal open class AuthSignInWebViewClient(
    private val provider: String,
    private val extraLoggedHosts: Set<String> = emptySet(),
) : WebViewClient() {
    private fun logsRequest(request: WebResourceRequest): Boolean =
        request.isForMainFrame || hostInLoginFlow(request.url?.host, extraLoggedHosts)

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (logsRequest(request)) {
            signInDiagnostic(
                provider,
                authNavErrorLine(error.errorCode, request.isForMainFrame, request.url?.toString()),
            )
        }
        super.onReceivedError(view, request, error)
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String?,
        failingUrl: String?,
    ) {
        // API < M only delivers this overload, and only for the main frame.
        signInDiagnostic(provider, authNavErrorLine(errorCode, isMainFrame = true, url = failingUrl))
        super.onReceivedError(view, errorCode, description, failingUrl)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (logsRequest(request)) {
            signInDiagnostic(
                provider,
                authHttpErrorLine(errorResponse.statusCode, request.isForMainFrame, request.url?.toString()),
            )
        }
        super.onReceivedHttpError(view, request, errorResponse)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?,
    ) {
        if (AppProxy.handleWebViewProxyAuth(handler, host)) return
        super.onReceivedHttpAuthRequest(view, handler, host, realm)
    }
}
