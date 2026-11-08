package moe.rukamori.archivetune.ui.component

// SPDX-License-Identifier: GPL-3.0-only
// Portions © Rukamori — github.com/rukamori
// Portions © vossgraves — github.com/vossgraves

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.constants.LyricsRomanizeChineseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeHindiKey
import moe.rukamori.archivetune.constants.LyricsRomanizeJapaneseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeKoreanKey
import moe.rukamori.archivetune.constants.LyricsRomanizeOtherLanguagesKey
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.lyrics.JapaneseLanguagePackManager
import moe.rukamori.archivetune.lyrics.AiLyricsRomanization
import moe.rukamori.archivetune.lyrics.LyricsPhonetics
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsRomanizationPreferences
import moe.rukamori.archivetune.lyrics.LyricsUtils
import moe.rukamori.archivetune.lyrics.LyricsUtils.isLineSyncedLrc
import moe.rukamori.archivetune.lyrics.LyricsUtils.isTtml
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseLyrics
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseTtml
import moe.rukamori.archivetune.lyrics.LyricsUtils.phoneticsForEntry
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.reportException
import kotlin.coroutines.cancellation.CancellationException

/** One published [SyncedLyrics] plus the identity of the romanisation baked into it. */
private data class KaraokeBuild(
    val lyrics: SyncedLyrics,
    val romanization: Map<Int, LyricsPhonetics>,
    val generation: Int,
)

/**
 * The part of a romanisation map that changes what is drawn: entries whose every value is
 * null/blank produce no phonetics at all, so a map full of them is indistinguishable from an empty
 * one and must not count as a change (otherwise a song with nothing romanisable would re-key the
 * karaoke view for a build that looks identical).
 */
private fun Map<Int, LyricsPhonetics>.renderedRomanization(): Map<Int, LyricsPhonetics> =
    filterValues { value -> !value.line.isNullOrBlank() || value.words.orEmpty().any { !it.isNullOrBlank() } }

private const val ROMANIZATION_FIRST_BUILD_GRACE_MS = 700L

internal data class AlternateLyricsData(
    val entries: List<LyricsEntry>?,
    val lyrics: SyncedLyrics,
    val generation: Int,
    val romanizationPreferences: LyricsRomanizationPreferences,
)

