package moe.rukamori.archivetune.desktop.integration.windows

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * The DPI awareness the process actually received. jpackage's launcher manifest declares system awareness, so a
 * mixed-DPI desktop gets one scale for both monitors; [WindowsWindowChrome] and the window sizing cannot fix that from
 * inside the JVM, but a support report can at least name the state instead of guessing. Read-only on purpose: DPI
 * awareness can only be set before the toolkit starts and the launcher manifest owns it, so this never changes it.
 */
internal object WindowsDpiAwareness {
    private const val DPI_AWARENESS_CONTEXT_UNAWARE = -1L
    private const val DPI_AWARENESS_CONTEXT_SYSTEM_AWARE = -2L
    private const val DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE = -3L
    private const val DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4L
    private const val DPI_AWARENESS_CONTEXT_UNAWARE_GDISCALED = -5L

    internal interface User32 : Library {
        // Windows 10 1703+. Older systems have no export and the lazy binding fails, which the caller treats as unknown.
        fun GetProcessDpiAwarenessContext(): Pointer?
        fun GetDpiForSystem(): Int
    }

    private val user32: User32? by lazy {
        if (!WindowsWindowChrome.isSupported()) null else runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }

    /** `per-monitor-v2`, `system`, `unaware`, … or `unknown` when this Windows build cannot answer. */
    fun describe(): String {
        val api = user32 ?: return "unknown"
        val context = runCatching { api.GetProcessDpiAwarenessContext() }.getOrNull() ?: return "unknown"
        return nameOf(Pointer.nativeValue(context))
    }

    internal fun nameOf(contextValue: Long): String = when (contextValue) {
        DPI_AWARENESS_CONTEXT_UNAWARE -> "unaware"
        DPI_AWARENESS_CONTEXT_SYSTEM_AWARE -> "system"
        DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE -> "per-monitor"
        DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 -> "per-monitor-v2"
        DPI_AWARENESS_CONTEXT_UNAWARE_GDISCALED -> "unaware-gdi-scaled"
        else -> "unknown"
    }
}
