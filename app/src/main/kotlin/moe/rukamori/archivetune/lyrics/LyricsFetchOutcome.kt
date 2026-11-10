package moe.rukamori.archivetune.lyrics

import androidx.annotation.StringRes
import io.ktor.client.plugins.ResponseException
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** What one provider said about a track. */
internal enum class ProviderLyricsAnswer { FOUND, MISSING, FAILED }

internal data class ProviderLyricsFetch(
    val answer: ProviderLyricsAnswer,
    val lyrics: String? = null,
) {
    companion object {
        val Missing = ProviderLyricsFetch(ProviderLyricsAnswer.MISSING)
        val Failed = ProviderLyricsFetch(ProviderLyricsAnswer.FAILED)

        fun found(lyrics: String) = ProviderLyricsFetch(ProviderLyricsAnswer.FOUND, lyrics)
    }
}

/** Successful siblings may still stream to the UI when the search cannot be cached. */
internal class LyricsSearchResults {
    private val results = mutableListOf<LyricsResult>()
    private val hadProviderFailure = AtomicBoolean(false)

    fun add(result: LyricsResult) {
        synchronized(results) { results += result }
    }

    fun failed() {
        hadProviderFailure.set(true)
    }

    fun cacheableResults(): List<LyricsResult>? = synchronized(results) {
        results.takeIf { it.isNotEmpty() && !hadProviderFailure.get() }?.toList()
    }
}

private const val MAX_CAUSE_DEPTH = 8

/**
 * A provider that could not be reached says nothing about whether it has the track. An
 * [IOException] anywhere in the cause chain (offline, DNS, timeout, reset) or a cancelled request
 * is a failed lookup, as is a retryable HTTP response; other errors count as a miss.
 */
internal fun Throwable.toProviderLyricsAnswer(): ProviderLyricsAnswer =
    if (
        generateSequence(this) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .any {
                it is IOException || it is CancellationException ||
                    (it is ResponseException &&
                        (it.response.status.value == 429 || it.response.status.value in 500..599))
            }
    ) {
        ProviderLyricsAnswer.FAILED
    } else {
        ProviderLyricsAnswer.MISSING
    }

/**
 * A miss is only worth storing when at least one provider actually answered; otherwise the stored
 * "not found" would stand in for lyrics a provider may well have once it is reachable again.
 */
internal fun isConclusiveMiss(answers: Iterable<ProviderLyricsAnswer>): Boolean =
    answers.any { it != ProviderLyricsAnswer.FAILED }

enum class LyricsRefetchResult(
    @param:StringRes val messageResId: Int?,
) {
    Updated(null),
    NotFound(R.string.lyrics_not_found),
    Unavailable(R.string.lyrics_refetch_failed),
}

fun LyricsResult.toRefetchResult(): LyricsRefetchResult =
    when {
        lyrics != LYRICS_NOT_FOUND -> LyricsRefetchResult.Updated
        isConclusive -> LyricsRefetchResult.NotFound
        else -> LyricsRefetchResult.Unavailable
    }
