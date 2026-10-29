package moe.rukamori.archivetune.ui.component

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Rendering and input defaults only. The caller still owns cookies, storage resets, navigation and
 * session capture. A profile must be selected before even configuring the view, including popups.
 */
internal fun createAuthWebView(
    context: Context,
    beforeConfigure: (WebView) -> Unit = {},
): WebView = AuthWebView(context).apply {
    beforeConfigure(this)
    configureAuthWebView()
}

@Suppress("DEPRECATION")
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureAuthWebView() {
    // A transparent document assumes the browser's white canvas, not the Compose sheet's surface.
    // Do not inject colors or color-scheme CSS: provider-authored light/dark styles remain in charge.
    setBackgroundColor(Color.WHITE)
    isFocusable = true
    isFocusableInTouchMode = true
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }
    val darkening = authWebViewDarkeningPolicy(
        sdkInt = Build.VERSION.SDK_INT,
        algorithmicDarkeningSupported = WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING),
        legacyForceDarkSupported = WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK),
    )
    if (darkening.disableViewForceDark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isForceDarkAllowed = false
    }
    if (darkening.disableAlgorithmicDarkening) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
    }
    if (darkening.disableLegacyForceDark) {
        WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
    }
}

private class AuthWebView(context: Context) : WebView(context) {
    // Resizing a dialog does not always bring a DOM-focused input back into view. No field value
    // crosses this boundary; the browser only scrolls the focused control within its new viewport.
    private val revealFocusedInput = Runnable {
        if (isAttachedToWindow && hasFocus()) {
            evaluateJavascript(REVEAL_FOCUSED_INPUT_SCRIPT, null)
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (shouldRevealFocusedAuthInput(oldWidth, oldHeight, width, height)) {
            removeCallbacks(revealFocusedInput)
            post(revealFocusedInput)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(revealFocusedInput)
        super.onDetachedFromWindow()
    }

    override fun destroy() {
        removeCallbacks(revealFocusedInput)
        super.destroy()
    }
}

private const val REVEAL_FOCUSED_INPUT_SCRIPT = """
(function(){
  var e=document.activeElement;
  if(e&&(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.tagName==='SELECT'||e.isContentEditable)){
    e.scrollIntoView({block:'nearest',inline:'nearest'});
  }
})();
"""
