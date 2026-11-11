package moe.rukamori.archivetune.shared.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.rukamori.archivetune.shared.core.bindings.CoreException
import moe.rukamori.archivetune.shared.core.bindings.ParseCancellation
import moe.rukamori.archivetune.shared.core.bindings.coreAvailable
import moe.rukamori.archivetune.shared.core.bindings.parseTtml
import moe.rukamori.archivetune.shared.domain.lyrics.KaraokeLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsCredits
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsNormalizer
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsWord
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import moe.rukamori.archivetune.shared.domain.lyrics.TtmlDocument
import moe.rukamori.archivetune.shared.domain.lyrics.TtmlDocumentReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Probe before parsing. XML refusal is a successful empty result and never retries in Kotlin. */
fun rustTtmlReaderOrNull(): TtmlDocumentReader? =
    try { if (coreAvailable()) RustTtmlReader else null }
    catch (_: Throwable) { null }

private object RustTtmlReader : TtmlDocumentReader {
    private val workers = CoroutineScope(Dispatchers.Default.limitedParallelism(2))

    override suspend fun readTtml(raw: String): TtmlDocument = suspendCancellableCoroutine { continuation ->
        val token = ParseCancellation()
        // Cancelling the Kotlin worker cannot interrupt a C call; the native scanner checks this token.
        continuation.invokeOnCancellation { runCatching { token.cancel() } }
        workers.launch {
            try {
                if (!continuation.isActive) return@launch
                val record = parseTtml(LyricsNormalizer.normalize(raw), token)
                val lines = record.lines.map { line ->
                    val words = line.words.map { LyricsWord(it.text, it.startTime, it.endTime, it.isBackground) }
                    if (words.isEmpty()) SyncedLine(
                        time = line.timeMs, text = line.text, agent = line.agent,
                        providerRomanizedText = line.providerRomanizedText, providerRomanizedWords = line.providerRomanizedWords,
                        providerRomanizedLanguage = line.providerRomanizedLanguage, providerTranslationText = line.providerTranslationText,
                    ) else KaraokeLine(
                        time = line.timeMs, text = line.text, agent = line.agent, words = words,
                        providerRomanizedText = line.providerRomanizedText, providerRomanizedWords = line.providerRomanizedWords,
                        providerRomanizedLanguage = line.providerRomanizedLanguage, providerTranslationText = line.providerTranslationText,
                    )
                }
                continuation.resume(TtmlDocument(lines, LyricsCredits(record.songwriters, record.translationAutoCreated, record.transliterationAutoCreated)))
            } catch (_: CoreException.Cancelled) {
                continuation.cancel(CancellationException("TTML parse cancelled"))
            } catch (failure: Throwable) {
                continuation.resumeWithException(failure)
            } finally { token.destroy() }
        }
    }
}
