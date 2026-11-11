/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import android.util.Log
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.LyricsProviderOrderKey
import moe.rukamori.archivetune.constants.PaxsenixApiKeyKey
import moe.rukamori.archivetune.constants.PaxsenixEndpointKey
import moe.rukamori.archivetune.constants.PreferredLyricsProvider
import moe.rukamori.archivetune.constants.PrioritizeWordSyncedLyricsKey
import moe.rukamori.archivetune.constants.deserializeLyricsProviderOrder
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.modules.ModuleLyricsProviders
import moe.rukamori.archivetune.modules.PreferLyricsExtensionsKey
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixDiagnostics
import moe.rukamori.archivetune.shared.data.lyrics.PaxsenixSettings
import moe.rukamori.archivetune.telegram.isTelegramMediaId
import moe.rukamori.archivetune.utils.GlobalLog
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.utils.NetworkConnectivityObserver
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.getAsync
import moe.rukamori.archivetune.utils.reportException
import javax.inject.Inject

class LyricsHelper
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val networkConnectivity: NetworkConnectivityObserver,
        private val moduleLyrics: ModuleLyricsProviders,
    ) {
        private val defaultProviders =
            listOf(
                LyprioLyricsProvider,
                LrcRedLyricsProvider,
                BiniLyricsProvider,
                BetterLyricsProvider,
                BetterLyricsPortatoProvider,
                YouLyPlusLyricsProvider,
                LrcLibLyricsProvider,
                KuGouLyricsProvider,
                MegalobizLyricsProvider,
                SimpMusicLyricsProvider,
                UnisonLyricsProvider,
                PaxsenixAppleMusicLyricsProvider,
                AppleMusicAccountLyricsProvider,
                TidalLyricsProvider,
                DeezerLyricsProvider,
                YouTubeSubtitleLyricsProvider,
                YouTubeLyricsProvider,

                MusixmatchExperimentalLyricsProvider,
                GeniusLyricsProvider,
            )

        internal var providerOverrideForTests: List<LyricsProvider>? = null
        internal var networkAvailableForTests: Boolean? = null
        private val baseProviders: List<LyricsProvider> get() = providerOverrideForTests ?: defaultProviders

        suspend fun checkPaxsenixEndpoints(): List<PaxsenixDiagnostics.PathCheck> {
            val preferences = context.dataStore.data.first()
            val client = HttpClient(OkHttp) {
                install(HttpTimeout) { requestTimeoutMillis = 15_000 }
            }
            return try {
                PaxsenixDiagnostics(
                    client,
                    PaxsenixSettings(
                        baseUrl = preferences[PaxsenixEndpointKey].orEmpty(),
                        bearerValue = preferences[PaxsenixApiKeyKey],
                    ),
                ).checkProviderPaths()
            } finally {
                client.close()
            }
        }

        private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
        private val singleLyricsCache = LruCache<String, LyricsResult>(MAX_CACHE_SIZE)
        private var extensionRevision: String? = null
        private val availableProviders get() = baseProviders + moduleLyrics.providers

        private suspend fun refreshExtensions() {
            val revision = try { moduleLyrics.snapshot() }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { reportException(error); return }
            synchronized(singleLyricsCache) {
                if (extensionRevision != revision) {
                    cache.evictAll()
                    singleLyricsCache.evictAll()
                    extensionRevision = revision
                }
            }
        }

        /**
         * The stored row text an upgrade attempt has already been made against, keyed by media id.
         *
         * Both fetch gates ask [shouldAttemptWordSyncedUpgrade] before sweeping the providers, and
         * without this memo the sweep would repeat on every play and every open of the lyrics panel
         * for a track the providers simply have no word-synced copy of — the same expensive miss,
         * over and over, on providers that rate-limit. Keyed by the stored text rather than the id
         * alone so a row that changes underneath us (a manual refetch, a provider backfill) is
         * eligible again.
         */
        private val wordSyncedUpgradeAttempts =
            object : LinkedHashMap<String, String>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
                    size > MAX_WORD_SYNCED_UPGRADE_ATTEMPTS
            }

        /**
         * True when the caller should refetch a track's lyrics purely because the word-synced
         * toggle can still improve the stored row, and this process has not already settled an
         * attempt for this exact row.
         *
         * The gates own this decision rather than [getLyricsWithProvider] because they short-circuit
         * on a stored row before the helper is ever asked; see [LyricsUtils.needsWordSyncedUpgrade].
         * The caller must use the returned value to decide *what to write* as well: only a result
         * that actually carries word-level timing may replace the stored row, and only the row that
         * was inspected — see [noteWordSyncedUpgradeSettled].
         *
         * Asking does not consume the attempt. See [noteWordSyncedUpgradeSettled] for why the
         * verdict, not the question, is what gets remembered.
         */
        suspend fun shouldAttemptWordSyncedUpgrade(
            mediaId: String,
            stored: LyricsEntity?,
        ): Boolean {
            // getAsync, not the blocking get operator: that one answers with the fallback when the
            // first DataStore snapshot has not landed and the caller is on the main thread, and this
            // is reachable from the lyrics panel's composition effect.
            val prioritizeWordSynced = context.dataStore.getAsync(PrioritizeWordSyncedLyricsKey) ?: true
            if (!LyricsUtils.needsWordSyncedUpgrade(prioritizeWordSynced, stored)) return false
            val storedLyrics = stored?.lyrics ?: return false
            return synchronized(wordSyncedUpgradeAttempts) { wordSyncedUpgradeAttempts[mediaId] != storedLyrics }
        }

        /**
         * Records that the upgrade sweep for [mediaId] reached a verdict, so the next gate pass
         * does not sweep the providers again for the same stored row.
         *
         * Deliberately called *after* the fetch rather than when the attempt is granted. A sweep
         * that never returned — no network, so the helper handed back the sentinel; or the track
         * was skipped, so the coroutine was cancelled — has learned nothing, and the row is still
         * worth upgrading. Consuming the attempt there would drop the upgrade for the rest of the
         * process, and it is MusicService's gate that usually asks first, at track start, which is
         * exactly when the network and the metadata are least settled. This mirrors the existing
         * rule that a sentinel row is retried on every play.
         */
        fun noteWordSyncedUpgradeSettled(
            mediaId: String,
            storedLyrics: String,
        ) {
            synchronized(wordSyncedUpgradeAttempts) { wordSyncedUpgradeAttempts[mediaId] = storedLyrics }
        }

        suspend fun getLyrics(
            mediaMetadata: MediaMetadata,
            preferredProviderOnly: Boolean = false,
            forceRefresh: Boolean = false,
        ): String = getLyricsWithProvider(
            mediaMetadata = mediaMetadata,
            preferredProviderOnly = preferredProviderOnly,
            forceRefresh = forceRefresh,
        ).lyrics

        suspend fun getLyricsWithProvider(
            mediaMetadata: MediaMetadata,
            preferredProviderOnly: Boolean = false,
            forceRefresh: Boolean = false,
        ): LyricsResult {
            refreshExtensions()
            val cacheKey = mediaMetadata.lyricsCacheKey

            // Read the "Prioritize Word Synced Lyrics" toggle once up-front. We need
            // it during the cache check below because when the toggle is ON, cached
            // non-word-synced lyrics must be treated as stale — otherwise turning the
            // toggle on and replaying a song that was already cached would keep
            // returning the old line-synced/plain result and the word-synced lookup
            // would never run.
            val prioritizeWordSynced =
                !preferredProviderOnly && (context.dataStore.getAsync(PrioritizeWordSyncedLyricsKey) ?: true)

            if (forceRefresh) {
                invalidateCache(cacheKey)
            } else {
                singleLyricsCache.get(cacheKey)?.let { cached ->
                    val cachedIsSelectable = cachedProviderIsSelectable(cached, mediaMetadata, prioritizeWordSynced)
                    val cachedIsWordSynced = LyricsUtils.hasWordSyncedLyrics(cached.lyrics)
                    // When prioritizing word-synced lyrics, only honor the cache if the
                    // cached lyrics are themselves word-synced. Eligibility follows the same
                    // lookup mode as the fetch path: an ordinary lookup requires the provider
                    // switch, while an available sweep provider's cached word-synced answer
                    // stays reusable because the sweep itself ignores those switches.
                    if (cachedIsSelectable && (!prioritizeWordSynced || cachedIsWordSynced)) {
                        GlobalLog.append(Log.DEBUG, "LyricsHelper", "Found lyrics in cache for ${mediaMetadata.title}")
                        return cached
                    }
                    GlobalLog.append(
                        Log.DEBUG,
                        "LyricsHelper",
                        if (!cachedIsSelectable) {
                            "Skipping cache for ${mediaMetadata.title}: provider ${cached.providerName} is disabled or unsupported"
                        } else {
                            "Skipping cache for ${mediaMetadata.title}: prioritizeWordSynced=true, cached lyrics not word-synced"
                        },
                    )
                }

                val cached =
                    cache.get(cacheKey)?.firstOrNull {
                        cachedProviderIsSelectable(it, mediaMetadata, prioritizeWordSynced)
                    }
                if (cached != null) {
                    val cachedIsWordSynced = LyricsUtils.hasWordSyncedLyrics(cached.lyrics)
                    if (!prioritizeWordSynced || cachedIsWordSynced) {
                        GlobalLog.append(Log.DEBUG, "LyricsHelper", "Found lyrics in cache for ${mediaMetadata.title}")
                        return cached
                    }
                }
            }

            GlobalLog.append(
                Log.DEBUG,
                "LyricsHelper",
                "Fetching lyrics for ${mediaMetadata.title} (Artist: ${mediaMetadata.artists.joinToString {
                    it.name
                }}, Album: ${mediaMetadata.album?.title})",
            )

            val isNetworkAvailable =
                networkAvailableForTests ?: try {
                    networkConnectivity.isCurrentlyConnected()
                } catch (e: Exception) {
                    true
                }

            if (!isNetworkAvailable) {
                GlobalLog.append(Log.WARN, "LyricsHelper", "Network unavailable, aborting lyrics fetch")
                return LyricsResult.Unavailable
            }

            // When "Prioritize Word Synced Lyrics" is ON (and the caller isn't asking for the
            // preferred provider only), first try to obtain word-synced lyrics from the three
            // word-sync-capable providers (BetterLyrics, YouLyPlus, Unison).
            if (prioritizeWordSynced) {
                GlobalLog.append(
                    Log.DEBUG,
                    "LyricsHelper",
                    "PrioritizeWordSynced=on: querying BetterLyrics/YouLyPlus/Unison for word-synced lyrics",
                )
                val wordSyncedResult = tryFetchWordSyncedFromPriorityProviders(mediaMetadata)
                if (wordSyncedResult != null && isMeaningfulLyrics(wordSyncedResult.lyrics)) {
                    GlobalLog.append(
                        Log.DEBUG,
                        "LyricsHelper",
                        "Word-synced lyrics found via ${wordSyncedResult.providerName}",
                    )
                    singleLyricsCache.put(cacheKey, wordSyncedResult)
                    return wordSyncedResult
                }
                GlobalLog.append(
                    Log.DEBUG,
                    "LyricsHelper",
                    "No word-synced lyrics from priority providers, falling back to normal priority flow",
                )
            }

            val ordered =
                orderedProviders()
                    .filter { isLookupEligible(it, mediaMetadata, sweepActive = false) }
            val providers = if (preferredProviderOnly) ordered.take(1) else ordered

            val result = fetchPriorityLyricsResult(providers, mediaMetadata)
            if (isMeaningfulLyrics(result.lyrics)) {
                singleLyricsCache.put(cacheKey, result)
            }

            return result
        }

        /**
         * Queries the three word-sync-capable providers (BetterLyrics, YouLyPlus, Unison) IN
         * PARALLEL and returns the first one whose response is actually word-synced (QRC/YRC/TTML
         * with word-level timings).
         */
        private suspend fun tryFetchWordSyncedFromPriorityProviders(
            mediaMetadata: MediaMetadata,
        ): LyricsResult? {
            // Fixed canonical order. This is independent of the user's provider
            // priority order so the behaviour is predictable when the toggle is ON.
            val wordSyncCapable: List<LyricsProvider> =
                WORD_SYNC_PROVIDER_NAMES
                    .mapNotNull { name -> baseProviders.firstOrNull { it.name == name } }
                    .filter { isLookupEligible(it, mediaMetadata, sweepActive = true) }

            val artist = mediaMetadata.artists.joinToString { it.name }
            val results =
                supervisorScope {
                    wordSyncCapable
                        .map { provider ->
                            async(Dispatchers.IO) {
                                val lyrics =
                                    providerRequests.run(isExtension = false, timeoutMillis = WORD_SYNC_PROVIDER_TIMEOUT_MS) {
                                        fetchProviderLyrics(provider, mediaMetadata, artist)
                                    }?.lyrics
                                if (lyrics == null) {
                                    GlobalLog.append(
                                        Log.DEBUG,
                                        "LyricsHelper",
                                        "${provider.name} returned no lyrics (timeout or error)",
                                    )
                                    null
                                } else {
                                    val isWordSynced = LyricsUtils.hasWordSyncedLyrics(lyrics)
                                    GlobalLog.append(
                                        Log.DEBUG,
                                        "LyricsHelper",
                                        "${provider.name} returned lyrics (word-synced=$isWordSynced, length=${lyrics.length})",
                                    )
                                    if (isWordSynced) provider.name to lyrics else null
                                }
                            }
                        }.mapNotNull { it.await() }
                }

            if (results.isEmpty()) return null

            // Walk results in canonical provider order (because `wordSyncCapable`
            // is ordered) and return the first one. We already filtered out
            // non-word-synced responses above, so every entry here is word-synced.
            val first = results.first()
            return LyricsResult(providerName = first.first, lyrics = first.second)
        }

        suspend fun getAllLyrics(
            mediaId: String,
            songTitle: String,
            songArtists: String,
            songAlbum: String?,
            duration: Int,
            forceRefresh: Boolean = false,
            callback: (LyricsResult) -> Unit,
        ) {
            refreshExtensions()
            val cacheKey = lyricsCacheKey(songTitle, songArtists)
            if (forceRefresh) {
                invalidateCache(cacheKey)
            } else {
                cache.get(cacheKey)?.let { results ->
                    if (results.isEmpty()) return
                    // A stored list from a provider the user has since switched off must not reappear
                    // in manual search. Replay only the enabled providers; if none survive, the
                    // lookup falls through to the current provider set.
                    val enabledResults = results.filter { cachedProviderIsEnabled(it) }
                    if (enabledResults.isNotEmpty()) {
                        enabledResults.forEach(callback)
                        return
                    }
                }
            }

            val isNetworkAvailable =
                networkAvailableForTests ?: try {
                    networkConnectivity.isCurrentlyConnected()
                } catch (e: Exception) {
                    true
                }

            if (!isNetworkAvailable) {
                return
            }

            // A search that lost a provider to a timeout or error is incomplete, and replaying it
            // from the cache would hide that provider's results until the entry is evicted. A
            // provider's ordinary no-lyrics Result.failure is still a conclusive miss.
            val searchResults = LyricsSearchResults()
            val providers = orderedProviders().filter { it.isEnabled(context) }

            // Fan out all enabled providers in parallel. The previous implementation
            // iterated providers sequentially with `forEach`, which meant the search
            // dialog stayed on "Searching providers…" until every provider returned in
            // order — a single slow provider (Musixmatch can take 10–15s) held back
            // results from faster ones (LRCLIB ~100ms). Running them concurrently lets
            // results stream into the UI as each provider finishes.
            //
            // Each provider call is wrapped in a per-provider timeout so a hung
            // provider can't pin the search dialog indefinitely. Failures and timeouts
            // are reported but never propagated — the dialog just shows fewer results.
            withContext(Dispatchers.IO) {
                supervisorScope {
                    providers.map { provider ->
                        async {
                            try {
                                val completed = providerRequests.run(provider !in baseProviders, PROVIDER_TIMEOUT_MS) {
                                    provider.getAllLyrics(
                                        mediaId, songTitle, songArtists, songAlbum, duration,
                                        onFailure = { error ->
                                            if (searchResults.recordFailure(error) == ProviderLyricsAnswer.FAILED) {
                                                reportException(error)
                                            }
                                        },
                                    ) lyricsCallback@{ lyrics ->
                                        val normalizedLyrics = LyricsUtils.lyricsOrNotFound(lyrics)
                                        if (normalizedLyrics == LYRICS_NOT_FOUND) return@lyricsCallback
                                        val result = LyricsResult(provider.name, normalizedLyrics)
                                        searchResults.add(result)
                                        callback(result)
                                    }
                                }
                                if (completed == null) searchResults.failed()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                if (searchResults.recordFailure(e) == ProviderLyricsAnswer.FAILED) {
                                    reportException(e)
                                }
                            }
                        }
                    }.forEach { it.await() }
                }
            }
            searchResults.cacheableResults()?.let { cache.put(cacheKey, it) }
        }

        /**
         * Resolves lyrics from all providers in parallel and returns the best result by (sync tier:
         * word > line > plain) then by provider priority (lower index wins).
         */
        private suspend fun fetchPriorityLyricsResult(
            providers: List<LyricsProvider>,
            mediaMetadata: MediaMetadata,
        ): LyricsResult {
            if (providers.isEmpty()) return LyricsResult.Unavailable

            val artist = mediaMetadata.artists.joinToString { it.name }
            val fetches =
                supervisorScope {
                    providers
                        .map { provider ->
                            async(Dispatchers.IO) {
                                val fetch =
                                    providerRequests.run(provider !in baseProviders, PROVIDER_TIMEOUT_MS) {
                                        fetchProviderLyrics(provider, mediaMetadata, artist)
                                    } ?: ProviderLyricsFetch.Failed
                                provider to fetch
                            }
                        }.map { it.await() }
                }
            val results = fetches.mapNotNull { (provider, fetch) -> fetch.lyrics?.let { provider.name to it } }

            if (results.isEmpty()) {
                return if (isConclusiveMiss(fetches.map { it.second.answer })) {
                    LyricsResult(providerName = "", lyrics = LYRICS_NOT_FOUND)
                } else {
                    LyricsResult.Unavailable
                }
            }

            // Ranking: word-synced > line-synced > plain. `firstOrNull` walks the
            // results in provider-priority order (because `providers` is ordered), so
            // when multiple providers return the same tier the higher-priority one
            // wins — this is what restores the priority order the streaming
            // implementation broke.
            val wordSynced = results.firstOrNull { LyricsUtils.hasWordSyncedLyrics(it.second) }
            if (wordSynced != null) return LyricsResult(providerName = wordSynced.first, lyrics = wordSynced.second)

            val lineSynced = results.firstOrNull { LyricsUtils.isLineSyncedLrc(it.second) }
            if (lineSynced != null) return LyricsResult(providerName = lineSynced.first, lyrics = lineSynced.second)

            val first = results.first()
            return LyricsResult(providerName = first.first, lyrics = first.second)
        }

        private suspend fun fetchProviderLyrics(
            provider: LyricsProvider,
            mediaMetadata: MediaMetadata,
            artist: String,
        ): ProviderLyricsFetch =
            try {
                provider
                    .getLyrics(
                        mediaMetadata.id,
                        mediaMetadata.title,
                        artist,
                        mediaMetadata.album?.title,
                        mediaMetadata.duration,
                        mediaMetadata.isrc,
                    ).fold(
                        onSuccess = { lyrics ->
                            LyricsUtils.lyricsOrNotFound(lyrics).takeIf { it != LYRICS_NOT_FOUND }
                                ?.let { ProviderLyricsFetch.found(it) }
                                ?: ProviderLyricsFetch.Missing
                        },
                        onFailure = {
                            reportException(it)
                            ProviderLyricsFetch(answer = it.toProviderLyricsAnswer())
                        },
                    )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                ProviderLyricsFetch(answer = e.toProviderLyricsAnswer())
            }

        private suspend fun orderedProviders(): List<LyricsProvider> {
            providerOverrideForTests?.let { return it }
            val orderStr = context.dataStore.data.first()[LyricsProviderOrderKey]
            val orderedEnums = deserializeLyricsProviderOrder(orderStr)
            val providerMap: Map<PreferredLyricsProvider, LyricsProvider> =
                mapOf(
                    PreferredLyricsProvider.LRCLIB to LrcLibLyricsProvider,
                    PreferredLyricsProvider.KUGOU to KuGouLyricsProvider,
                    PreferredLyricsProvider.MEGALOBIZ to MegalobizLyricsProvider,
                    PreferredLyricsProvider.BETTER_LYRICS to BetterLyricsProvider,
                    PreferredLyricsProvider.BETTER_LYRICS_PORTATO to BetterLyricsPortatoProvider,
                    PreferredLyricsProvider.YOULY_PLUS to YouLyPlusLyricsProvider,
                    PreferredLyricsProvider.SIMPMUSIC to SimpMusicLyricsProvider,
                    PreferredLyricsProvider.PAXSENIX_APPLE_MUSIC to PaxsenixAppleMusicLyricsProvider,
                    PreferredLyricsProvider.APPLE_MUSIC to AppleMusicAccountLyricsProvider,
                    PreferredLyricsProvider.TIDAL to TidalLyricsProvider,
                    PreferredLyricsProvider.DEEZER to DeezerLyricsProvider,
                    PreferredLyricsProvider.UNISON to UnisonLyricsProvider,
                    PreferredLyricsProvider.LYPRIO to LyprioLyricsProvider,
                    PreferredLyricsProvider.MUSIXMATCH_EXPERIMENTAL to MusixmatchExperimentalLyricsProvider,
                )
            val userOrdered = orderedEnums.mapNotNull { providerMap[it] }
            val rest = baseProviders.filterNot { it in userOrdered }
            val builtIn = userOrdered + rest
            val extensions = moduleLyrics.providers
            return if (context.dataStore.getAsync(PreferLyricsExtensionsKey) == true) extensions + builtIn else builtIn + extensions
        }

        private fun isMeaningfulLyrics(lyrics: String): Boolean = LyricsUtils.hasMeaningfulLyricsContent(lyrics)

        private fun cachedProviderIsSelectable(
            cached: LyricsResult,
            mediaMetadata: MediaMetadata,
            sweepActive: Boolean,
        ): Boolean {
            val provider = availableProviders.firstOrNull { it.name == cached.providerName } ?: return false
            return isLookupEligible(provider, mediaMetadata, sweepActive)
        }

        /**
         * One eligibility authority for the fetch path and its cache. [sweepActive] preserves the
         * documented word-sync exception: the fixed sweep queries its three providers regardless of
         * their fallback switches, so their answers must remain reusable in that lookup mode. Every
         * other mode accepts only enabled providers, and a provider that cannot serve this media id
         * is never eligible.
         */
        private fun isLookupEligible(
            provider: LyricsProvider,
            mediaMetadata: MediaMetadata,
            sweepActive: Boolean,
        ): Boolean {
            if (!supportsMediaId(provider, mediaMetadata.id)) return false
            return (sweepActive && provider.name in WORD_SYNC_PROVIDER_NAMES) || provider.isEnabled(context)
        }

        private fun cachedProviderIsEnabled(cached: LyricsResult): Boolean =
            availableProviders.firstOrNull { it.name == cached.providerName }?.isEnabled(context) == true

        private fun supportsMediaId(
            provider: LyricsProvider,
            mediaId: String,
        ): Boolean {
            val isNonYouTubeId = mediaId.isTelegramMediaId() || mediaId.isLocalMediaId()
            if (!isNonYouTubeId) return true
            return provider !is SimpMusicLyricsProvider &&
                provider !is YouTubeLyricsProvider &&
                provider !is YouTubeSubtitleLyricsProvider
        }

        fun clearCache() {
            cache.evictAll()
            singleLyricsCache.evictAll()
        }

        private fun invalidateCache(cacheKey: String) {
            cache.remove(cacheKey)
            singleLyricsCache.remove(cacheKey)
        }

        private val MediaMetadata.lyricsCacheKey: String
            get() =
                lyricsCacheKey(
                    title = title,
                    artists = artists.joinToString { it.name },
                )

        private fun lyricsCacheKey(
            title: String,
            artists: String,
        ): String = "$artists-$title".replace(" ", "")

        companion object {
            // Shared by all fetch paths and helper instances (service, menu and preload).
            // Keep the six built-in slots available while a bounded extension request runs.
            private val providerRequests = LyricsProviderRequests()
            private const val MAX_CACHE_SIZE = 16

            // One entry per track whose stored lyrics a word-synced upgrade has been attempted
            // against, so a session that plays through a large library cannot grow this map
            // without bound.
            private const val MAX_WORD_SYNCED_UPGRADE_ATTEMPTS = 64

            // The fixed sweep order. The sweep deliberately ignores these providers' fallback
            // switches, so cache revalidation must accept their answers in the same lookup mode.
            private val WORD_SYNC_PROVIDER_NAMES = listOf("BetterLyrics", "YouLyPlus", "Unison")

            // Per-provider hard timeout for the normal priority flow. Provider calls
            // that exceed this are cancelled and dropped from ranking. Tuned to be
            // long enough for typical provider latency (~3–5s for Musixmatch under
            // good conditions) but short enough that a hung provider can't pin the
            // lyrics panel.
            private const val PROVIDER_TIMEOUT_MS = 8_000L

            // Longer timeout for the "Prioritize Word Synced Lyrics" path. YouLyPlus
            // in particular fans out across 5 mirrors × 2 endpoints (up to 10 HTTP
            // requests in sequence) and can legitimately take 10–15s, so the regular
            // 8s timeout silently skipped it even when it had word-synced lyrics
            // available. Since this path only runs once per song when the toggle is
            // ON (and the user has explicitly opted in for higher-quality lyrics),
            // the extra latency is acceptable.
            private const val WORD_SYNC_PROVIDER_TIMEOUT_MS = 15_000L
        }
    }

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
    /** False when no provider could be asked or reached, so a miss must not be stored. */
    val isConclusive: Boolean = true,
) {
    companion object {
        val Unavailable = LyricsResult(providerName = "", lyrics = LYRICS_NOT_FOUND, isConclusive = false)
    }
}
