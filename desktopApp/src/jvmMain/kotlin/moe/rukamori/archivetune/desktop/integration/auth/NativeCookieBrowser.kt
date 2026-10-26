/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration.auth

import moe.rukamori.archivetune.desktop.integration.NativeBrowserLibrary

/**
 * JNI boundary for the cookie-only sign-in window in desktopApp/native/browser. The native side owns the window,
 * enforces the origin policy and reads the cookies. Java receives the Finish press, the close event and bounded cookie
 * rows, never a page URL. The export names, the [open] signature and the listener method names are one contract with
 * browser_linux.cpp, browser_windows.cpp and browser_macos.mm, so change them together.
 */
internal object NativeCookieBrowser {
    /** Loads the shared browser library once. A [LinkageError] means the window is unavailable on this machine. */
    fun ensureLoaded() {
        NativeBrowserLibrary.ensureLoaded()
    }

    /**
     * Returns a window handle, or 0 when the window cannot be created or its owner does not answer in time. [origins]
     * are canonical "https://host:port" strings, and the native side refuses every other navigation.
     */
    @JvmStatic
    external fun open(startUrl: String, origins: Array<String>, title: String, doneLabel: String, listener: Any): Long

    /** Asks for the cookies of one authorized origin. The answer arrives on [NativeCookieBrowserListener.onCookies]. */
    @JvmStatic
    external fun requestCookies(handle: Long, requestId: Long, origin: String)

    /** Closes the window. Idempotent; the close event still arrives through the listener. */
    @JvmStatic
    external fun close(handle: Long)
}

/**
 * Where the native events land. Implementations only record state and complete waiting coroutines; they never call
 * the browser back, because the events arrive on the native owner thread.
 */
internal interface NativeCookieBrowserSink {
    fun onFinish()
    fun onCookieRows(requestId: Long, rows: Array<Array<String>>?)
    fun onWindowClosed(failed: Boolean)
}

/**
 * The object the native side calls on its owner thread. The JNI code finds these three methods by name and signature
 * (`onDone()V`, `onCookies(J[[Ljava/lang/String;)V`, `onClosed(Z)V`), so they stay public and unrenamed.
 */
internal class NativeCookieBrowserListener(private val sink: NativeCookieBrowserSink) {
    fun onDone() = sink.onFinish()

    fun onCookies(requestId: Long, rows: Array<Array<String>>?) = sink.onCookieRows(requestId, rows)

    fun onClosed(failed: Boolean) = sink.onWindowClosed(failed)
}
