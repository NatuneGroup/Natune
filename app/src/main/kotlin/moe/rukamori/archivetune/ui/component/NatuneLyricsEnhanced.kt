package moe.rukamori.archivetune.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.betterlyrics.QRCParser
import moe.rukamori.archivetune.betterlyrics.TTMLParser
import moe.rukamori.archivetune.constants.LyricsClickKey
import moe.rukamori.archivetune.constants.LyricsLineBlurKey
import moe.rukamori.archivetune.constants.LyricsRomanizeChineseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeHindiKey
import moe.rukamori.archivetune.constants.LyricsRomanizeJapaneseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeKoreanKey
import moe.rukamori.archivetune.constants.LyricsRomanizeOtherLanguagesKey
import moe.rukamori.archivetune.constants.LyricsScrollKey
import moe.rukamori.archivetune.constants.LyricsTextSizeKey
import moe.rukamori.archivetune.constants.TranslateLyricsKey
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.lyrics.AiLyricsRomanization
import moe.rukamori.archivetune.lyrics.JapaneseLanguagePackManager
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsLineAlignment
import moe.rukamori.archivetune.lyrics.LyricsPhonetics
import moe.rukamori.archivetune.lyrics.LyricsRenderingPreferences
import moe.rukamori.archivetune.lyrics.LyricsRomanizationPreferences
import moe.rukamori.archivetune.lyrics.LyricsSourceFormat
import moe.rukamori.archivetune.lyrics.LyricsSyncType
import moe.rukamori.archivetune.lyrics.LyricsTextDirection
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.lyrics.PreparedLyrics
import moe.rukamori.archivetune.lyrics.PreparedLyricsLine
import moe.rukamori.archivetune.lyrics.PreparedLyricsPhonetic
import moe.rukamori.archivetune.lyrics.PreparedLyricsTrack
import moe.rukamori.archivetune.lyrics.PreparedLyricsWord
import moe.rukamori.archivetune.lyrics.WordTimestamp
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.viewmodels.LyricsRenderError
import moe.rukamori.archivetune.viewmodels.LyricsRenderScreenState
import kotlin.math.roundToLong

/** Supplies Natune's provider, parser and romanization results to the upstream renderer. */
@Composable
fun NatuneLyricsEnhanced(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    modifier: Modifier = Modifier,
    textColorOverride: Color? = null,
    lyricsLineBlurOverride: Boolean? = null,
    textSizeOverride: Float? = null,
) {
    val state = rememberNatuneEnhancedLyricsState(textSizeOverride)
    LyricsEnhanced(
        lyricsState = state,
        sliderPositionProvider = sliderPositionProvider,
        lyricsSyncOffset = lyricsSyncOffset,
        modifier = modifier,
        textColorOverride = textColorOverride,
        lyricsLineBlurOverride = lyricsLineBlurOverride,
    )
}

