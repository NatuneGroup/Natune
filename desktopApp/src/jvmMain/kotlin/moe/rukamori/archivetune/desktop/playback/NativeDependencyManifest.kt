package moe.rukamori.archivetune.desktop.playback

import java.io.File

/**
 * The load order of the staged native closure beside the playback engine.
 *
 * Windows has no equivalent of ELF's `$ORIGIN` or Mach-O's `@loader_path`: the loader searches the
 * executable's directory, System32, the Windows directory, the working directory and PATH, never the
 * loaded library's own directory, and HotSpot loads a JNI library with plain `LoadLibrary`. The staged
 * FFmpeg, OpenSSL, libxml2 and zlib DLLs therefore have to be loaded explicitly, dependencies first.
 * `native-dependencies.txt` is written from the shipped PE import tables by
 * `desktopApp/native/scripts/windows-native-deps.py`, so this order cannot drift from the binaries.
 */
internal object NativeDependencyManifest {
    const val FILE_NAME: String = "native-dependencies.txt"

    /** The staged manifest, or null when the package does not carry one (a developer build). */
    fun read(directory: File): Map<String, List<String>>? {
        val file = File(directory, FILE_NAME)
        if (!file.isFile) return null
        return runCatching { parse(file.readLines()) }.getOrNull()
    }

    /** One `library=dependency,dependency` entry per line; blank lines and `#` comments are ignored. */
    fun parse(lines: List<String>): Map<String, List<String>> {
        val dependencies = mutableMapOf<String, List<String>>()
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEach
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val library = line.substring(0, separator).trim().lowercase()
            val imports = line.substring(separator + 1)
                .split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
            dependencies[library] = imports
        }
        return dependencies
    }

    /**
     * Everything [root] transitively imports, dependencies before dependents and [root] itself excluded.
     * An import cycle keeps each library once, in the order the walk first reached it.
     */
    fun loadOrder(dependencies: Map<String, List<String>>, root: String): List<String> {
        val key = root.lowercase()
        val ordered = mutableListOf<String>()
        val placed = mutableSetOf<String>()
        val walking = mutableSetOf<String>()

        fun visit(library: String) {
            if (library in placed || !walking.add(library)) return
            // A system import is not a staged library and must not appear in the load order.
            dependencies[library].orEmpty().filter { it in dependencies }.forEach { visit(it) }
            walking.remove(library)
            placed.add(library)
            if (library != key) ordered.add(library)
        }

        visit(key)
        return ordered
    }
}
