package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsDetection

/**
 * Orchestrates the lyrics providers with the same policy as the Android `LyricsHelper`.
 *
 * - [getLyricsWithProvider]: a word-synced sweep first when [prioritizeWordSynced] is on, then a
 *   parallel fetch of every enabled provider. The result ranks word-synced over line-synced over
 *   plain, and provider order breaks ties. It is not first-result-wins.
 * - [getAllLyrics]: streams every enabled provider's results through a callback as they arrive.
 * - Concurrent lookups for the same track share one fetch through [LyricsSingleFlight].
 *
 * [providers] must already be in the canonical order ([LyricsProviderOrder.CANONICAL_NAMES]). The
 * host applies any user-chosen order before constructing the helper. [isEnabled] is the host's
 * per-provider switch, and no preference key is read here.
 *
 * Each deliberate difference from the Android helper is noted at the code that makes it.
 */
class LyricsHelper(
    providers: List<LyricsProvider>,
    private val isEnabled: (providerName: String) -> Boolean,
    scope: CoroutineScope,
    /** The Android "Prioritize Word Synced Lyrics" toggle. Read on every lookup, so a toggle change applies at once. */
    private val prioritizeWordSynced: suspend () -> Boolean = { true },
    /** Mirrors the Android connectivity check. An exception from it counts as connected. */
    private val isNetworkAvailable: () -> Boolean = { true },
    /** Receives provider failures that Android reports through `reportException`. */
    private val reportProviderFailure: (providerName: String, error: Throwable) -> Unit = { _, _ -> },
    /** Availability is not a user toggle: a priority sweep may ignore switches, never a missing native provider. */
    private val isAvailable: (providerName: String) -> Boolean = { true },
) {
    private val providers: List<LyricsProvider> = providers.toList()
    private val calls = LyricsProviderCalls(reportProviderFailure)
    private val singleFlight = LyricsSingleFlight<LyricsFlightKey, LyricsResult>(scope)
    private val resultCache = LyricsBoundedCache<String, LyricsResult>(LyricsFetchPolicy.RESULT_CACHE_CAPACITY)
    private val allResultsCache =
        LyricsBoundedCache<String, List<LyricsResult>>(LyricsFetchPolicy.RESULT_CACHE_CAPACITY)

    /**
     * The stored row each media id last settled a word-synced upgrade against. Keyed by the row text
     * rather than the id alone, so a row that changes underneath us is eligible again.
     */
    private val wordSyncedUpgradeMemo =
        LyricsBoundedCache<String, String>(LyricsFetchPolicy.WORD_SYNCED_UPGRADE_MEMO_CAPACITY)

    /**
     * True when the caller should refetch because the word-synced toggle could still improve the
     * stored row, and this helper has not already settled that exact row.
     *
     * Asking does not consume the attempt. Call [noteWordSyncedUpgradeSettled] once the sweep has
     * reached a verdict, so a sweep that never returned is retried later.
     */
    suspend fun shouldAttemptWordSyncedUpgrade(
        mediaId: String,
        storedLyrics: String?,
        isRemoteSource: Boolean,
    ): Boolean {
        val prioritize = prioritizeWordSynced()
        if (!LyricsDetection.needsWordSyncedUpgrade(prioritize, isRemoteSource, storedLyrics)) return false
        val stored = storedLyrics ?: return false
        return wordSyncedUpgradeMemo.get(mediaId) != stored
    }

    suspend fun noteWordSyncedUpgradeSettled(
        mediaId: String,
        storedLyrics: String,
    ) {
        wordSyncedUpgradeMemo.put(mediaId, storedLyrics)
    }

    suspend fun getLyrics(
        request: LyricsRequest,
        preferredProviderOnly: Boolean = false,
        forceRefresh: Boolean = false,
    ): String = getLyricsWithProvider(request, preferredProviderOnly, forceRefresh).lyrics

    suspend fun getLyricsWithProvider(
        request: LyricsRequest,
        preferredProviderOnly: Boolean = false,
        forceRefresh: Boolean = false,
    ): LyricsResult {
        val key = request.trackKey
        // The word-synced toggle does not apply to the preferred-provider-only path, as on Android.
        val prioritize = !preferredProviderOnly && prioritizeWordSynced()

        if (forceRefresh) {
            invalidate(key)
        } else {
            cachedResult(key, request, prioritize)?.let { return it }
        }

        // Checked after the cache so a cached answer still works offline, as on Android.
        if (!networkAvailable()) return LyricsResult.NOT_FOUND

        return singleFlight.execute(LyricsFlightKey(key, preferredProviderOnly, prioritize)) {
            fetchAndCache(key, request, preferredProviderOnly, prioritize)
        }
    }

    /**
     * Streams every enabled provider's results through [callback] as they arrive. A cache hit replays
     * the stored list. A run with transient provider errors is not cached so it can be retried.
     *
     * Callbacks run one at a time on a single collector, so a host does not need its own lock. This
     * is stricter than Android, which called the callback from each provider's thread.
     */
    suspend fun getAllLyrics(
        request: LyricsRequest,
        forceRefresh: Boolean = false,
        callback: (LyricsResult) -> Unit,
    ) {
        val key = request.trackKey
        if (forceRefresh) {
            invalidate(key)
        } else {
            allResultsCache.get(key)?.let { cached ->
                if (cached.isEmpty()) return
                // A stored list from a provider the user has since switched off must not reappear in
                // manual search. Replay only the still-selectable providers; if none survive, the
                // lookup falls through to the current provider set.
                val selectable = cached.filter { isCachedProviderEnabled(it.providerName) }
                if (selectable.isNotEmpty()) {
                    selectable.forEach(callback)
                    return
                }
            }
        }

        if (!networkAvailable()) return

        val enabled = providers.filter { isEnabled(it.name) && isAvailable(it.name) }
        val run = streamAllProviders(enabled, request, callback)
        if (!run.hadTransientFailure) {
            allResultsCache.put(key, run.results)
        }
    }

    /** Drops the result caches. The word-synced upgrade memo is kept, as the Android helper keeps it. */
    suspend fun clearCache() {
        resultCache.clear()
        allResultsCache.clear()
    }

    private suspend fun cachedResult(
        key: String,
        request: LyricsRequest,
        prioritize: Boolean,
    ): LyricsResult? {
        // With the word-synced toggle on, a cached line-synced or plain answer is stale. Skip it so the
        // sweep gets another chance. Eligibility follows the same lookup mode as the fetch path: an
        // ordinary lookup requires the provider switch, while an available sweep provider's cached
        // word-synced answer stays reusable because the sweep itself ignores those switches. A cached
        // answer from a provider that is unavailable or cannot handle this media id is always stale.
        // The entry is skipped, not purged, so re-enabling the provider can reuse it when no other
        // provider answers.
        resultCache.get(key)?.let { cached ->
            if (isCachedProviderSelectable(cached.providerName, request, prioritize) &&
                (!prioritize || LyricsDetection.hasWordSyncedLyrics(cached.lyrics))
            ) {
                return cached
            }
        }
        allResultsCache.get(key)?.let { cached ->
            val first = cached.firstOrNull { isCachedProviderSelectable(it.providerName, request, prioritize) }
            if (first != null && (!prioritize || LyricsDetection.hasWordSyncedLyrics(first.lyrics))) return first
        }
        return null
    }

    private fun isCachedProviderSelectable(
        providerName: String,
        request: LyricsRequest,
        sweepActive: Boolean,
    ): Boolean {
        val provider = providers.firstOrNull { it.name == providerName } ?: return false
        return isLookupEligible(provider, request, sweepActive)
    }

    /**
     * One eligibility authority for the fetch path and its cache. [sweepActive] keeps the documented
     * word-sync exception: the three canonical sweep providers are queried regardless of their
     * fallback switches, so their answers must remain reusable in that lookup mode. Every other
     * mode accepts only providers the user has enabled. Availability and media-id capability always
     * apply.
     */
    private fun isLookupEligible(
        provider: LyricsProvider,
        request: LyricsRequest,
        sweepActive: Boolean,
    ): Boolean {
        if (!isAvailable(provider.name)) return false
        if (!LyricsMediaIdPolicy.supportsMediaId(provider, request.mediaId)) return false
        return (sweepActive && provider.name in LyricsProviderOrder.WORD_SYNC_NAMES) ||
            isEnabled(provider.name)
    }

    private fun isCachedProviderEnabled(providerName: String): Boolean {
        val provider = providers.firstOrNull { it.name == providerName } ?: return false
        return isEnabled(provider.name) && isAvailable(provider.name)
    }

    private suspend fun fetchAndCache(
        key: String,
        request: LyricsRequest,
        preferredProviderOnly: Boolean,
        prioritize: Boolean,
    ): LyricsResult {
        if (prioritize) {
            val wordSynced = sweepWordSynced(request)
            if (wordSynced != null && isMeaningful(wordSynced.lyrics)) {
                resultCache.put(key, wordSynced)
                return wordSynced
            }
        }

        val usable = providers.filter { isLookupEligible(it, request, sweepActive = false) }
        // Android takes the first of the user-ordered list. The host's list order plays that role here.
        val candidates = if (preferredProviderOnly) usable.take(1) else usable
        val result = fetchPriority(candidates, request)
        if (isMeaningful(result.lyrics)) resultCache.put(key, result)
        return result
    }

    /**
     * The word-synced sweep. It asks the three word-capable providers in parallel with the longer
     * timeout and returns the first word-synced answer in the fixed order. Anything else is ignored.
     */
    private suspend fun sweepWordSynced(request: LyricsRequest): LyricsResult? {
        val sweepProviders =
            LyricsProviderOrder.WORD_SYNC_NAMES
                .mapNotNull { name -> providers.firstOrNull { it.name == name } }
                .filter { isLookupEligible(it, request, sweepActive = true) }
        if (sweepProviders.isEmpty()) return null

        val answers =
            calls.fanOut(sweepProviders, LyricsFetchPolicy.WORD_SYNCED_TIMEOUT_MS) { provider ->
                calls.fetchUsable(provider, request)
            }
        val candidates = answers.map { (provider, lyrics) -> LyricsResult(provider.name, lyrics) }
        return LyricsRanking.firstWordSynced(candidates)
    }

    private suspend fun fetchPriority(
        candidates: List<LyricsProvider>,
        request: LyricsRequest,
    ): LyricsResult {
        if (candidates.isEmpty()) return LyricsResult.NOT_FOUND
        val answers =
            calls.fanOut(candidates, LyricsFetchPolicy.FAN_OUT_TIMEOUT_MS) { provider ->
                calls.fetchUsable(provider, request)
            }
        return LyricsRanking.rank(answers.map { (provider, lyrics) -> LyricsResult(provider.name, lyrics) })
    }

    private suspend fun streamAllProviders(
        enabled: List<LyricsProvider>,
        request: LyricsRequest,
        callback: (LyricsResult) -> Unit,
    ): StreamedLyricsRun {
        val channel = Channel<StreamedLyricsResult>(Channel.UNLIMITED)
        val collected = mutableListOf<LyricsResult>()
        var hadTransientFailure = false
        return coroutineScope {
            val collector =
                launch {
                    for (item in channel) {
                        item.result?.let { result ->
                            collected += result
                            callback(result)
                        }
                        hadTransientFailure = hadTransientFailure || item.isTransientFailure
                    }
                }
            try {
                supervisorScope {
                    enabled
                        .map { provider ->
                            async {
                                calls.streamAll(
                                    provider = provider,
                                    request = request,
                                    timeoutMs = LyricsFetchPolicy.FAN_OUT_TIMEOUT_MS,
                                    emit = { result -> channel.trySend(StreamedLyricsResult(result, false)) },
                                    onFailure = { channel.trySend(StreamedLyricsResult(null, true)) },
                                )
                            }
                        }.awaitAll()
                }
            } finally {
                channel.close()
            }
            collector.join()
            StreamedLyricsRun(
                results = collected.toList(),
                hadTransientFailure = hadTransientFailure,
            )
        }
    }

    private suspend fun invalidate(key: String) {
        resultCache.remove(key)
        allResultsCache.remove(key)
    }

    private fun networkAvailable(): Boolean = runCatching { isNetworkAvailable() }.getOrDefault(true)

    private fun isMeaningful(lyrics: String): Boolean = LyricsDetection.hasMeaningfulContent(lyrics)
}

private data class LyricsFlightKey(
    val trackKey: String,
    val preferredProviderOnly: Boolean,
    val prioritizeWordSynced: Boolean,
)

private data class StreamedLyricsResult(val result: LyricsResult?, val isTransientFailure: Boolean)

private data class StreamedLyricsRun(val results: List<LyricsResult>, val hadTransientFailure: Boolean)
