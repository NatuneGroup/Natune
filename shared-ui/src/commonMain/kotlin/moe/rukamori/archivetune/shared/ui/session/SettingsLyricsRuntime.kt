/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.lyrics.*
import moe.rukamori.archivetune.shared.domain.settings.*

/**
 * Connects persisted controls to real provider orchestration/preloading. Endpoint instances are recreated
 * when credentials/endpoint change, rather than retaining construction-time configuration indefinitely.
 */
class SettingsLyricsRuntime(
    scope: CoroutineScope,
    store: SettingsStore,
    availableProviderNames: StateFlow<Set<String>>,
    providerRevision: StateFlow<Long>,
    providerFactory: (PreferenceSnapshot) -> List<LyricsProvider>,
    networkAvailable: () -> Boolean,
    reportFailure: (String, Throwable) -> Unit,
) {
    private data class Config(
        val order: List<String>, val enabled: Map<String, Boolean>, val wordSync: Boolean,
        val preloadCount: Int, val credential: String, val endpoint: String, val available: Set<String>, val providerRevision: Long,
    ) {
        override fun toString(): String = "LyricsConfig(<redacted>)"
    }
    private data class Window(val current: LyricsRequest?, val upcoming: List<LyricsRequest>)
    private class Runtime(val helper: LyricsHelper)
    private val currentRuntime = MutableStateFlow<Runtime?>(null)
    private val window = MutableStateFlow(Window(null, emptyList()))

    init {
        scope.launch {
            combine(store.snapshots.filterNotNull(), availableProviderNames, providerRevision) { prefs, available, revision ->
                val names = LyricsProviderPreference.entries.map { it.providerName } +
                    listOf("BiniLyrics", "lrc.red", "Genius", "YouTube Music", "YouTube Subtitle")
                prefs to Config(
                    prefs.lyricsProviderOrder().map { it.providerName },
                    names.associateWith(prefs::lyricsProviderEnabled),
                    prefs[PrioritizeWordSyncedLyricsKey, false],
                    prefs[QueueLyricsPreloadCountKey, 3].coerceIn(0, 10),
                    prefs[PaxsenixApiKeyKey].orEmpty(), prefs[PaxsenixEndpointKey].orEmpty(), available.toSet(), revision,
                )
            }.distinctUntilChangedBy { it.second }.collectLatest { (prefs, config) ->
                coroutineScope {
                    val providers = orderLyricsProviders(providerFactory(prefs), config.order)
                    val helper = LyricsHelper(
                        providers,
                        { name -> name in config.available && config.enabled[name] != false },
                        this, { config.wordSync }, networkAvailable, reportFailure,
                    )
                    val preload = LyricsPreloadManager(helper, this, nextCount = config.preloadCount)
                    val runtime = Runtime(helper)
                    currentRuntime.value = runtime
                    launch {
                        window.collect {
                            preload.onSongChanged(it.current)
                            preload.update(it.upcoming)
                        }
                    }
                    try { awaitCancellation() }
                    finally {
                        withContext(NonCancellable) {
                            preload.close()
                            if (currentRuntime.value === runtime) currentRuntime.value = null
                        }
                    }
                }
            }
        }
    }

    /** Upcoming tracks come from the existing queue owner, never a second queue implementation. */
    fun updateWindow(current: LyricsRequest?, upcoming: List<LyricsRequest>) {
        window.value = Window(current, upcoming.toList())
    }

    suspend fun lyrics(request: LyricsRequest, forceRefresh: Boolean = false): LyricsResult =
        currentRuntime.filterNotNull().first().helper.getLyricsWithProvider(request, forceRefresh = forceRefresh)
}

/** Extra providers keep their established slots; the persisted picker only orders its 17 members. */
internal fun orderLyricsProviders(providers: List<LyricsProvider>, order: List<String>): List<LyricsProvider> {
    val members = providers.filter { it.name in order }.sortedBy { order.indexOf(it.name) }.iterator()
    return providers.map { if (it.name in order) members.next() else it }
}