@Composable
private fun rememberNatuneEnhancedLyricsState(textSizeOverride: Float?): LyricsRenderScreenState {
    val connection = LocalPlayerConnection.current ?: return LyricsRenderScreenState.Loading
    val metadata by connection.mediaMetadata.collectAsStateWithLifecycle()
    val currentLyrics by connection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val source = currentLyrics?.takeIf { it.id == metadata?.id }?.lyrics
    val clickEnabled by rememberPreference(LyricsClickKey, defaultValue = true)
    val scrollEnabled by rememberPreference(LyricsScrollKey, defaultValue = true)
    val textSize by rememberPreference(LyricsTextSizeKey, defaultValue = 26f)
    val lineBlurEnabled by rememberPreference(LyricsLineBlurKey, defaultValue = false)
    val showTranslations by rememberPreference(TranslateLyricsKey, defaultValue = true)
    val romanizeChinese by rememberPreference(LyricsRomanizeChineseKey, defaultValue = true)
    val romanizeHindi by rememberPreference(LyricsRomanizeHindiKey, defaultValue = true)
    val romanizeJapanese by rememberPreference(LyricsRomanizeJapaneseKey, defaultValue = false)
    val romanizeKorean by rememberPreference(LyricsRomanizeKoreanKey, defaultValue = true)
    val romanizeOther by rememberPreference(LyricsRomanizeOtherLanguagesKey, defaultValue = true)
    val aiSettings = AiLyricsRomanization.rememberSettings()
    val japanesePackState by JapaneseLanguagePackManager.state.collectAsStateWithLifecycle()
    var parsedState by remember(source) { mutableStateOf<EnhancedParsedLyrics?>(null) }
    var parseFailed by remember(source) { mutableStateOf(false) }

    LaunchedEffect(source) {
        val text = source ?: return@LaunchedEffect
        try {
            parsedState = withContext(Dispatchers.Default) { parseEnhancedLyrics(text) }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            reportException(exception)
            parseFailed = true
        }
    }

    val entries = parsedState?.entries.orEmpty()
    val languageHints = remember(source, entries) { LyricsUtils.sourceLanguageHints(source, entries) }
    val aiHandled = remember(aiSettings, entries, languageHints) {
        AiLyricsRomanization.isEligible(aiSettings, entries.map { it.text }, languageHints)
    }
    val romanizationPreferences = remember(
        romanizeJapanese, romanizeKorean, romanizeChinese, romanizeHindi, romanizeOther, aiHandled,
    ) {
        LyricsRomanizationPreferences(
            romanizeJapanese = romanizeJapanese,
            romanizeKorean = romanizeKorean,
            romanizeChinese = romanizeChinese,
            romanizeHindi = romanizeHindi,
            romanizeOther = romanizeOther,
            aiHandled = aiHandled,
        )
    }
    val aiSessionKey = remember(metadata?.id, source, languageHints) {
        AiLyricsRomanization.sessionKey(metadata?.id, source, languageHints)
    }
    val aiResult by AiLyricsRomanization.results.collectAsStateWithLifecycle()
    val aiLines = remember(aiResult, aiSessionKey, aiSettings, entries) {
        AiLyricsRomanization.linesFor(aiSessionKey, entries.map { it.text }, languageHints)
    }
    LaunchedEffect(aiSessionKey, entries, aiSettings) {
        if (aiHandled && aiSettings.auto && entries.isNotEmpty()) {
            AiLyricsRomanization.request(aiSessionKey, entries.map { it.text }, aiSettings, languageHints)
        }
    }
    var phonetics by remember(entries, romanizationPreferences, aiLines, japanesePackState) {
        mutableStateOf<Map<Int, LyricsPhonetics>>(emptyMap())
    }
    LaunchedEffect(entries, romanizationPreferences, aiLines, japanesePackState) {
        val context = entries.joinToString("\n") { it.text }
        phonetics = withContext(Dispatchers.Default) {
            entries.mapIndexed { index, entry ->
                async {
                    val reading = if (aiHandled) {
                        LyricsPhonetics(line = aiLines.getOrNull(index))
                    } else {
                        try {
                            LyricsUtils.phoneticsForEntry(entry, romanizationPreferences, context)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            reportException(exception)
                            LyricsPhonetics()
                        }
                    }
                    index to reading
                }
            }.awaitAll().toMap()
        }
    }
    val preferences = LyricsRenderingPreferences(
        clickEnabled = clickEnabled,
        scrollEnabled = scrollEnabled,
        textSizeSp = textSizeOverride ?: textSize,
        lineSpacing = 1.3f,
        lineBlurEnabled = lineBlurEnabled,
        v2BounceFactor = 1f,
        v2GlowFactor = 1f,
        v2FillTransitionWidthDp = 8f,
        v2LrcBounceEnabled = true,
        romanization = romanizationPreferences,
        showTranslations = showTranslations,
    )
    return remember(parsedState, parseFailed, preferences, phonetics) {
        when {
            parseFailed -> LyricsRenderScreenState.Error(LyricsRenderError.INVALID_LYRICS)
            parsedState == null -> LyricsRenderScreenState.Loading
            entries.isEmpty() -> LyricsRenderScreenState.Empty
            else -> LyricsRenderScreenState.Success(prepareEnhancedLyrics(parsedState!!, preferences, phonetics))
        }
    }
}

private data class EnhancedParsedLyrics(
    val entries: List<LyricsEntry>,
    val sourceFormat: LyricsSourceFormat,
    val syncType: LyricsSyncType,
)

