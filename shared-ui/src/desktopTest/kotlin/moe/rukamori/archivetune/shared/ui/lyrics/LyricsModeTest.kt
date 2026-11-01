package moe.rukamori.archivetune.shared.ui.lyrics

import androidx.compose.ui.graphics.Color
import moe.rukamori.archivetune.shared.domain.lyrics.KaraokeLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsWord
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiProvider
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiRomanizationSettings
import moe.rukamori.archivetune.shared.domain.lyrics.ai.AiServiceSettings
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.IcuLyricsTransliterator
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.KuromojiJapaneseTokenizer
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsPhonetics
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsRomanizationPreferences
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsRomanizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mode selection, tap and selection rules, and the romanization presentation that the fullscreen looks read. */
class LyricsModeTest {
    @Test
    fun storedModeParsesEveryNameAndFallsBackToEnhanced() {
        assertEquals(LyricsMode.V2, LyricsMode.fromStored("V2"))
        assertEquals(LyricsMode.SPOTIFY, LyricsMode.fromStored(" SPOTIFY "))
        assertEquals(LyricsMode.SIMPMUSIC, LyricsMode.fromStored("SIMPMUSIC"))
        assertEquals(LyricsMode.ENHANCED, LyricsMode.fromStored("ENHANCED"))
        assertEquals(LyricsMode.ENHANCED, LyricsMode.fromStored(null))
        assertEquals(LyricsMode.ENHANCED, LyricsMode.fromStored("retired-mode"))
    }

    @Test
    fun fullscreenLooksKeepTheirBaselineManualScrollHolds() {
        assertEquals(4_500L, LyricsModeProfile.MANUAL_SCROLL_HOLD_MS)
        listOf(LyricsMode.ENHANCED, LyricsMode.V2, LyricsMode.SPOTIFY).forEach { mode ->
            assertEquals(4_500L, LyricsModeProfile.of(mode).manualScrollHoldMs, "hold for $mode")
        }
        assertEquals(4_000L, LyricsModeProfile.of(LyricsMode.SIMPMUSIC).manualScrollHoldMs, "hold for SIMPMUSIC")
    }

    @Test
    fun onlySimpMusicKeepsTheManualScrollHoldOpenWhileTheListIsDragged() {
        assertTrue(LyricsModeProfile.of(LyricsMode.SIMPMUSIC).holdsWhileDragged)
        listOf(LyricsMode.ENHANCED, LyricsMode.V2, LyricsMode.SPOTIFY).forEach { mode ->
            assertFalse(LyricsModeProfile.of(mode).holdsWhileDragged, "drag hold for $mode")
        }
    }

    @Test
    fun lookProfilesKeepTheirAndroidWidthsAndSweepFloors() {
        assertEquals(860, LyricsModeProfile.of(LyricsMode.ENHANCED).maxWidthDp)
        assertEquals(760, LyricsModeProfile.of(LyricsMode.SPOTIFY).maxWidthDp)
        assertEquals(180L, LyricsModeProfile.of(LyricsMode.V2).minSweepMs)
        assertEquals(1.3f, LyricsModeProfile.of(LyricsMode.SIMPMUSIC).lineSpacingFactor)
        assertTrue(LyricsMode.entries.map { LyricsModeProfile.of(it).mainSizeSp }.all { it > 0f })
    }

    @Test
    fun tapSeeksOnlyTimedRowsWhenTapToSeekIsOn() {
        val timed = LyricsRenderRows.build(listOf(SyncedLine(time = 4_000, text = "timed"))).single()
        val untimed = LyricsRenderRows.build(listOf(SyncedLine(time = 0, text = "untimed"))).single()

        assertEquals(LyricsTapAction.Seek(4_000L), LyricsTapPolicy.onTap(timed, selectionActive = false, tapToSeek = true))
        assertEquals(LyricsTapAction.None, LyricsTapPolicy.onTap(timed, selectionActive = false, tapToSeek = false))
        assertEquals(LyricsTapAction.None, LyricsTapPolicy.onTap(untimed, selectionActive = false, tapToSeek = true))
    }

    @Test
    fun whileSelectingATapTogglesSelectionInsteadOfSeeking() {
        val timed = LyricsRenderRows.build(listOf(SyncedLine(time = 4_000, text = "timed"))).single()

        assertEquals(LyricsTapAction.ToggleSelection, LyricsTapPolicy.onTap(timed, selectionActive = true, tapToSeek = true))
    }

