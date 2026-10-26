package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** QQ Music QRC framing: `[lineStartMs,lineDurationMs]text(wordStartMs,wordDurationMs)`. */
class LyricsParseQrcTest {
    private val demo =
        """
        [ti:Demo Track]
        [ar:Demo Artist]
        [1000,2000]Hello(1000,800) world(1800,1200)
        [3000,2000]Second(3000,1000) line(4000,1000)
        """.trimIndent()

    @Test
    fun qrcWordTimesAreSecondsAndLineDurationIsKept() {
        val lines = LyricsParser.parse(demo)

        assertEquals(listOf(1_000L, 3_000L), lines.map { it.time })
        assertEquals(listOf(2_000L, 2_000L), lines.map { it.durationMs })
        val first = assertIs<KaraokeLine>(lines.first())
        assertEquals("Hello world", first.text)
        assertEquals(2, first.words.size)
        assertEquals(1.0, first.words[0].startTime, 0.0001)
        assertEquals(1.8, first.words[0].endTime, 0.0001)
        assertEquals(1.8, first.words[1].startTime, 0.0001)
        assertEquals(3.0, first.words[1].endTime, 0.0001)
    }

    @Test
    fun qrcIsDetectedAsLineSyncedAndWordSynced() {
        assertTrue(LyricsDetection.isLineSyncedLrc(demo))
        assertTrue(LyricsDetection.hasWordSyncedLyrics(demo))
    }

    @Test
    fun metadataRowsAreNotLyricLines() {
        val lines = LyricsParser.parse(demo)

        assertEquals(2, lines.size)
        assertTrue(lines.all { it is KaraokeLine })
    }

    @Test
    fun translationRowsFoldUnderTheWordTimedRowWithTheSameStart() {
        val lines =
            LyricsParser.parse(
                """
                [1000,2000]Hello(1000,800) world(1800,1200)
                [1000,2000]Hola mundo
                """.trimIndent(),
            )

        assertEquals(1, lines.size)
        assertEquals("Hello world", lines.single().text)
        assertEquals("Hola mundo", lines.single().providerTranslationText)
    }

    @Test
    fun singerPrefixesAssignVoicesAndChorusAlias() {
        val lines =
            LyricsParser.parse(
                """
                [1000,2000]Alice:(1000,300)Hello(1300,700)
                [3000,2000]Bob:(3000,300)Hi(3300,700)
                [5000,2000]Alice:(5000,300)Again(5300,700)
                [7000,1000]合:(7000,300)Together(7300,700)
                """.trimIndent(),
            )

        assertEquals(listOf("v1", "v2", "v1", "v1000"), lines.map { it.agent })
        assertEquals(listOf("Hello", "Hi", "Again", "Together"), lines.map { it.text })
    }

    @Test
    fun aRowWithoutAPrefixInheritsTheCurrentSinger() {
        val lines =
            LyricsParser.parse(
                """
                [1000,2000]Alice:(1000,300)Hello(1300,700)
                [3000,2000]Still(3000,500) Alice(3500,500)
                """.trimIndent(),
            )

        assertEquals(listOf("v1", "v1"), lines.map { it.agent })
    }

    @Test
    fun creditRowsAreDroppedWhenTheirPrefixIsAMetadataLabel() {
        val lines =
            LyricsParser.parse(
                """
                [500,1000]Lyricist:(500,300)Jane(800,700)
                [2000,2000]Real(2000,1000) line(3000,1000)
                """.trimIndent(),
            )

        assertEquals(listOf("Real line"), lines.map { it.text })
    }

    @Test
    fun millisecondYrcFramingKeepsLineTimeAndPlainText() {
        val lines = LyricsParser.parse("[1200,900]Plain text")

        assertEquals(listOf(1_200L), lines.map { it.time })
        assertEquals("Plain text", lines.single().text)
        assertEquals(null, lines.single().words)
    }
}
