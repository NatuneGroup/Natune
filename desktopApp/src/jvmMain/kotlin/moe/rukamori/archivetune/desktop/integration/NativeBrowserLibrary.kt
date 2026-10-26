package moe.rukamori.archivetune.desktop.integration

import java.io.File

/**
 * Loads archivetune_browser once. The cookie-only sign-in window and the Linux file chooser both run on that library's
 * single GTK owner, so both must share one loaded instance rather than each loading a copy with its own owner.
 */
internal object NativeBrowserLibrary {
    private val loaded: Boolean by lazy {
        val explicit = System.getProperty("archivetune.browser.library")
        val bundled = System.getProperty("compose.application.resources.dir")?.let {
            File(it, "native/${System.mapLibraryName("archivetune_browser")}")
        }
        when {
            explicit != null -> System.load(explicit)
            bundled?.isFile == true -> System.load(bundled.absolutePath)
            else -> System.loadLibrary("archivetune_browser")
        }
        true
    }

    /** Loads the native library once. A [LinkageError] means the browser and the Linux file chooser are unavailable here. */
    fun ensureLoaded() {
        check(loaded)
    }
}