    @Test
    fun selectionIsCappedAtFiveAndSharesInDisplayOrder() {
        var selection = LyricsSelectionState()
        listOf(4, 1, 3, 0, 2).forEach { selection = selection.toggled(it) }
        assertTrue(selection.isActive)
        assertEquals(listOf(0, 1, 2, 3, 4), selection.sortedIndices())

        val full = selection.toggled(9)
        assertEquals(selection, full, "a sixth line is ignored, not swapped in")
        assertEquals(LyricsSelectionState.MAX_SELECTED_LINES, selection.indices.size)

        val removed = selection.toggled(3)
        assertEquals(listOf(0, 1, 2, 4), removed.sortedIndices())
        assertFalse(LyricsSelectionState().isActive)
    }

    @Test
    fun aiResultTakesOverEligibleLinesAndAlignsToEachLine() {
        val lines = listOf(
            SyncedLine(time = 0, text = "東京タワー", providerRomanizedLanguage = "ja"),
            SyncedLine(time = 1_000, text = "Hello", providerRomanizedLanguage = "en"),
        )
        val ai = AiRomanizationReadings(
            sessionKey = SESSION_KEY,
            romanizedByLine = mapOf("東京タワー" to "Tokyo Tower", "Hello" to "hello"),
        )

        val presentation = LyricsRomanizationPresenter.present(
            sessionKey = SESSION_KEY,
            lines = lines,
            builtIn = preferences(romanizeJapanese = true),
            romanizer = LyricsRomanizer(),
            aiSettings = aiSettings(),
            sourceLanguageHints = listOf("ja"),
            ai = ai,
        )

        assertEquals(LyricsRomanizationSource.AI, presentation.source)
        assertEquals("Tokyo Tower", presentation.phonetics[0]?.line)
        // An echo of the original line carries no information, so that line keeps no reading.
        assertNull(presentation.phonetics[1])
    }

    @Test
    fun excludedAiFallsBackToBuiltInReadings() {
        val lines = listOf(SyncedLine(time = 0, text = "東京", providerRomanizedLanguage = "ja"))
        val excluded = AiRomanizationReadings(
            sessionKey = SESSION_KEY,
            romanizedByLine = mapOf("東京" to "AI Tokyo"),
        )

        val presentation = LyricsRomanizationPresenter.present(
            sessionKey = SESSION_KEY,
            lines = lines,
            builtIn = preferences(romanizeJapanese = true),
            romanizer = japaneseRomanizer(),
            aiSettings = aiSettings(excludedLanguages = setOf("ja")),
            sourceLanguageHints = listOf("ja"),
            ai = excluded,
        )

        assertEquals(LyricsRomanizationSource.BUILT_IN, presentation.source)
        assertEquals("toukyou", presentation.phonetics[0]?.line)
    }

    @Test
    fun aiResultForAnotherSessionIsNotUsed() {
        val lines = listOf(SyncedLine(time = 0, text = "東京", providerRomanizedLanguage = "ja"))
        val stale = AiRomanizationReadings(
            sessionKey = "an-earlier-track",
            romanizedByLine = mapOf("東京" to "Old Tokyo"),
        )

        val presentation = LyricsRomanizationPresenter.present(
            sessionKey = SESSION_KEY,
            lines = lines,
            builtIn = preferences(romanizeJapanese = true),
            romanizer = japaneseRomanizer(),
            aiSettings = aiSettings(),
            sourceLanguageHints = listOf("ja"),
            ai = stale,
        )

        // Eligible AI owns the line, so the stale text is dropped and this line waits for its own result.
        assertEquals(LyricsRomanizationSource.AI, presentation.source)
        assertNull(presentation.phonetics[0])
    }

    @Test
    fun eligibleAiWithoutAResultShowsNoBuiltInReading() {
        val lines = listOf(SyncedLine(time = 0, text = "東京", providerRomanizedLanguage = "ja"))

        val presentation = LyricsRomanizationPresenter.present(
            sessionKey = SESSION_KEY,
            lines = lines,
            builtIn = preferences(romanizeJapanese = true),
            romanizer = japaneseRomanizer(),
            aiSettings = aiSettings(),
            sourceLanguageHints = listOf("ja"),
        )

        assertEquals(LyricsRomanizationSource.AI, presentation.source)
        assertNull(presentation.phonetics[0])
    }

