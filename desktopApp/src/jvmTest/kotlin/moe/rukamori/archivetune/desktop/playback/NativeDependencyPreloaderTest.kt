package moe.rukamori.archivetune.desktop.playback

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeDependencyPreloaderTest {
    @Test
    fun loadsTheStagedClosureDependenciesFirst() {
        val closure = mapOf(
            "archivetune_engine.dll" to listOf("avcodec-62.dll"),
            "avcodec-62.dll" to listOf("avutil-60.dll", "zlib1.dll"),
            "avutil-60.dll" to listOf("zlib1.dll"),
            "zlib1.dll" to emptyList(),
        )
        withNativeDirectory(closure) { directory ->
            val loaded = mutableSetOf<String>()
            val attempts = mutableListOf<String>()
            val preloader = NativeDependencyPreloader(
                load = { library ->
                    attempts += library.name
                    val missing = closure.getValue(library.name).filterNot { it in loaded }
                    check(missing.isEmpty()) { "${library.name} was loaded before $missing" }
                    loaded += library.name
                },
            )

            val result = preloader.preload(directory, "archivetune_engine.dll")

            assertTrue(result.ordered)
            assertEquals(emptyList(), result.failures)
            // The manifest's order is a dependency order, and the engine itself stays the caller's job.
            assertEquals(listOf("zlib1.dll", "avutil-60.dll", "avcodec-62.dll"), attempts)
        }
    }

    @Test
    fun namesTheSiblingThatCouldNotBeLoaded() {
        withNativeDirectory(mapOf("archivetune_engine.dll" to listOf("avcodec-62.dll"), "avcodec-62.dll" to emptyList())) { directory ->
            val preloader = NativeDependencyPreloader(load = { error("not a valid Win32 application") })

            val result = preloader.preload(directory, "archivetune_engine.dll")

            assertEquals(listOf("avcodec-62.dll"), result.failures.map { it.library })
            assertTrue(result.describe("the engine failed").contains("avcodec-62.dll did not load: not a valid Win32 application"))
        }
    }

    @Test
    fun resolvesTheClosureWithoutAManifest() {
        // Sorted file order puts avcodec first, so a single pass cannot load it; the retry rounds must.
        val closure = mapOf(
            "avcodec-62.dll" to listOf("avutil-60.dll"),
            "avutil-60.dll" to listOf("zlib1.dll"),
            "zlib1.dll" to emptyList(),
        )
        withNativeDirectory(closure, manifest = false) { directory ->
            val loaded = mutableSetOf<String>()
            val preloader = NativeDependencyPreloader(
                load = { library ->
                    check(closure.getValue(library.name).all { it in loaded }) { "${library.name} needs its imports first" }
                    loaded += library.name
                },
            )

            val result = preloader.preload(directory, "archivetune_engine.dll")

            assertFalse(result.ordered)
            assertEquals(emptyList(), result.failures)
            assertEquals(setOf("avcodec-62.dll", "avutil-60.dll", "zlib1.dll"), loaded)
        }
    }

    @Test
    fun parsesTheStagedManifestAndOrdersItsEntries() {
        val manifest = NativeDependencyManifest.parse(
            listOf(
                "# written by windows-native-deps.py",
                "",
                "ArchivEtuNe_Engine.DLL=avcodec-62.dll, swscale-9.dll",
                "swscale-9.dll=avutil-60.dll",
                "avcodec-62.dll=",
                "avutil-60.dll=",
                "not a manifest line",
            ),
        )

        assertEquals(listOf("avutil-60.dll"), manifest.getValue("swscale-9.dll"))
        assertEquals(
            listOf("avcodec-62.dll", "avutil-60.dll", "swscale-9.dll"),
            NativeDependencyManifest.loadOrder(manifest, "archivetune_engine.dll"),
        )
    }

    @Test
    fun anImportCycleKeepsEveryLibraryOnce() {
        val manifest = NativeDependencyManifest.parse(listOf("a.dll=b.dll", "b.dll=a.dll", "root.dll=a.dll"))

        assertEquals(setOf("a.dll", "b.dll"), NativeDependencyManifest.loadOrder(manifest, "root.dll").toSet())
    }

    private fun withNativeDirectory(
        dependencies: Map<String, List<String>>,
        manifest: Boolean = true,
        block: (File) -> Unit,
    ) {
        val directory = Files.createTempDirectory("natune-native-deps").toFile()
        try {
            dependencies.keys.forEach { File(directory, it).writeText(it) }
            if (manifest) {
                File(directory, NativeDependencyManifest.FILE_NAME).writeText(
                    dependencies.entries.joinToString("\n") { (library, imports) ->
                        "$library=${imports.joinToString(",")}"
                    },
                )
            }
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
