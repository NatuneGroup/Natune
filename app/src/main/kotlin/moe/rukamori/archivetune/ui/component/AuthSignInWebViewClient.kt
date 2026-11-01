package moe.rukamori.archivetune.ui.component

import android.webkit.HttpAuthHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import timber.log.Timber
import moe.rukamori.archivetune.utils.AppProxy

/** "Diagnostics" in the tag routes the line through the redacting diagnostic facility, not only logcat. */
internal const val SIGN_IN_DIAGNOSTIC_TAG = "SignInDiagnostics"

internal fun signInDiagnostic(provider: String, message: String) {
    Timber.tag(SIGN_IN_DIAGNOSTIC_TAG).w("%s sign-in %s", provider, message)
}

/**
 * The shared WebViewClient for every provider sign-in: proxy authentication plus bounded, redacted
 * navigation-failure diagnostics. Main-frame failures always log; subresource failures log only for
 * the flow's own hosts ([extraLoggedHosts]) so a tracker's broken beacon does not drown the real
 * error. [authLogUrl] keeps scheme, host and path — never a query, cookie or form field — and every
 * line additionally passes the shared redaction before it can reach a report.
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
                "error code=${error.errorCode} frame=${if (request.isForMainFrame) "main" else "sub"} " +
                    "url=${authLogUrl(request.url?.toString())} desc=${error.description}",
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
        signInDiagnostic(provider, "error code=$errorCode frame=main url=${authLogUrl(failingUrl)} desc=$description")
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
                "http=${errorResponse.statusCode} frame=${if (request.isForMainFrame) "main" else "sub"} " +
                    "url=${authLogUrl(request.url?.toString())}",
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
