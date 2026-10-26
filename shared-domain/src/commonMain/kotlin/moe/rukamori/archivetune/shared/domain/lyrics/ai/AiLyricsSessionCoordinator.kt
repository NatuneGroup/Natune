package moe.rukamori.archivetune.shared.domain.lyrics.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsDetection
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsParser
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsSentinels
import moe.rukamori.archivetune.shared.domain.lyrics.document.LyricsDocument
import moe.rukamori.archivetune.shared.domain.lyrics.language.LyricsLanguagePolicy

/**
 * One session owner per concern (translation or romanization), shared by automatic and menu requests.
 * Hosts supply M7 service function references; rate limiting remains in that service, not here.
 */
class AiLyricsSessionCoordinator(
    private val scope: CoroutineScope,
    private val identitySalt: String,
    private val translate: suspend (AiServiceSettings, String, List<String>, String) -> List<String>,
    private val romanize: suspend (AiServiceSettings, List<String>) -> List<String?>,
    private val reportFailure: (AiLyricsOperation) -> Unit = {},
    private val cacheCapacity: Int = 8,
) {
    private val lock = Mutex()
    private var settings: AiLyricsSessionSettings? = null
    private var generation = 0L
    private var job: Job? = null
    private val cache = linkedMapOf<CacheKey, AiLyricsSessionResult>()
    private val mutableState = MutableStateFlow(AiLyricsSessionState())
    val state = mutableState.asStateFlow()

    init {
        require(cacheCapacity > 0)
        require(identitySalt.isNotEmpty())
    }

    /** Invoke from the actual settings observer, even when no menu/renderer is currently composed. */
    suspend fun updateSettings(value: AiLyricsSessionSettings) = lock.withLock {
        updateSettingsLocked(value)
    }

    /** Track/text/target navigation invalidates an outstanding job even without a replacement request. */
    suspend fun select(request: AiLyricsSessionRequest?) = lock.withLock {
        if (!sameTarget(mutableState.value.request, request)) {
            invalidateLocked()
            mutableState.value = AiLyricsSessionState(request = request)
        }
    }

    suspend fun cancel() = lock.withLock {
        invalidateLocked()
        mutableState.value = mutableState.value.copy(running = false, result = null, failure = null)
    }

    /** A delayed persistence failure must not cancel the next track's request. */
    suspend fun cancelResult(result: AiLyricsSessionResult): Boolean = lock.withLock {
        if (mutableState.value.result !== result) return@withLock false
        invalidateLocked()
        mutableState.value = mutableState.value.copy(running = false, result = null, failure = null)
        true
    }

    /** Returns the shared in-flight job; a rejection/cache hit requires no transport job. */
    suspend fun submit(request: AiLyricsSessionRequest, value: AiLyricsSessionSettings): Job? = lock.withLock {
        updateSettingsLocked(value)
        if (sameTarget(mutableState.value.request, request) && job?.isActive == true) return@withLock job
        invalidateLocked()
        mutableState.value = AiLyricsSessionState(request = request)
        if (!eligible(request, value)) {
            mutableState.value = AiLyricsSessionState(request, failure = AiLyricsSessionFailure.INELIGIBLE)
            return@withLock null
        }
        val key = cacheKey(request, value)
        cache.remove(key)?.let { result ->
            cache[key] = result
            mutableState.value = AiLyricsSessionState(request, result = result.copy(request = request))
            return@withLock null
        }
        val capturedGeneration = generation
        mutableState.value = AiLyricsSessionState(request, running = true)
        scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = execute(request, value)
                currentCoroutineContext().ensureActive()
                lock.withLock {
                    if (capturedGeneration != generation) return@withLock
                    cache[key] = result
                    while (cache.size > cacheCapacity) cache.remove(cache.keys.first())
                    mutableState.value = AiLyricsSessionState(request, result = result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IncompleteLyricsResponse) {
                fail(capturedGeneration, request, AiLyricsSessionFailure.INCOMPLETE)
            } catch (_: Exception) {
                // Neither lyrics, credentials, endpoints nor exception bodies enter local diagnostics.
                fail(capturedGeneration, request, AiLyricsSessionFailure.PROVIDER)
            } finally {
                withContext(NonCancellable) {
                    lock.withLock {
                        if (capturedGeneration == generation) {
                            job = null
                            mutableState.value = mutableState.value.copy(running = false)
                        }
                    }
                }
            }
        }.also { job = it; it.start() }
    }

    private suspend fun fail(g: Long, request: AiLyricsSessionRequest, failure: AiLyricsSessionFailure) {
        lock.withLock {
            if (g != generation) return
            mutableState.value = AiLyricsSessionState(request, failure = failure)
            reportFailure(request.operation)
        }
    }

    private fun updateSettingsLocked(value: AiLyricsSessionSettings) {
        if (settings == value) return
        settings = value
        invalidateLocked()
        cache.clear()
        mutableState.value = AiLyricsSessionState()
    }

    private fun invalidateLocked() {
        generation++
        job?.cancel()
        job = null
    }

    private fun sameTarget(a: AiLyricsSessionRequest?, b: AiLyricsSessionRequest?): Boolean =
        a?.copy(automatic = false) == b?.copy(automatic = false)

    private fun eligible(request: AiLyricsSessionRequest, settings: AiLyricsSessionSettings): Boolean {
        if (request.lyrics.length > LyricsDocument.MAX_CHARS ||
            request.lyrics.isBlank() || request.lyrics == LyricsSentinels.NOT_FOUND) return false
        return when (request.operation) {
            AiLyricsOperation.TRANSLATE -> settings.translation.preflight(
                request.lyrics, request.targetLanguage, hints(request), request.automatic,
            ) == AiTranslationPreflight.Ready
            AiLyricsOperation.ROMANIZE -> {
                val lines = linesOf(request.lyrics)
                (!request.automatic || settings.romanization.auto) &&
                    AiRomanizationPolicy.isEligible(settings.romanization, lines, hints(request)) &&
                    AiRomanizationPolicy.needsRequest(lines)
            }
        }
    }

    private suspend fun execute(request: AiLyricsSessionRequest, settings: AiLyricsSessionSettings): AiLyricsSessionResult =
        when (request.operation) {
            AiLyricsOperation.TRANSLATE -> {
                val document = LyricsDocument.parse(request.lyrics)
                if (document.segments.isEmpty()) throw IncompleteLyricsResponse()
                val translated = mutableMapOf<Int, String>()
                for (range in AiSegmentBatchPolicy.chunkByBudget(document.segments.map { it.text.length })) {
                    currentCoroutineContext().ensureActive()
                    val batch = range.map { document.segments[it] }
                    val response = translate(
                        settings.translation.config, AiTargetLanguage.canonical(request.targetLanguage),
                        batch.map { it.text }, document.formatName,
                    )
                    if (AiSegmentBatchPolicy.validate(batch.size, response) !is AiSegmentBatchCheck.Accepted) {
                        throw IncompleteLyricsResponse()
                    }
                    batch.forEachIndexed { index, segment -> translated[segment.id] = response[index] }
                }
                AiLyricsSessionResult(request, translatedLyrics = document.rebuild(translated))
            }
            AiLyricsOperation.ROMANIZE -> {
                val lines = linesOf(request.lyrics)
                val response = mutableListOf<String?>()
                for (range in AiSegmentBatchPolicy.chunkByBudget(lines.map { it.length })) {
                    currentCoroutineContext().ensureActive()
                    val batch = romanize(settings.romanization.config, range.map { lines[it] })
                    if (batch.size != range.count()) throw IncompleteLyricsResponse()
                    response.addAll(batch)
                }
                val byLine = linkedMapOf<String, String>()
                lines.forEachIndexed { index, line ->
                    AiRomanizationPolicy.candidateLine(line, response[index])?.let {
                        if (line.trim() !in byLine) byLine[line.trim()] = it
                    }
                }
                if (!AiRomanizationPolicy.isPublishable(lines, byLine)) throw IncompleteLyricsResponse()
                AiLyricsSessionResult(request, romanizedByLine = byLine)
            }
        }

    private fun hints(request: AiLyricsSessionRequest): List<String> =
        request.sourceLanguageHints.ifEmpty { LyricsLanguagePolicy.sourceLanguageHints(request.lyrics) }

    private data class CacheKey(
        val mediaId: String,
        val lyrics: String,
        val operation: AiLyricsOperation,
        val target: String,
        val hints: List<String>,
        val configIdentity: String,
    )

    private fun cacheKey(request: AiLyricsSessionRequest, settings: AiLyricsSessionSettings): CacheKey = CacheKey(
        request.mediaId, request.lyrics, request.operation, AiTargetLanguage.canonical(request.targetLanguage),
        hints(request).toList(),
        (if (request.operation == AiLyricsOperation.TRANSLATE) settings.translation.config else settings.romanization.config)
            .cacheIdentity(identitySalt),
    )

    companion object {
        fun linesOf(lyrics: String): List<String> = when {
            lyrics == LyricsSentinels.NOT_FOUND -> emptyList()
            LyricsDetection.isTtml(lyrics) -> LyricsParser.parseTtml(lyrics).map { it.text }
            else -> LyricsParser.parse(lyrics).map { it.text }.ifEmpty {
                lyrics.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            }
        }
    }
}

private class IncompleteLyricsResponse : Exception()
