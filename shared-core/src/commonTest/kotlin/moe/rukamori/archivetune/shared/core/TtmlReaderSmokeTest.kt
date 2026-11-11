package moe.rukamori.archivetune.shared.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TtmlReaderSmokeTest {
    @Test fun readerLoadsWhenBuiltAndReturnsRows() = runTest {
        val reader = rustTtmlReaderOrNull() ?: return@runTest // Default build intentionally has no native library.
        val doc = reader.readTtml("<tt><p begin=\"1s\">Hello</p><songwriter>Ada</songwriter></tt>")
        assertEquals("Hello", doc.lines.single().text)
        assertEquals(1000L, doc.lines.single().time)
        assertEquals(listOf("Ada"), doc.credits.songwriters)
    }
}
