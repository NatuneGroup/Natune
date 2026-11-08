package moe.rukamori.archivetune.desktop.integration.windows

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.concurrent.atomic.AtomicBoolean
import moe.rukamori.archivetune.desktop.DesktopDiagnosticLog
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLevel

/**
 * The Windows frame is drawn by DWM, not by the app: without these attributes a dark Compose client area sits under the
 * default light Win32 title bar, which is what makes the app read as a Java program. `DwmSetWindowAttribute` through the
 * already-bundled JNA is the supported way to follow the app theme, and every call is optional: an older Windows build,
 * a stripped DWM or a missing desktop integration must leave the stock frame rather than fail the window.
 */
internal object WindowsWindowChrome {
    // DOCUMENTED_USE_IMMERSIVE_DARK_MODE is 20 from Windows 10 20H1; 19 is the 1809-1909 attribute number.
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_PRE_20H1 = 19
    // Windows 11 22000+. Windows 10 answers E_INVALIDARG and keeps its own rounding.
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWCP_ROUND = 2
    private const val ATTRIBUTE_BYTES = 4
    private const val S_OK = 0

    internal interface DwmApi : Library {
        fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: Pointer, size: Int): Int
    }

    private val dwm: DwmApi? by lazy {
        if (!isSupported()) null else runCatching { Native.load("dwmapi", DwmApi::class.java) }.getOrNull()
    }
    private val warned = AtomicBoolean()

    fun isSupported(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** The value `DWMWA_USE_IMMERSIVE_DARK_MODE` takes; the attribute documents a 32-bit BOOL, not a Java boolean. */
    internal fun darkModeValue(dark: Boolean): Int = if (dark) 1 else 0

    /**
     * Applies the dark title bar and, on Windows 11, the rounded corner the Compose surface expects. Safe off Windows
     * (no-op) and safe before the native peer exists (waits for the window to be shown once).
     */
    fun apply(window: Window, dark: Boolean) {
        if (!isSupported() || dwm == null) return
        if (window.isDisplayable) applyNow(window, dark) else window.addComponentListener(object : ComponentAdapter() {
            override fun componentShown(event: ComponentEvent) {
                window.removeComponentListener(this)
                applyNow(window, dark)
            }
        })
    }

    private fun applyNow(window: Window, dark: Boolean) {
        val api = dwm ?: return
        val handle = runCatching { Native.getComponentPointer(window) }.getOrNull() ?: return
        val value = Memory(ATTRIBUTE_BYTES.toLong())
        value.setInt(0, darkModeValue(dark))
        val darkResult = runCatching {
            val modern = api.DwmSetWindowAttribute(handle, DWMWA_USE_IMMERSIVE_DARK_MODE, value, ATTRIBUTE_BYTES)
            if (modern == S_OK) modern else api.DwmSetWindowAttribute(handle, DWMWA_USE_IMMERSIVE_DARK_MODE_PRE_20H1, value, ATTRIBUTE_BYTES)
        }.getOrElse { -1 }
        val corners = runCatching {
            value.setInt(0, DWMWCP_ROUND)
            api.DwmSetWindowAttribute(handle, DWMWA_WINDOW_CORNER_PREFERENCE, value, ATTRIBUTE_BYTES) == S_OK
        }.getOrDefault(false)
        // One line per apply, at INFO: the diagnostic log has no console to print to in a packaged app.
        DesktopDiagnosticLog.host(
            "window chrome: dark=${darkModeValue(dark)} darkAttribute=${if (darkResult == S_OK) "applied" else "unavailable"} " +
                "roundedCorners=$corners",
            DiagnosticLevel.INFO,
        )
        if (darkResult != S_OK && warned.compareAndSet(false, true)) {
            DesktopDiagnosticLog.host("window chrome: DWM rejected the immersive dark mode attribute (older Windows build)")
        }
    }
}
