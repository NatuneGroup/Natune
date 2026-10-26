package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsDetection
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsSentinels

/**
 * Every provider call the orchestrator makes, with the Android error policy in one place: a
 * provider failure is reported and dropped so its siblings keep running, while cancellation is
 * rethrown so the caller's cancellation still stops the group.
 *
 * Each call holds one process-wide permit, and the timeout starts only once the permit is held,
 * as on Android. A slow queue for permits therefore never counts against a provider.
 */
internal class LyricsProviderCalls(
    private val reportFailure: (providerName: String, error: Throwable) -> Unit,
) {
    /** The provider's usable lyrics, or null when it returned nothing meaningful or failed. */
    suspend fun fetchUsable(
        provider: LyricsProvider,
        request: LyricsRequest,
    ): String? {
        val response =
            suspendRunCatching {
                provider.getLyrics(
                    id = request.mediaId,
                    title = request.title,
                    artist = request.artist,
                    album = request.album,
                    durationSec = request.durationSec,
                    isrc = request.isrc,
                )
            }.getOrElse {
                reportFailure(provider.name, it)
                return null
            }
        return response.fold(
            onSuccess = { text ->
                LyricsDetection.lyricsOrNotFound(text).takeIf { it != LyricsSentinels.NOT_FOUND }
            },
            onFailure = {
                reportFailure(provider.name, it)
                null
            },
        )
    }

    /**
     * Runs [call] against every provider in parallel, each under the shared permit and [timeoutMs].
     * Results keep the input order. A null answer or a timeout drops that provider.
     *
     * The wait is for every provider, not the first answer, which is what the Android sweep does.
     */
    suspend fun <T : Any> fanOut(
        providers: List<LyricsProvider>,
        timeoutMs: Long,
        call: suspend (LyricsProvider) -> T?,
    ): List<Pair<LyricsProvider, T>> =
        supervisorScope {
            providers
                .map { provider ->
                    async {
                        val value =
                            LyricsFetchPolicy.providerPermits.withPermit {
                                withTimeoutOrNull(timeoutMs) { call(provider) }
                            }
                        value?.let { provider to it }
                    }
                }.awaitAll()
                .filterNotNull()
        }

    /**
     * Streams one provider's all-results callback. A failure is reported and dropped, while a
     * timeout is treated as silence, as on Android.
     */
    suspend fun streamAll(
        provider: LyricsProvider,
        request: LyricsRequest,
        timeoutMs: Long,
        emit: (LyricsResult) -> Unit,
    ) {
        suspendRunCatching {
            LyricsFetchPolicy.providerPermits.withPermit {
                withTimeoutOrNull(timeoutMs) {
                    provider.getAllLyrics(
                        id = request.mediaId,
                        title = request.title,
                        artist = request.artist,
                        album = request.album,
                        durationSec = request.durationSec,
                        isrc = request.isrc,
                    ) { lyrics ->
                        val normalized = LyricsDetection.lyricsOrNotFound(lyrics)
                        if (normalized != LyricsSentinels.NOT_FOUND) {
                            emit(LyricsResult(provider.name, normalized))
                        }
                    }
                }
            }
        }.onFailure { reportFailure(provider.name, it) }
    }
}
