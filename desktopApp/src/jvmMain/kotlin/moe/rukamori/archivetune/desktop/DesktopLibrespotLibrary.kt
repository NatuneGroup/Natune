package moe.rukamori.archivetune.desktop

import java.io.File

/** The Android JNI worker is reused; only discovery of the packaged desktop library differs. */
internal object DesktopLibrespotLibrary {
    fun load() {
        val name = System.mapLibraryName("natune_librespot")
        val explicit = System.getProperty("natune.librespot.library")?.takeIf(String::isNotBlank)
        val bundled = System.getProperty("compose.application.resources.dir")?.let { File(it, "native/$name") }
        when {
            explicit != null -> System.load(File(explicit).absolutePath)
            bundled?.isFile == true -> System.load(bundled.absolutePath)
            else -> System.loadLibrary("natune_librespot")
        }
    }
}
