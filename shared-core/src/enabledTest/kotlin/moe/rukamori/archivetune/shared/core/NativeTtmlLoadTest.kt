package moe.rukamori.archivetune.shared.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NativeTtmlLoadTest {
    @Test fun actualLibraryLoadsAndMapsRowsAndCredits() = runTest {
        val reader = assertNotNull(rustTtmlReaderOrNull(), "Built native library must load on this platform")
        val doc = reader.readTtml("<tt><p begin=\"1s\">Hello</p><songwriter>Ada</songwriter></tt>")
        assertEquals("Hello", doc.lines.single().text)
        assertEquals(listOf("Ada"), doc.credits.songwriters)
    }
}
