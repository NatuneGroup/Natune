package moe.rukamori.archivetune.shared.data.localmedia

import kotlin.test.*

class ImportedMediaNamingTest {
    @Test fun thePickedLeafNameIsKeptWithASafeExtension() {
        assertEquals("Song.mp3", importedMediaFileName("Song.mp3", { false }))
        assertEquals("Song.flac", importedMediaFileName("Folder/Song.FLAC", { false }))
        assertEquals("Song.m4a", importedMediaFileName("a/b\\Song.m4a", { false }))
        assertEquals("Importação-2.mp3", importedMediaFileName("x/Importação-2.mp3", { false }))
    }

    @Test fun separatorsAndDotEntriesBecomeOnePlainName() {
        assertEquals("escape.mp3", importedMediaFileName("../escape.mp3", { false }))
        assertEquals("imported", importedMediaFileName("..", { false }))
        assertEquals("mp3", importedMediaFileName(".mp3", { false }))
        listOf("../escape.mp3", "a/b\\c.mp3", "..", ".", "name with spaces.mp3").forEach { picked ->
            val name = importedMediaFileName(picked, { false })
            assertFalse('/' in name, picked)
            assertFalse('\\' in name, picked)
            assertNotEquals("..", name, picked)
            assertNotEquals(".", name, picked)
        }
    }

    @Test fun aTakenNameGainsTheFirstFreeSuffix() {
        val taken = mutableSetOf("Song.mp3", "Song-1.mp3")
        assertEquals("Song-2.mp3", importedMediaFileName("Song.mp3", { it in taken }))
        taken += "Song-2.mp3"
        assertEquals("Song-3.mp3", importedMediaFileName("Song.mp3", { it in taken }))
    }

    @Test fun anOversizedOrNonAsciiExtensionIsDroppedRatherThanCarried() {
        assertEquals("Track", importedMediaFileName("Track.verylongextension", { false }))
        assertEquals("Track.xyz1", importedMediaFileName("Track.xyz1", { false }))
        assertEquals("Track", importedMediaFileName("Track.日本語", { false }))
    }
}
