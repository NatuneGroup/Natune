package moe.rukamori.archivetune.desktop

import java.awt.Desktop
import java.awt.Toolkit

/** The application name, used for the window title and the Linux window class. */
internal const val APP_NAME = "Natune"

/**
 * Process-wide desktop setup that must finish before the first window is composed. Every step
 * fails silently by design, so each one reports its outcome on stderr instead of relying on the
 * window appearing.
 */
internal object DesktopRuntime {
    private val osName = System.getProperty("os.name").orEmpty()
    private val isWindows = osName.startsWith("Windows", ignoreCase = true)
    private val isLinux = osName.startsWith("Linux", ignoreCase = true)

    /**
     * Linux only. The desktop shell binds a launcher icon to a window by its class name, and the
     * JDK reads that name from a field on its X11 toolkit at the first window. Building the
     * toolkit here, before any window, makes the override take effect.
     */
    fun forceLinuxWindowClass() {
        if (!isLinux) return
        runCatching {
            val toolkit = Toolkit.getDefaultToolkit()
            // Only the X11 toolkit exposes this field. The Wayland toolkit and headless runs are skipped.
            if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") {
                System.err.println("window-class override skipped: toolkit ${toolkit.javaClass.name}")
                return
            }
            toolkit.javaClass.getDeclaredField("awtAppClassName").apply {
                isAccessible = true
                set(null, APP_NAME)
            }
            System.err.println("window-class override set to $APP_NAME")
        }.onFailure { System.err.println("window-class override failed: ${it.message}") }
    }

    /**
     * Renderer and interop properties are read once, when rendering starts, so they are written
     * here rather than after a window exists, where they would silently change nothing.
     */
    fun prepare() {
        // java.awt.Desktop caches its support answer on first call. Asking now, while only system
        // libraries are loaded, keeps a bundled native library from poisoning that answer later.
        Desktop.isDesktopSupported()
        System.setProperty("compose.swing.render.on.graphics", "true")
        System.setProperty("compose.interop.blending", "true")
        System.setProperty("compose.layers.type", "COMPONENT")
        // With vsync on, frame pacing waits for a display signal that can stop arriving when the window
        // moves to another monitor. The UI then freezes while audio keeps playing. Windows renders by
        // a different path and does not show this, so the switch is limited to other platforms.
        if (!isWindows) System.setProperty("skiko.vsync.enabled", "false")
    }
}
