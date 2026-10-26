package moe.rukamori.archivetune.desktop.playback

import java.io.File

/**
 * Loads `libarchivetune_engine` once per process.
 *
 * The library path comes from the `archivetune.engine.library` system property when it is set,
 * which the desktop build passes to tests. Otherwise the normal `java.library.path` search applies.
 * A missing or incompatible library fails every later call with the same message, so the caller
 * sees the real cause instead of an [UnsatisfiedLinkError] from deep inside a native call.
 */
internal object PlaybackEngineLibrary {
    const val PATH_PROPERTY: String = "archivetune.engine.library"

    private const val LIBRARY_NAME = "archivetune_engine"
    private const val EXPECTED_ABI_VERSION = 1

    private val loaded: Result<Unit> by lazy { runCatching { load() } }

    fun ensureLoaded() {
        loaded.getOrElse { cause ->
            throw IllegalStateException(
                "The desktop playback engine is not available: ${cause.message}. Build it with " +
                    "desktopApp/native/scripts/build-native-deps.sh and the buildNativeEngine task, " +
                    "or point $PATH_PROPERTY at the built library.",
                cause,
            )
        }
    }

    private fun load() {
        val explicitPath = System.getProperty(PATH_PROPERTY)?.takeIf { it.isNotBlank() }
        if (explicitPath != null) {
            System.load(explicitPath)
        } else {
            val bundled = System.getProperty("compose.application.resources.dir")?.let {
                File(it, "native/${System.mapLibraryName(LIBRARY_NAME)}")
            }
            if (bundled?.isFile == true) System.load(bundled.absolutePath) else System.loadLibrary(LIBRARY_NAME)
        }
        val abiVersion = PlaybackEngineNative.abiVersion()
        check(abiVersion == EXPECTED_ABI_VERSION) {
            "engine ABI version $abiVersion does not match expected $EXPECTED_ABI_VERSION"
        }
    }
}
