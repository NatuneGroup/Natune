package moe.rukamori.archivetune.shared.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.lyrics.KotlinTtmlDocumentReader
import moe.rukamori.archivetune.shared.domain.lyrics.TtmlDocument
import moe.rukamori.archivetune.shared.domain.lyrics.TtmlDocumentReader
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.RustTtmlParserKey

class TtmlReaderSelectionTest {
    private val ttml = """<tt><body><div><p begin="1s" end="2s">Hello</p></div></body></tt>"""

    @Test
    fun kotlinReaderIsUsedUnlessTheFlagIsExplicitlyOn() {
        assertSame(KotlinTtmlDocumentReader, ttmlDocumentReader(null))
        assertSame(KotlinTtmlDocumentReader, ttmlDocumentReader(PreferenceSnapshot.EMPTY))
        assertSame(KotlinTtmlDocumentReader, ttmlDocumentReader(PreferenceSnapshot.of(RustTtmlParserKey to false)))
    }

    @Test
    fun flagOnStillReadsLyricsWhateverTheBuildPackaged() = runTest {
        val expected = KotlinTtmlDocumentReader.readTtml(ttml)
        assertEquals(1, expected.lines.size)
        assertEquals(expected, ttmlDocumentReader(PreferenceSnapshot.of(RustTtmlParserKey to true)).readTtml(ttml))
    }

    @Test
    fun nativeFailureFallsBackToKotlinButCancellationPropagates() = runTest {
        val failing = withKotlinFallback(TtmlDocumentReader { throw IllegalStateException("native") })
        assertEquals(KotlinTtmlDocumentReader.readTtml(ttml), failing.readTtml(ttml))

        val linkage = withKotlinFallback(TtmlDocumentReader { throw UnsupportedOperationException("unsatisfied link") })
        assertEquals(KotlinTtmlDocumentReader.readTtml(ttml), linkage.readTtml(ttml))

        val cancelled = withKotlinFallback(TtmlDocumentReader { throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { cancelled.readTtml(ttml) }

        val refused = withKotlinFallback(TtmlDocumentReader { TtmlDocument(emptyList()) })
        assertEquals(TtmlDocument(emptyList()), refused.readTtml(ttml))
    }
}
