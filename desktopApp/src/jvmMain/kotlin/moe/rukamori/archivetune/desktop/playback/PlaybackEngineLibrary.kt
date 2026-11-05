package moe.rukamori.archivetune.desktop.playback

import java.io.File

/**
 * Loads `libarchivetune_engine` once per process.
 *
 * The library path comes from the `archivetune.engine.library` system property when it is set,
 * which the desktop build passes to tests, or from the `ARCHIVETUNE_ENGINE_LIBRARY` environment
 * variable when the property is blank, for a developer run that cannot add JVM arguments.
 * Otherwise the normal `java.library.path` search applies. A missing or incompatible library fails
 * every later call with the same message, so the caller sees the real cause instead of an
 * [UnsatisfiedLinkError] from deep inside a native call.
 */
internal object PlaybackEngineLibrary {
    const val PATH_PROPERTY: String = "archivetune.engine.library"
    const val PATH_ENV: String = "ARCHIVETUNE_ENGINE_LIBRARY"

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

    /**
     * Whether the library [ensureLoaded] would load is on disk. Playback never consults this: it
     * still tries the loader and reports the real cause on failure. The background warm uses it so
     * a build without native files keeps that failure for the first play instead of loading on the
     * chance that something is there.
     */
    fun isLibraryPresent(): Boolean = engineLibraryPresent(
        explicitPath = explicitEngineLibraryPath(),
        resourcesDirectory = System.getProperty(RESOURCES_PROPERTY),
        libraryName = System.mapLibraryName(LIBRARY_NAME),
        libraryPath = System.getProperty("java.library.path"),
    )

    private fun load() {
        val explicitPath = explicitEngineLibraryPath()
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

/**
 * The explicit library path this process was given. The system property wins because the Gradle
 * test task sets it; the environment variable covers a developer run that cannot add JVM arguments.
 * A blank value counts as unset, exactly like an unset property.
 */
internal fun explicitEngineLibraryPath(
    systemProperty: String? = System.getProperty(PlaybackEngineLibrary.PATH_PROPERTY),
    environment: String? = System.getenv(PlaybackEngineLibrary.PATH_ENV),
): String? = systemProperty?.takeIf { it.isNotBlank() } ?: environment?.takeIf { it.isNotBlank() }

/**
 * Whether a load would find its library on disk. This mirrors the loader's own resolution (explicit
 * path, packaged `resources/native` directory, then the library search path) without loading
 * anything, so it is cheap enough for a startup decision.
 */
internal fun engineLibraryPresent(
    explicitPath: String?,
    resourcesDirectory: String?,
    libraryName: String,
    libraryPath: String?,
    fileExists: (File) -> Boolean = { it.isFile },
): Boolean {
    if (explicitPath != null) return fileExists(File(explicitPath))
    val packaged = resourcesDirectory?.let { File(File(it, "native"), libraryName) }
    if (packaged != null && fileExists(packaged)) return true
    return libraryPath.orEmpty()
        .split(File.pathSeparator)
        .any { entry -> entry.isNotBlank() && fileExists(File(entry, libraryName)) }
}
