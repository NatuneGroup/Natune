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
        val preloadCount: Int, val preferExtensions: Boolean, val credential: String, val endpoint: String, val available: Set<String>, val providerRevision: Long,
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
                val names = LyricsProviderPreference.entries.map { it.providerName }
                prefs to Config(
                    prefs.lyricsProviderOrder().map { it.providerName },
                    names.associateWith(prefs::lyricsProviderEnabled),
                    prefs[PrioritizeWordSyncedLyricsKey, true],
                    prefs[QueueLyricsPreloadCountKey, 3].coerceIn(0, 10),
                    prefs[PreferLyricsExtensionsKey, false],
                    prefs[PaxsenixApiKeyKey].orEmpty(), prefs[PaxsenixEndpointKey].orEmpty(), available.toSet(), revision,
                )
            }.distinctUntilChangedBy { it.second }.collectLatest { (prefs, config) ->
                coroutineScope {
                    val providers = orderLyricsProviders(providerFactory(prefs), config.order, config.preferExtensions)
                    val helper = LyricsHelper(
                        providers,
                        { name -> name in config.available && config.enabled[name] != false },
                        this, { config.wordSync }, networkAvailable, reportFailure,
                        isAvailable = { name -> name in config.available },
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

internal fun orderLyricsProviders(
    providers: List<LyricsProvider>,
    order: List<String>,
    preferExtensions: Boolean = false,
): List<LyricsProvider> {
    val sorted = moe.rukamori.archivetune.shared.domain.settings.orderLyricsProviders(providers, order) { it.name }
    return if (preferExtensions) sorted.filterNot { it.name in order } + sorted.filter { it.name in order } else sorted
}
