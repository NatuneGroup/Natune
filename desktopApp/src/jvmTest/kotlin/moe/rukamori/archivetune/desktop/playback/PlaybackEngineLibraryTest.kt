package moe.rukamori.archivetune.desktop.playback

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackEngineLibraryTest {
    @Test
    fun theSystemPropertyWinsOverTheEnvironmentVariable() {
        assertEquals("/dev/engine.so", explicitEngineLibraryPath("/dev/engine.so", "/env/engine.so"))
    }

    @Test
    fun aBlankExplicitValueCountsAsUnset() {
        assertEquals("/env/engine.so", explicitEngineLibraryPath("  ", "/env/engine.so"))
        assertNull(explicitEngineLibraryPath("", " "))
        assertNull(explicitEngineLibraryPath(null, null))
    }

    @Test
    fun aMissingExplicitLibraryIsNotPresent() {
        val explicit = File("/dev/natune/missing.so")

        assertFalse(engineLibraryPresent(explicit.path, "/app/resources", NAME, null) { false })
    }

    @Test
    fun aPresentExplicitLibraryNeedsNoResourcesDirectory() {
        val explicit = File("/dev/natune/engine.so")

        assertTrue(engineLibraryPresent(explicit.path, null, NAME, null) { it == explicit })
    }

    @Test
    fun thePackagedLibraryIsFoundUnderTheResourcesNativeDirectory() {
        val resources = File("/app/resources")
        val packaged = File(File(resources, "native"), NAME)

        assertTrue(engineLibraryPresent(null, resources.path, NAME, null) { it == packaged })
    }

    @Test
    fun theLibrarySearchPathIsTheLastResort() {
        val directories = listOf(File("/usr/lib/natune"), File("/opt/natune"))
        val searchPath = directories.joinToString(File.pathSeparator) { it.path }
        val onPath = File(directories.last(), NAME)

        assertTrue(engineLibraryPresent(null, null, NAME, searchPath) { it == onPath })
    }

    @Test
    fun noLibraryAnywhereIsNotPresent() {
        assertFalse(engineLibraryPresent(null, "/app/resources", NAME, "") { false })
        assertFalse(engineLibraryPresent(null, null, NAME, null) { false })
    }

    private companion object {
        const val NAME = "libarchivetune_engine.so"
    }
}
