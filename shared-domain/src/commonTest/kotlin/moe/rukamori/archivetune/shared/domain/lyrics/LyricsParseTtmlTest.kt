package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LyricsParseTtmlTest {
    private val duet =
        """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="en">
          <head>
            <metadata>
              <iTunesMetadata>
                <translations>
                  <translation type="original" xml:lang="es">
                    <text for="L1">Hola mundo</text>
                  </translation>
                </translations>
                <transliterations>
                  <transliteration xml:lang="es-Latn">
                    <text for="L1">hola mundo</text>
                  </transliteration>
                </transliterations>
              </iTunesMetadata>
            </metadata>
          </head>
          <body>
            <div>
              <p begin="1.000" end="3.000" ttm:agent="v1" itunes:key="L1"><span begin="1.000" end="1.500">Hello</span> <span begin="1.500" end="2.000">world</span></p>
              <p begin="4.000" end="6.000" ttm:agent="v2" itunes:key="L2"><span begin="4.000" end="5.000">Hi</span></p>
            </div>
          </body>
        </tt>
        """.trimIndent()

    @Test
    fun explicitWordTimingBecomesKaraokeWords() {
        val lines = LyricsParser.parseTtml(duet)

        val first = assertIs<KaraokeLine>(lines.first())
        assertEquals(1_000L, first.time)
        assertEquals("Hello world", first.text)
        assertEquals(listOf("Hello ", "world"), first.words.map { it.text })
        assertEquals(1.0, first.words[0].startTime, 0.0001)
        assertEquals(1.5, first.words[0].endTime, 0.0001)
        assertEquals(1.5, first.words[1].startTime, 0.0001)
        assertEquals(2.0, first.words[1].endTime, 0.0001)
    }

    @Test
    fun agentAttributesMarkDuetVoices() {
        val lines = LyricsParser.parseTtml(duet)

        assertEquals(listOf("v1", "v2"), lines.map { it.agent })
    }

    @Test
    fun translationAndTransliterationAreReadThroughTheLineKey() {
        val first = LyricsParser.parseTtml(duet).first()

        assertEquals("Hola mundo", first.providerTranslationText)
        assertEquals("hola mundo", first.providerRomanizedText)
        assertEquals("es-Latn", first.providerRomanizedLanguage)
    }

    @Test
    fun lineWithoutWordSpansStaysSynced() {
        val lines =
            LyricsParser.parseTtml(
                """
                <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
                <p begin="2.000" end="3.000">Plain line</p>
                </div></body></tt>
                """.trimIndent(),
            )

        assertIs<SyncedLine>(lines.single())
        assertEquals("Plain line", lines.single().text)
        assertEquals(2_000L, lines.single().time)
    }

    @Test
    fun backgroundSpansAreMarkedAsBackgroundWords() {
        val lines =
            LyricsParser.parseTtml(
                """
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
                <p begin="1.000" end="4.000" ttm:agent="v1"><span begin="1.000" end="2.000">Hi</span><span ttm:role="x-bg" begin="2.000" end="3.000">echo</span></p>
                </div></body></tt>
                """.trimIndent(),
            )

        val words = assertIs<KaraokeLine>(lines.single()).words
        assertEquals(listOf("Hi", "echo"), words.map { it.text })
        assertEquals(listOf(false, true), words.map { it.isBackground })
    }

    @Test
    fun relativeWordTimesAreShiftedToTheLineStart() {
        val lines =
            LyricsParser.parseTtml(
                """
                <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
                <p begin="10.000" end="12.000"><span begin="0.000" end="1.000">Hi</span> <span begin="1.000" end="2.000">there</span></p>
                </div></body></tt>
                """.trimIndent(),
            )

        val line = assertIs<KaraokeLine>(lines.single())
        assertEquals(10_000L, line.time)
        assertEquals(listOf(10.0, 11.0), line.words.map { it.startTime })
        assertEquals(listOf(11.0, 12.0), line.words.map { it.endTime })
    }

    @Test
    fun frameRateAndTickRateConvertOffsetTimes() {
        val lines =
            LyricsParser.parseTtml(
                """
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:frameRate="25" ttp:tickRate="1000"><body><div>
                <p begin="50f" end="100f">Frames</p>
                <p begin="1500t" end="3000t">Ticks</p>
                </div></body></tt>
                """.trimIndent(),
            )

        assertEquals(listOf("Ticks", "Frames"), lines.map { it.text })
        assertEquals(listOf(1_500L, 2_000L), lines.map { it.time })
    }

    @Test
    fun paragraphsAreSortedByStartTime() {
        val lines =
            LyricsParser.parseTtml(
                """
                <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
                <p begin="5.000" end="6.000">Second</p>
                <p begin="1.000" end="2.000">First</p>
                </div></body></tt>
                """.trimIndent(),
            )

        assertEquals(listOf("First", "Second"), lines.map { it.text })
    }

    @Test
    fun malformedXmlYieldsAnEmptyList() {
        assertTrue(LyricsParser.parseTtml("<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"1.0\"").isEmpty())
    }

    @Test
    fun ttmlDocumentIsDetectedAndWordSynced() {
        assertTrue(LyricsDetection.isTtml(duet))
        assertTrue(LyricsDetection.hasWordSyncedLyrics(duet))
        assertFalse(LyricsDetection.isTtml("[00:01.00]Hello"))
    }

    @Test
    fun displayTextOfTtmlIsOneRowPerParagraph() {
        assertEquals("Hello world\nHi", LyricsDetection.displayText(duet))
    }

    private val credited =
        """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xml:lang="en">
          <head>
            <metadata>
              <iTunesMetadata>
                <songwriters>
                  <songwriter>Ada Writer</songwriter>
                  <songwriter>Sam Tune</songwriter>
                </songwriters>
                <translations>
                  <translation xml:lang="es" itunes:automaticallyCreated="true">
                    <text for="L1">Hola mundo</text>
                  </translation>
                </translations>
                <transliterations>
                  <transliteration xml:lang="es-Latn">
                    <text for="L1" itunes:automaticallyCreated="1">hola mundo</text>
                  </transliteration>
                </transliterations>
              </iTunesMetadata>
            </metadata>
          </head>
          <body>
            <div>
              <p begin="1.000" end="3.000" itunes:key="L1">Hello world</p>
            </div>
          </body>
        </tt>
        """.trimIndent()

    @Test
    fun songwritersAndAutoCreatedFlagsComeFromTheDocument() {
        val credits = LyricsParser.parseTtmlCredits(credited)

        assertEquals(listOf("Ada Writer", "Sam Tune"), credits.songwriters)
        assertTrue(credits.translationAutoCreated)
        assertTrue(credits.transliterationAutoCreated)
        assertFalse(credits.isEmpty)
    }

    @Test
    fun aDocumentWithoutCreditsParsesEmpty() {
        val credits = LyricsParser.parseTtmlCredits(duet)

        assertEquals(emptyList(), credits.songwriters)
        assertFalse(credits.translationAutoCreated)
        assertFalse(credits.transliterationAutoCreated)
        assertTrue(credits.isEmpty)
    }

    @Test
    fun nonTtmlAndMalformedInputParseEmpty() {
        assertTrue(LyricsParser.parseTtmlCredits("[00:01.00] hello").isEmpty)
        assertTrue(LyricsParser.parseTtmlCredits("").isEmpty)
        assertTrue(LyricsParser.parseTtmlCredits("<tt><unclosed").isEmpty)
    }
}
