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
    private const val RESOURCES_PROPERTY = "compose.application.resources.dir"
    private const val EXPECTED_ABI_VERSION = 1

    private val loaded: Result<Unit> by lazy { runCatching { load() } }

    fun ensureLoaded() {
        loaded.getOrElse { cause ->
            throw IllegalStateException(
                "Desktop playback is unavailable: the audio engine library did not load. Update or reinstall " +
                    "Natune to repair this build's native files, and report the detail below if that does not " +
                    "fix it. Detail: ${cause.message} (developers: build it with " +
                    "desktopApp/native/scripts/build-native-deps.sh and the buildNativeEngine task, " +
                    "or point $PATH_PROPERTY at the built library.)",
                cause,
            )
        }
    }

    private fun load() {
        val explicitPath = System.getProperty(PATH_PROPERTY)?.takeIf { it.isNotBlank() }
        if (explicitPath != null) {
            System.load(explicitPath)
        } else {
            val nativeDirectory = System.getProperty(RESOURCES_PROPERTY)?.let { File(it, "native") }
            val bundled = nativeDirectory?.let { File(it, System.mapLibraryName(LIBRARY_NAME)) }
            when {
                nativeDirectory != null && bundled?.isFile == true -> loadBundled(nativeDirectory, bundled)
                else -> System.loadLibrary(LIBRARY_NAME)
            }
        }
        val abiVersion = PlaybackEngineNative.abiVersion()
        check(abiVersion == EXPECTED_ABI_VERSION) {
            "engine ABI version $abiVersion does not match expected $EXPECTED_ABI_VERSION"
        }
    }

    /**
     * Loads the packaged library with its staged closure.
     *
     * Windows resolves the engine's FFmpeg imports through the loader search path, which does not
     * contain the library's own directory, so the staged dependencies are loaded first (see
     * [NativeDependencyPreloader]). Unix hosts already reach them through the library's rpath, and the
     * preload there would only repeat work the loader does anyway.
     */
    private fun loadBundled(nativeDirectory: File, bundled: File) {
        val preload = if (isWindows()) {
            NativeDependencyPreloader().preload(nativeDirectory, bundled.name)
        } else {
            NativePreloadResult.EMPTY
        }
        try {
            System.load(bundled.absolutePath)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException(preload.describe(error.message), error)
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")
}
