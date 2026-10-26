package moe.rukamori.archivetune.desktop.playback

import java.io.File

/**
 * Loads libtdjni once, before the first TDLib client is created, so that the packaged library is used instead of
 * whatever the host's library path happens to hold.
 *
 * The vendored `org.drinkless.tdlib.Client` repeats the load by name in its static initializer. In the packaged app that
 * repeat fails and prints one UnsatisfiedLinkError trace, and the client still works: the JVM resolves the native methods
 * against every library its class loader has loaded, and this library is one of them. The trace is expected and is the
 * price of keeping the vendored binding byte-identical to the Android copy.
 */
internal object TdlightNativeLibrary {
    private val loaded: Boolean by lazy {
        val explicit = System.getProperty("archivetune.tdlight.library")
        val bundled = System.getProperty("compose.application.resources.dir")?.let {
            File(it, "native/${System.mapLibraryName("tdjni")}")
        }
        when {
            explicit != null -> System.load(explicit)
            bundled?.isFile == true -> System.load(bundled.absolutePath)
            else -> System.loadLibrary("tdjni")
        }
        true
    }

    /** Loads the native library once. A [LinkageError] means Telegram streams are unavailable on this host. */
    fun ensureLoaded() {
        check(loaded)
    }
}