    @Test
    fun aiReadingsAlignByTrimmedLineTextSoRepeatedLinesShareOneReading() {
        val lines = listOf(
            SyncedLine(time = 0, text = "東京"),
            SyncedLine(time = 1_000, text = "東京"),
            SyncedLine(time = 2_000, text = " 大阪 "),
        )
        val ai = AiRomanizationReadings(
            sessionKey = SESSION_KEY,
            romanizedByLine = mapOf("東京" to "Tokyo", "大阪" to "Osaka"),
        )

        val presentation = LyricsRomanizationPresenter.present(
            sessionKey = SESSION_KEY,
            lines = lines,
            builtIn = preferences(),
            romanizer = japaneseRomanizer(),
            aiSettings = aiSettings(),
            sourceLanguageHints = listOf("ja"),
            ai = ai,
        )

        assertEquals(LyricsRomanizationSource.AI, presentation.source)
        assertEquals(listOf("Tokyo", "Tokyo", "Osaka"), presentation.phonetics.map { it?.line })
    }

    @Test
    fun providerReadingsAlignToTimedWordsAndKeepSourceRanges() {
        // Provider word readings apply only with the line-level reading that accompanies them, as in TTML transliterations.
        val line = KaraokeLine(
            time = 0,
            text = "東京 タワー",
            providerRomanizedText = "Tokyo Tower",
            providerRomanizedWords = listOf("Tokyo", "Tower"),
            providerRomanizedLanguage = "ja",
            words = listOf(LyricsWord("東京", 0.0, 0.5), LyricsWord("タワー", 0.5, 1.0)),
        )

        val presentation = LyricsRomanizationPresenter.present(
            sessionKey = SESSION_KEY,
            lines = listOf(line),
            builtIn = preferences(romanizeJapanese = true),
            romanizer = LyricsRomanizer(),
        )
        assertEquals(LyricsRomanizationSource.PROVIDER, presentation.source)
        val rows = LyricsRenderRows.build(listOf<LyricsLine>(line), presentation.phonetics)

        assertEquals("東京 タワー", rows[0].text)
        assertEquals("Tokyo Tower", rows[0].phonetic)
        assertEquals(listOf(0..1, 3..5), rows[0].words!!.map { it.range })
    }

    @Test
    fun disabledRomanizationPresentsNothing() {
        val lines = listOf(SyncedLine(time = 0, text = "東京", providerRomanizedLanguage = "ja"))

        val presentation = LyricsRomanizationPresenter.present(
            sessionKey = SESSION_KEY,
            lines = lines,
            builtIn = preferences(),
            romanizer = japaneseRomanizer(),
        )

        assertEquals(LyricsRomanizationPresentation.off(SESSION_KEY), presentation)
    }

    @Test
    fun publishedPresentationReplacesTheVisibleValueWithoutAnotherSession() {
        val state = LyricsRomanizationState(LyricsRomanizationPresentation.off(SESSION_KEY))
        val reading = LyricsRomanizationPresentation(
            sessionKey = SESSION_KEY,
            source = LyricsRomanizationSource.BUILT_IN,
            phonetics = listOf(LyricsPhonetics(line = "toukyou")),
        )

        state.publish(reading)

        assertEquals(reading, state.presentation.value)
    }

    @Test
    fun enhancedAndSpotifyMoveOnlyWordTimedSecondVoiceRowsToTheEnd() {
        val secondVoice = row(karaoke(startMs = 0, first = "back", second = "up", agent = "v2"))
        val firstVoice = row(karaoke(startMs = 0, first = "lead", second = "voice", agent = "v1"))
        val lineOnly = row(SyncedLine(time = 1_000, text = "line only", agent = "v2"))

        listOf(LyricsMode.ENHANCED, LyricsMode.SPOTIFY).forEach { mode ->
            assertEquals(LyricsLineAlignment.END, LyricsAlignmentPolicy.of(mode, secondVoice), "second voice in $mode")
            assertEquals(LyricsLineAlignment.START, LyricsAlignmentPolicy.of(mode, firstVoice), "first voice in $mode")
            assertEquals(LyricsLineAlignment.START, LyricsAlignmentPolicy.of(mode, lineOnly), "line-only text in $mode")
        }
    }

    @Test
    fun v2AlignsByAgentWithoutCaseAndSimpMusicFollowsTheScript() {
        assertEquals(LyricsLineAlignment.START, LyricsAlignmentPolicy.of(LyricsMode.V2, row(SyncedLine(time = 1_000, text = "none"))))
        assertEquals(
            LyricsLineAlignment.START,
            LyricsAlignmentPolicy.of(LyricsMode.V2, row(SyncedLine(time = 1_000, text = "v1", agent = "v1"))),
        )
        assertEquals(
            LyricsLineAlignment.END,
            LyricsAlignmentPolicy.of(LyricsMode.V2, row(SyncedLine(time = 1_000, text = "v2", agent = "V2"))),
        )
        assertEquals(
            LyricsLineAlignment.CENTER,
            LyricsAlignmentPolicy.of(LyricsMode.V2, row(SyncedLine(time = 1_000, text = "other", agent = "v3"))),
        )

        assertEquals(
            LyricsLineAlignment.END,
            LyricsAlignmentPolicy.of(LyricsMode.SIMPMUSIC, row(SyncedLine(time = 1_000, text = "مرحبا"))),
        )
        assertEquals(
            LyricsLineAlignment.START,
            LyricsAlignmentPolicy.of(LyricsMode.SIMPMUSIC, row(SyncedLine(time = 1_000, text = "hello"))),
        )
    }

