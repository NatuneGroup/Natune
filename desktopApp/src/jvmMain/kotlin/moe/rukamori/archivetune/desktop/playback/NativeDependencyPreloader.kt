package moe.rukamori.archivetune.desktop.playback

import java.io.File

/** One staged library that could not be loaded, with the loader's reason. */
internal data class NativePreloadFailure(val library: String, val reason: String?)

/**
 * What [NativeDependencyPreloader] managed to put in the process before the engine itself.
 * [ordered] is false when the package carried no manifest and the closure was resolved by retrying.
 */
internal data class NativePreloadResult(
    val loaded: List<String>,
    val failures: List<NativePreloadFailure>,
    val ordered: Boolean,
) {
    /** The engine loader's own failure text plus what the preload did, for one error message. */
    fun describe(engineMessage: String?): String {
        val parts = mutableListOf<String>()
        if (!engineMessage.isNullOrBlank()) parts += engineMessage
        if (failures.isNotEmpty()) {
            parts += failures.joinToString(", ") { failure ->
                if (failure.reason.isNullOrBlank()) "${failure.library} did not load" else "${failure.library} did not load: ${failure.reason}"
            }
        } else if (!ordered) {
            parts += "no ${NativeDependencyManifest.FILE_NAME} beside the engine, so the closure was preloaded in retry order"
        } else {
            parts += "the staged closure loaded, so the engine library itself is the problem"
        }
        return parts.joinToString("; ")
    }

    companion object {
        val EMPTY: NativePreloadResult = NativePreloadResult(emptyList(), emptyList(), ordered = false)
    }
}

/**
 * Loads the sibling libraries a native library imports before that library itself.
 *
 * Windows resolves a library's imports through the loader search path, which never contains the loaded
 * library's own directory (see [NativeDependencyManifest]). Loading each dependency by absolute path
 * puts it in the process first, and the import then binds to the loaded module by name. ELF and Mach-O
 * hosts resolve the same closure through `$ORIGIN` and `@loader_path`, so they need nothing here.
 *
 * A library that fails to load is recorded, never thrown: the caller still tries the root library, and
 * one message then carries both the loader's error and which sibling was missing.
 */
internal class NativeDependencyPreloader(
    private val listLibraries: (File) -> List<File> = { directory ->
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".dll", ignoreCase = true) }
            .orEmpty()
    },
    private val load: (File) -> Unit = { System.load(it.absolutePath) },
) {
    /** Loads the staged closure of [rootLibrary] from [directory]; the root library is left to the caller. */
    fun preload(directory: File, rootLibrary: String): NativePreloadResult {
        val libraries = listLibraries(directory).associateBy { it.name.lowercase() }
        val root = rootLibrary.lowercase()
        val loaded = mutableListOf<String>()
        val failures = mutableListOf<NativePreloadFailure>()
        val manifest = NativeDependencyManifest.read(directory)
        if (manifest != null) {
            NativeDependencyManifest.loadOrder(manifest, rootLibrary)
                .filter { it in libraries && it != root }
                .forEach { name ->
                    val library = libraries.getValue(name)
                    attempt(library, loaded, failures)
                }
            return NativePreloadResult(loaded, failures, ordered = true)
        }
        // Without a manifest, retry until a pass loads nothing more: a pass can only succeed where its
        // dependencies are already in the process, so the order sorts itself out in a few rounds.
        var pending = libraries.keys
            .filter { name -> name != root && MEDIA_LIBRARY_PREFIXES.any { prefix -> name.startsWith(prefix) } }
            .sorted()
        while (pending.isNotEmpty()) {
            val stillPending = mutableListOf<String>()
            val passFailures = mutableListOf<NativePreloadFailure>()
            pending.forEach { name ->
                attempt(libraries.getValue(name), loaded, passFailures) { stillPending += name }
            }
            if (stillPending.size == pending.size) {
                failures += passFailures
                break
            }
            pending = stillPending
        }
        return NativePreloadResult(loaded, failures, ordered = false)
    }

    private fun attempt(
        library: File,
        loaded: MutableList<String>,
        failures: MutableList<NativePreloadFailure>,
        onFailure: (String) -> Unit = {},
    ) {
        runCatching { load(library) }
            .onSuccess { loaded += library.name }
            .onFailure { error ->
                failures += NativePreloadFailure(library.name, error.message)
                onFailure(library.name)
            }
    }

    private companion object {
        /** The versioned media/CRT closures the engine links; deliberately excludes the app's own libraries. */
        val MEDIA_LIBRARY_PREFIXES = listOf(
            "avcodec", "avdevice", "avfilter", "avformat", "avutil", "swresample", "swscale",
            "libssl", "libcrypto", "libxml2", "libiconv", "zlib1", "libwinpthread",
        )
    }
}