private fun parseEnhancedLyrics(source: String): EnhancedParsedLyrics {
    val format = when {
        LyricsUtils.isTtml(source) -> LyricsSourceFormat.TTML
        QRCParser.isQrc(source) -> LyricsSourceFormat.QRC
        LyricsUtils.isLineSyncedLrc(source) -> LyricsSourceFormat.LRC
        else -> LyricsSourceFormat.PLAIN
    }
    val entries = when {
        source.isBlank() || source == LYRICS_NOT_FOUND -> emptyList()
        format == LyricsSourceFormat.TTML ->
            TTMLParser.parseTTML(LyricsUtils.normalizeLyricsText(source)).map { line ->
                LyricsEntry(
                    time = (line.startTime * 1000.0).toLong(),
                    text = line.text,
                    words = line.words.filter { it.text.isNotEmpty() }.map { word ->
                        WordTimestamp(word.text, word.startTime, word.endTime, word.isBackground)
                    }.takeIf { it.isNotEmpty() },
                    agent = line.agent,
                    durationMs = ((line.endTime - line.startTime) * 1000.0).toLong().coerceAtLeast(0L),
                    providerRomanizedText = line.providerRomanizedText,
                    providerRomanizedWords = line.providerRomanizedWords,
                    providerRomanizedLanguage = line.providerRomanizedLanguage,
                    providerTranslationText = line.providerTranslationText,
                )
            }.sorted()
        format != LyricsSourceFormat.PLAIN -> LyricsUtils.parseLyrics(source)
        else -> source.lineSequence().map(String::trim).filter(String::isNotEmpty)
            .map { LyricsEntry(time = -1L, text = it) }.toList()
    }
    val sync = when {
        format == LyricsSourceFormat.PLAIN -> LyricsSyncType.PLAIN
        entries.any { !it.words.isNullOrEmpty() } -> LyricsSyncType.WORD
        else -> LyricsSyncType.LINE
    }
    return EnhancedParsedLyrics(entries, format, sync)
}

private fun prepareEnhancedLyrics(
    parsed: EnhancedParsedLyrics,
    preferences: LyricsRenderingPreferences,
    readings: Map<Int, LyricsPhonetics>,
): PreparedLyrics {
    val lines = parsed.entries.mapIndexed { index, entry ->
        val words = entry.words.orEmpty().filterNot(WordTimestamp::isBackground).map { it.toPreparedWord() }
        val backgrounds = entry.words.orEmpty().filter(WordTimestamp::isBackground).map { it.toPreparedWord() }
        val end = when {
            entry.time < 0L -> -1L
            entry.durationMs > 0L -> entry.time + entry.durationMs
            words.isNotEmpty() -> words.maxOf(PreparedLyricsWord::endMs)
            else -> parsed.entries.getOrNull(index + 1)?.time?.minus(1L) ?: entry.time + 4000L
        }
        val reading = readings[index]
        PreparedLyricsLine(
            id = "${parsed.sourceFormat}:$index:${entry.time}:${entry.text.hashCode()}",
            startMs = entry.time,
            endMs = if (entry.time < 0L) end else end.coerceAtLeast(entry.time + 1L),
            text = entry.text,
            alignment = when (entry.agent?.lowercase()) {
                null, "v1" -> LyricsLineAlignment.START
                "v2" -> LyricsLineAlignment.END
                else -> LyricsLineAlignment.CENTER
            },
            direction = if (LyricsUtils.isRtlText(entry.text)) LyricsTextDirection.RTL else LyricsTextDirection.LTR,
            main = PreparedLyricsTrack(entry.text, null, ImmutableList.copyOf(words)),
            backgrounds = ImmutableList.copyOf(
                if (backgrounds.isEmpty()) emptyList() else listOf(
                    PreparedLyricsTrack(
                        backgrounds.joinToString("") { it.text }, null, ImmutableList.copyOf(backgrounds),
                    ),
                ),
            ),
            translation = LyricsUtils.providedTranslationTextForEntry(entry),
            romanizedText = reading?.line,
            phonetics = ImmutableList.copyOf(reading?.words.orEmpty().mapIndexedNotNull { wordIndex, text ->
                text?.takeIf(String::isNotBlank)?.let { PreparedLyricsPhonetic(wordIndex, it) }
            }),
            isInstrumental = entry.isInstrumental,
        )
    }
    return PreparedLyrics(parsed.sourceFormat, parsed.syncType, ImmutableList.copyOf(lines), preferences)
}

private fun WordTimestamp.toPreparedWord(): PreparedLyricsWord {
    val start = (startTime * 1000.0).roundToLong().coerceAtLeast(0L)
    return PreparedLyricsWord(text, start, (endTime * 1000.0).roundToLong().coerceAtLeast(start + 1L))
}