@Composable
internal fun rememberAlternateLyricsData(lyrics: String?, mediaId: String?): AlternateLyricsData {
    val isTtmlFormat = remember(lyrics) { lyrics != null && isTtml(lyrics!!) }
    val (romanizeChinese) = rememberPreference(LyricsRomanizeChineseKey, defaultValue = true)
    val (romanizeHindi) = rememberPreference(LyricsRomanizeHindiKey, defaultValue = true)
    val (romanizeJapanese) = rememberPreference(LyricsRomanizeJapaneseKey, defaultValue = false)
    val (romanizeKorean) = rememberPreference(LyricsRomanizeKoreanKey, defaultValue = true)
    val (romanizeOtherLanguages) = rememberPreference(LyricsRomanizeOtherLanguagesKey, defaultValue = true)

    val aiRomanizationSettings = AiLyricsRomanization.rememberSettings()
    val japanesePackState by JapaneseLanguagePackManager.state.collectAsStateWithLifecycle()
    val japanesePackReady = japanesePackState is moe.rukamori.archivetune.lyrics.JapaneseLanguagePackState.Installed



    // Parsed off the composition thread. `null` means "not parsed yet" — the render `when` below
    // keeps the shimmer up on that state instead of falling through to "lyrics not found".
    //
    // The parse must stay off the composition thread: for word-synced TTML it is an XML parse
    // plus one object per syllable, twice (buildSyncedLyrics walks the result again). On the
    // composition thread that lands on the exact frame the Apple Music COVER->LYRICS morph starts
    // and stutters the switch to the lyrics page. Both run on Dispatchers.Default, the dispatcher
    // the romanization pass below already uses.
    var parsedEntries by remember(lyrics) { mutableStateOf<List<LyricsEntry>?>(null) }
    LaunchedEffect(lyrics) {
        val text = lyrics
        if (text == null || text == LYRICS_NOT_FOUND) {
            parsedEntries = emptyList()
            return@LaunchedEffect
        }
        parsedEntries =
            withContext(Dispatchers.Default) {
                when {
                    isTtml(text) -> parseTtml(text)
                    isLineSyncedLrc(text) -> parseLyrics(text)
                    else ->
                        text
                            .lines()
                            .filter { it.isNotBlank() }
                            .map { line -> LyricsEntry(time = -1L, text = line.trim()) }
                }
            }
    }
    val lyricsEntries: List<LyricsEntry> = parsedEntries.orEmpty()
    val sourceLanguageHints = remember(lyrics, lyricsEntries) {
        LyricsUtils.sourceLanguageHints(lyrics, lyricsEntries)
    }
    val aiHandlesLyrics = remember(aiRomanizationSettings, lyricsEntries, sourceLanguageHints) {
        AiLyricsRomanization.isEligible(aiRomanizationSettings, lyricsEntries.map { it.text }, sourceLanguageHints)
    }
    val romanizationPreferences =
        remember(
            romanizeJapanese,
            romanizeKorean,
            romanizeChinese,
            romanizeHindi,
            romanizeOtherLanguages,
            aiHandlesLyrics,
        ) {
            LyricsRomanizationPreferences(
                romanizeJapanese = romanizeJapanese,
                romanizeKorean = romanizeKorean,
                romanizeChinese = romanizeChinese,
                romanizeHindi = romanizeHindi,
                romanizeOther = romanizeOtherLanguages,
                aiHandled = aiHandlesLyrics,
            )
        }

    // Keyed on the raw lyrics text, not on lyricsEntries: the entries now arrive a beat after the
    // first composition, and re-keying on them would put a synchronous buildSyncedLyrics straight
    // back on the main thread the moment the parse lands. The effect below publishes the real
    // build from Default, so this only ever supplies the empty starting value.
    var karaokeBuild by remember(lyrics, isTtmlFormat) {
        mutableStateOf(KaraokeBuild(SyncedLyrics(emptyList()), emptyMap(), generation = 0))
    }
    val syncedLyrics = karaokeBuild.lyrics
    // Bumped alongside the lyrics object whenever a build replaces an already-visible one with
    // different romanisation; see KaraokeBuild for why the karaoke view has to be re-keyed on it.
    val karaokeGeneration = karaokeBuild.generation

    // Runs once per track instead of once per line (network + billed), so it can't hang off the
    // per-line pass below. Results arrive asynchronously through AiLyricsRomanization.results and are
    // folded into the same romanizationMap the built-in engines feed, which means everything
    // downstream — the wrapping-unit distribution, the karaoke phonetics, `showPhonetic` — is shared.
    val aiRomanizationSessionKey =
        remember(mediaId, lyrics, sourceLanguageHints) { AiLyricsRomanization.sessionKey(mediaId, lyrics, sourceLanguageHints) }
    val aiRomanizationResult by AiLyricsRomanization.results.collectAsStateWithLifecycle()
    // Resolved by line text, not by index — this renderer parses without the head entry and without
    // instrumental breaks, LyricsV2 parses with both, and both derive the same session key. See
    // AiLyricsRomanization.Result.
    val aiRomanizedLines: List<String?> =
        remember(aiRomanizationResult, aiRomanizationSessionKey, aiRomanizationSettings, lyricsEntries) {
            AiLyricsRomanization.linesFor(aiRomanizationSessionKey, lyricsEntries.map { it.text }, sourceLanguageHints)
        }
    LaunchedEffect(aiRomanizationSessionKey, lyricsEntries, aiRomanizationSettings) {
        if (!aiHandlesLyrics || !aiRomanizationSettings.auto) return@LaunchedEffect
        if (lyricsEntries.isEmpty()) return@LaunchedEffect
        AiLyricsRomanization.request(
            sessionKey = aiRomanizationSessionKey,
            lines = lyricsEntries.map { it.text },
            settings = aiRomanizationSettings,
            sourceLanguageHints = sourceLanguageHints,
        )
    }

    val romanizationContext = remember(lyricsEntries) { lyricsEntries.joinToString("\n") { it.text } }

    LaunchedEffect(lyricsEntries, romanizationPreferences, aiRomanizedLines, japanesePackReady) {
        // Everything below (scanning + romanizing every line, often one job per word for TTML)
        // must not inherit the main dispatcher — it stutters the karaoke animation on track
        // change; run the whole batch on Default and only publish the results back.
        withContext(Dispatchers.Default) {
        // Publishing new lyrics as state (instead of bumping a key that tears the whole karaoke
        // subtree down mid-playback) lets the list pick up a *translation* without a re-layout
        // hitch. Romanisation is different: a build that changes the romanisation of lines already
        // on screen carries a new generation, which re-keys the list and places its lines again
        // instead of reflowing them under the reader. See KaraokeBuild.
        fun publish(romanization: Map<Int, LyricsPhonetics>) {
            val previous = karaokeBuild
            val changesVisibleLines =
                previous.lyrics.lines.isNotEmpty() &&
                    romanization.renderedRomanization() != previous.romanization.renderedRomanization()
            karaokeBuild =
                KaraokeBuild(
                    lyrics = buildSyncedLyrics(lyricsEntries, romanization),
                    romanization = romanization,
                    generation = if (changesVisibleLines) previous.generation + 1 else previous.generation,
                )
        }

        val aiMap = aiRomanizationMap(lyricsEntries, aiRomanizedLines)

        val toRomanize: List<Pair<Int, LyricsEntry>> =
            if (!romanizationPreferences.isEnabled) {
                // Romanisation is off, or the AI engine owns it — `aiMap` is already everything
                // this build will ever have.
                emptyList()
            } else {
                lyricsEntries.mapIndexed { index, entry -> index to entry }
            }
        if (toRomanize.isEmpty()) {
            publish(aiMap)
            return@withContext
        }

        val romanization =
            async {
                val jobs =
                    toRomanize.map { (index, entry) ->
                        async {
                            val romanized =
                                try {
                                    phoneticsForEntry(entry, romanizationPreferences, romanizationContext)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    reportException(e)
                                    LyricsPhonetics()
                                }
                            index to romanized
                        }
                    }
                jobs.awaitAll().toMap()
            }

        // Why the un-romanised build is not published up front: KaraokeBuild describes the case of
        // a second build landing while lines are already on screen — those lines then keep their
        // phonetic-less layout for good. Waiting a beat for the local pass means the first build
        // the view ever sees already has the phonetics, so nothing has to be replaced at all —
        // and the shimmer is already up, so the wait costs no visible state.
        //
        // The timeout is what keeps that from becoming a stall: it does not cancel the pass (the
        // deferred belongs to the enclosing scope, not to withTimeoutOrNull), it only stops waiting
        // on it, and the two-build path below is then taken with the re-key that makes it correct.
        val quickRomanization = withTimeoutOrNull(ROMANIZATION_FIRST_BUILD_GRACE_MS) { romanization.await() }
        if (quickRomanization != null) {
            publish(quickRomanization)
            return@withContext
        }
        publish(aiMap)
        publish(romanization.await())
        }
    }

    return AlternateLyricsData(parsedEntries, syncedLyrics, karaokeGeneration, romanizationPreferences)
}

/**
 * AI supplies line readings, not timed-word alignment.
 */
private fun aiRomanizationMap(
    entries: List<LyricsEntry>,
    aiLines: List<String?>,
): Map<Int, LyricsPhonetics> {
    if (aiLines.isEmpty() || entries.isEmpty()) return emptyMap()
    val map = mutableMapOf<Int, LyricsPhonetics>()
    entries.forEachIndexed { index, _ ->
        val romanized = aiLines.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
        map[index] = LyricsPhonetics(line = romanized)
    }
    return map
}