    @Test
    fun shapedRowsPlaceTheirTextAtTheAlignmentTheLookChose() {
        val profile = LyricsModeProfile.of(LyricsMode.ENHANCED)
        val shortLine = row(SyncedLine(time = 1_000, text = "hi"))
        val measurer = testTextMeasurer()

        fun leftEdge(alignment: LyricsLineAlignment): Float {
            val key = LyricsShapeKey(rowIndex = 0, widthPx = 400, style = lyricsMainStyle(profile, alignment))
            return shapeLyricRow(measurer, shortLine, key).layout.getBoundingBox(0).left
        }

        assertTrue(leftEdge(LyricsLineAlignment.START) < 1f)
        assertTrue(leftEdge(LyricsLineAlignment.CENTER) in 100f..300f)
        assertTrue(leftEdge(LyricsLineAlignment.END) > 300f)
    }

    @Test
    fun aPassedLineReadsAsTheActiveColourAtSeventyPercent() {
        val palette = LyricsPalette(dim = Color.Black, bright = Color.White, glow = Color.Red)

        assertEquals(LyricsPalette.PASSED_ALPHA, palette.passed.alpha, 0.0001f)
        assertEquals(palette.bright.copy(alpha = LyricsPalette.PASSED_ALPHA), palette.passed)
        assertEquals(LyricsPalette.PASSED_ALPHA, 0.7f, 0.0001f)
    }

    @Test
    fun onlySyncedRowsBehindThePlayheadCountAsPassed() {
        val rows = LyricsRenderRows.build(
            listOf(
                SyncedLine(time = 1_000, text = "one"),
                SyncedLine(time = 2_000, text = "two"),
                SyncedLine(time = 3_000, text = "three"),
            ),
        )

        // A row passes once the feed clears its end (the next start here), not when a later row is active.
        assertFalse(isPassedLyricRow(rows[0], 1_000))
        assertFalse(isPassedLyricRow(rows[1], 2_500))
        assertTrue(isPassedLyricRow(rows[0], 2_000))
        assertTrue(isPassedLyricRow(rows[0], 2_500))
        assertFalse(isPassedLyricRow(rows[0].copy(isSynced = false), 9_000))
    }

    @Test
    fun aFinishedRowStaysPassedThroughGapsAndPastTheLastLine() {
        val rows = LyricsRenderRows.build(
            listOf(
                SyncedLine(time = 1_000, text = "one", durationMs = 500),
                SyncedLine(time = 5_000, text = "two", durationMs = 1_000),
            ),
        )

        // The feed sits in the instrumental gap between the lines, where no row is active.
        assertTrue(isPassedLyricRow(rows[0], 3_000))
        assertFalse(isPassedLyricRow(rows[1], 3_000))
        // And past the last line's span the outro still counts it as passed.
        assertTrue(isPassedLyricRow(rows[1], 7_000))
    }

    private fun row(line: LyricsLine): LyricsRenderRow = LyricsRenderRows.build(listOf(line)).single()

    private fun japaneseRomanizer(): LyricsRomanizer =
        LyricsRomanizer(
            japaneseTokenizer = KuromojiJapaneseTokenizer.create(),
            transliterator = IcuLyricsTransliterator(),
        )

    private fun preferences(romanizeJapanese: Boolean = false): LyricsRomanizationPreferences =
        LyricsRomanizationPreferences(
            romanizeJapanese = romanizeJapanese,
            romanizeKorean = false,
            romanizeChinese = false,
            romanizeHindi = false,
            romanizeOther = false,
        )

    private fun aiSettings(excludedLanguages: Set<String> = emptySet()): AiRomanizationSettings =
        AiRomanizationSettings(
            enabled = true,
            auto = true,
            excludedLanguages = excludedLanguages,
            config = AiServiceSettings(
                provider = AiProvider.CHATGPT,
                apiKey = "test-key",
                customEndpoint = "",
                model = "test-model",
            ),
        )

    private companion object {
        const val SESSION_KEY = "track-1|session"
    }
}
