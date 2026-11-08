@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookieSource
import moe.rukamori.archivetune.shared.data.diagnostics.RotatingDiagnosticLog
import moe.rukamori.archivetune.shared.data.platform.ios.*
import moe.rukamori.archivetune.shared.data.provider.TIDAL_PKCE_REDIRECT_URI
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashLogStore
import moe.rukamori.archivetune.shared.domain.diagnostics.CrashReport
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLevel
import moe.rukamori.archivetune.shared.domain.diagnostics.DiagnosticLogStore
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapaneseLanguagePackManager
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.platform.ChallengeEvaluator
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceController
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresencePlatform
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresencePolicy
import moe.rukamori.archivetune.shared.domain.presence.ListenerPresenceStore
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.ui.ios.IosMountedRoot
import moe.rukamori.archivetune.shared.ui.ios.IosSessionGraph
import moe.rukamori.archivetune.shared.ui.settings.IosSettingsOpenState
import moe.rukamori.archivetune.shared.ui.settings.IosVaultSettings
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore
import platform.Foundation.NSBundle
import platform.Foundation.NSLock

/** What the iOS root mounts: progress, an explicit unavailable boundary, or the opened session graph. */
internal sealed interface IosSessionState {
    data object Opening : IosSessionState
    data object SettingsUnavailable : IosSessionState
    data object GraphUnavailable : IosSessionState
    data class Ready(val graph: IosSessionGraph) : IosSessionState
}

/**
 * One host per process, owned by the Swift app session (ArchiveTuneSession) and created at launch. Detaching or
 * recreating the SwiftUI controller and backgrounding never close it; [close] is the final shutdown and is idempotent.
 * Settings open on a worker after construction; the UI observes [settingsState] and never waits for it.
 * Root/repository integration must use these same instances; the queue belongs to the session graph opened over them.
 */
class IosHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The window's safe area, reported by the Swift host. The shared root pads with it once; no screen repeats it. */
    val safeArea = IosSafeArea()
    internal val playbackPort = IosPlaybackPort()
    private val sessionState = MutableStateFlow<IosSessionState>(IosSessionState.Opening)

    /** The session graph the root mounts once settings and the database are open. */
    internal val session: StateFlow<IosSessionState> = sessionState.asStateFlow()
    private val active = MutableStateFlow(true)

    /** False while the app is in the background, so the root stops drawing frames; playback is unaffected. */
    internal val appActive: StateFlow<Boolean> = active.asStateFlow()
    private var graph: IosSessionGraph? = null

    /** The one controller the app session re-presents. Detaching it closes nothing; the services stay owned here. */
    val viewController = MainViewController(root = { IosMountedRoot(this) }) { IosPlaybackErrorOverlay(this) }
    val player = MusicPlayerController(playbackPort, scope)
    private val failure = MutableStateFlow<PlaybackFailure?>(null)

    /** The failure the playback error dialog shows, or null while none is open. */
    val playbackFailure: StateFlow<PlaybackFailure?> = failure.asStateFlow()
    private var playbackRetry: ((occurrenceId: String) -> Unit)? = null
    val credentials = IosKeychainCredentialStore()
    val files = IosPlatformFiles()
    val browser = IosBrowserAuthPort { requireNotNull(viewController.view.window) }
    val embeddedBrowser = IosEmbeddedBrowserAuthPort { viewController }

    /** The embedded WKWebView sheet that catches the fixed TIDAL PKCE HTTPS return. */
    val tidalPkceSheet = IosTidalPkceSheet({ viewController }, TIDAL_PKCE_REDIRECT_URI)
    val cookieBrowser = IosCookieOnlyBrowserAuth { viewController }

    /** The JS-evaluating WKWebView sheet that harvests the MusicKit pair, as Android's login WebView does. */
    val appleMusicSheet = IosAppleMusicLoginSheet { viewController }

    /** Stateless: each evaluation owns a fresh bounded QuickJS runtime, so there is nothing to close. */
    val challenges: ChallengeEvaluator = IosChallengeEvaluator()
    private val settingsOpen = MutableStateFlow<IosSettingsOpenState>(IosSettingsOpenState.Opening)

    /** Opening until the Keychain read completes; Unavailable is terminal for this host, never a memory fallback. */
    val settingsState: StateFlow<IosSettingsOpenState> = settingsOpen.asStateFlow()
    private val japanesePack = MutableStateFlow<JapaneseLanguagePackManager?>(null)

    /** Null until settings are ready. Its absence never blocks playback, settings or startup. */
    val japaneseLanguagePack: StateFlow<JapaneseLanguagePackManager?> = japanesePack.asStateFlow()
    private var settings: IosVaultSettings? = null
    private var foregroundRequested = false
    private var nowPlaying: IosNowPlaying? = null
    private val telegramSession = MutableStateFlow<IosTelegramClient?>(null)
    private val linkRoutes = Channel<String>(capacity = LINK_ROUTE_CAPACITY)

    /** Links handed to the session graph, which resolves them into routes. The bound drops a burst of opens. */
    internal val linkRequests = linkRoutes.receiveAsFlow()

    /**
     * The one TDLib session of this host, created once the account store exists, because its admission is that store's
     * native Telegram account. Null until settings are ready.
     */
    val telegram: StateFlow<IosTelegramClient?> = telegramSession.asStateFlow()
    // willTerminate and a background-triggered close can race; only the first caller owns the durable teardown.
    private val closeLock = NSLock()
    private var closed = false

    // Its own scope, so quitting can wait a bounded time for the one stopped payload before the session is released.
    private val presenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val presenceTransport = IosListenerPresenceTransport(iosListenerPresenceEndpoint())

    /**
     * The listener-presence opt-in a settings screen switches, or null when its directory could not be prepared.
     * Heartbeats follow audible playback and go out only while the user has opted in and the build supplies an HTTPS
     * endpoint (ARCHIVETUNE_LISTENER_PRESENCE_URL through Info.plist).
     */
    val listenerPresence: ListenerPresenceStore? = try {
        IosListenerPresenceStore(iosListenerPresenceDirectory())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private val diagnosticsRoot: String? = try {
        iosDiagnosticsDirectory()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /**
     * The persistent, redacted log of this app's own process across launches, for a log viewer and a report, or null
     * when its directory could not be prepared. Its writer runs only while something is logged.
     */
    val diagnostics: DiagnosticLogStore? = diagnosticsRoot?.let { root ->
        val started = iosNowMillis()
        RotatingDiagnosticLog(
            files = IosDiagnosticLogFiles("$root/logs"),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            sessionStartMillis = started,
            nowMillis = ::iosNowMillis,
            formatTime = ::iosDiagnosticTime,
            sessionHeader = "Natune session ${iosDiagnosticTime(started)} version=${iosApplicationVersion()}",
        )
    }

    /** The bounded store of crash records the settings page lists, beside the reports it replaces nothing of. */
    val crashLogs: CrashLogStore? = diagnosticsRoot?.let { IosCrashLogStore("$it/records") }

    init {
        // First, so a failure while the rest of the host starts is still captured for the next launch.
        diagnosticsRoot?.let { root ->
            IosCrashCapture.install(root, iosApplicationVersion(), NSBundle.mainBundle.bundleIdentifier.orEmpty())
        }
        scope.launch { openSettingsAndPack() }
        // Queue-owned loads report only exhausted recovery through IosSessionGraph; standalone loads still report here.
        scope.launch { player.failures.collect { if (it.attemptId == null) failure.value = it } }
        listenerPresence?.let { store ->
            ListenerPresenceController(
                scope = presenceScope,
                store = store,
                transport = presenceTransport,
                playing = player.snapshot.map { it?.isAudioAdvancing == true }
                    .stateIn(presenceScope, SharingStarted.Eagerly, false),
                platform = ListenerPresencePlatform.IOS,
                appVersion = ListenerPresencePolicy.sanitizedAppVersion(iosApplicationVersion()),
            ).start()
        }
    }

    fun closePlaybackFailure() {
        failure.value = null
    }

    /**
     * Called once by the queue composition, which alone can load an occurrence again. A queue that also reports songs
     * no source resolved shows them through [showPlaybackFailure].
     */
    fun installPlaybackRetry(retry: (occurrenceId: String) -> Unit) {
        check(!closed && playbackRetry == null)
        playbackRetry = retry
    }

    fun showPlaybackFailure(value: PlaybackFailure) {
        if (!closed) failure.value = value
    }

    internal fun retryPlayback(occurrenceId: String) {
        playbackRetry?.invoke(occurrenceId)
    }

    /** The crash report an earlier launch stored, for the crash screen, or null when there is none. */
    fun storedCrash(): CrashReport? = IosCrashCapture.stored()

    /** Removes the stored crash report. False while it is still on disk, so it is offered again next launch. */
    fun clearCrash(): Boolean = IosCrashCapture.clear()

    /** Prepared provider audio enters playback only through the iOS admission rules in [IosPlaybackPort]. */
    fun loadPrepared(
        occurrenceId: String,
        mediaId: String,
        prepared: PreparedAudio,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ) {
        playbackPort.submitPrepared(occurrenceId, mediaId, prepared, startPositionMs, playWhenReady)
    }

    /**
     * Supplies the account store that personal progressive, native and TIDAL audio admit against. The root calls it once
     * the vault is ready, with the single store it owns for the session, so no default or dummy store is ever used. Until
     * then that audio is refused, and the anonymous progressive and Deezer paths are unaffected.
     */
    fun attachAccounts(accounts: AcceptedAccountReader, cookies: ScopedCookieSource) {
        check(!closed)
        playbackPort.attachAccounts(accounts, cookies)
    }

    /**
     * Forwarded by the Swift app session on each real activation. The pack spends its single per-process
     * consideration here; a call before settings are ready is remembered and delivered once they are.
     */
    fun foregrounded() {
        if (closed) return
        active.value = true
        playbackPort.setAppActive(true)
        foregroundRequested = true
        val pack = japanesePack.value ?: return
        scope.launch { pack.onForeground() }
    }

    /** Forwarded by the Swift app session when the app leaves the foreground. */
    fun backgrounded() {
        if (closed) return
        active.value = false
        // The UI stops drawing, so the playback port also stops reading its state at the active rate.
        playbackPort.setAppActive(false)
        // A swipe-kill never posts willTerminate, so the queue is made durable here rather than only at final close.
        val opened = graph ?: return
        runDurably(BACKGROUND_CHECKPOINT_TIMEOUT_MS) {
            try {
                opened.checkpointQueue()
            } catch (failure: Exception) {
                report("queue checkpoint on background failed: ${failure::class.simpleName}")
            }
        }
    }

    /**
     * The `archivetune://` link the Swift app session receives in `onOpenURL`. It is queued for the session graph,
     * which decides which routes a link may open; a link before the graph is ready is dropped rather than replayed.
     */
    fun openLink(link: String) {
        if (!closed) linkRoutes.trySend(link)
    }

    /** Widget links foreground the app and wait once for the restored, authoritative queue. */
    fun widgetCommand(action: String) {
        if (closed || action !in setOf("play", "pause", "next", "previous", "toggle")) return
        scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                val ready = sessionState.first { it !is IosSessionState.Opening } as? IosSessionState.Ready ?: return@withTimeoutOrNull
                when (action) {
                    "toggle" -> if (ready.graph.player.snapshot.value?.playWhenReady == true) ready.graph.player.pause() else ready.graph.player.play()
                    "play" -> ready.graph.player.play()
                    "pause" -> ready.graph.player.pause()
                    "next" -> ready.graph.skipNext()
                    "previous" -> ready.graph.skipPrevious()
                }
            }
        }
    }

    /** Read-only, credential-free data for Apple system templates and the WidgetKit extension. */
    fun systemSnapshotJson(): String = moe.rukamori.archivetune.shared.ui.ios.iosSystemSnapshotJson(graph?.queue?.state?.value, player)

    fun systemLibrary(completion: (String) -> Unit) {
        scope.launch {
            try {
                val ready = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    sessionState.first { it !is IosSessionState.Opening }
                } as? IosSessionState.Ready ?: return@launch completion("[]")
                val page = ready.graph.libraryContent.chipPage(moe.rukamori.archivetune.shared.domain.library.LibraryFilter.SONGS)
                    .first { it.initialized || it.failed }
                completion(kotlinx.serialization.json.buildJsonArray {
                    page.items.take(200).forEach { row -> add(kotlinx.serialization.json.buildJsonObject {
                        put("id", kotlinx.serialization.json.JsonPrimitive(row.item.ref.id))
                        put("provider", kotlinx.serialization.json.JsonPrimitive(row.item.ref.provider))
                        put("title", kotlinx.serialization.json.JsonPrimitive(row.item.title))
                        put("artist", kotlinx.serialization.json.JsonPrimitive(row.item.subtitle))
                    }) }
                }.toString())
            } catch (_: Exception) { completion("failed") }
        }
    }

    fun systemPlay(provider: String, id: String, occurrence: String?, completion: (String) -> Unit) {
        scope.launch {
            try {
                val ready = kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    sessionState.first { it !is IosSessionState.Opening }
                } as? IosSessionState.Ready ?: return@launch completion("failed")
                val queued = ready.graph.queue.state.value.entries.firstOrNull {
                    it.occurrenceId == occurrence && it.item.ref.provider == provider && it.item.ref.id == id
                }
                if (queued != null) ready.graph.queue.select(queued.occurrenceId) else {
                    val item = ready.graph.library.findSong(id, provider) ?: return@launch completion("failed")
                    val row = moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence("carplay:" + platform.Foundation.NSUUID().UUIDString, item)
                    ready.graph.queue.replace(listOf(row), row.occurrenceId, true, staysFinite = true)
                }
                ready.graph.player.play()
                completion("ready")
            } catch (_: Exception) { completion("failed") }
        }
    }

    /** One line in the persistent diagnostics log and the console, for failures the UI only reports. */
    fun report(message: String) {
        println("Natune iOS: $message")
        diagnostics?.append(DiagnosticLevel.INFO, "Host", message)
    }

    /**
     * Called once by the authoritative shared queue/repository composition, not by a demo queue. Single-shot per
     * host: a second call fails rather than registering duplicate remote-command targets.
     */
    fun installSystemControls(
        metadata: (occurrenceId: String) -> ContentItem?,
        next: () -> Unit,
        previous: () -> Unit,
        metadataChanges: kotlinx.coroutines.flow.Flow<*>? = null,
        commandAvailability: kotlinx.coroutines.flow.Flow<IosTransportAvailability>? = null,
    ): IosNowPlaying {
        check(!closed && nowPlaying == null)
        return IosNowPlaying(player, metadata, next, previous, metadataChanges, commandAvailability).also { nowPlaying = it }
    }

    /**
     * Final process shutdown, called only by the Swift app session at willTerminate. Idempotent and safe off the
     * main thread.
     */
    fun close() {
        closeLock.lock()
        val first = !closed
        if (first) closed = true
        closeLock.unlock()
        if (!first) return
        val openedGraph = graph
        graph = null
        // The queue checkpoint comes before every Main-dispatched teardown below, so a throwing or unavailable main
        // dispatcher cannot leave the last queue revision unsaved.
        if (openedGraph != null) {
            runDurably(QUEUE_CHECKPOINT_TIMEOUT_MS) {
                try {
                    openedGraph.checkpointQueue()
                } catch (failure: Exception) {
                    report("queue checkpoint before close failed: ${failure::class.simpleName}")
                }
            }
            // The graph checkpoints once more while its owners can still write, then closes the database and clients.
            // It runs before the Main-dispatched teardown below so a throw there cannot skip the durable close.
            runDurably(GRAPH_CLOSE_TIMEOUT_MS) {
                try {
                    openedGraph.close()
                } catch (failure: Exception) {
                    report("session graph close failed: ${failure::class.simpleName}")
                }
            }
        }
        nowPlaying?.close()
        nowPlaying = null
        val session = telegramSession.value
        telegramSession.value = null
        browser.close()
        embeddedBrowser.close()
        files.close()
        player.close()
        val pack = japanesePack.value
        val opened = settings
        japanesePack.value = null
        settings = null
        scope.cancel()
        val presence = presenceScope.coroutineContext[Job]
        // Only work that may be dropped belongs behind the cancelled host scope: presence, TDLib, the pack and settings.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            // A running heartbeat gets its one stopped payload; a slow endpoint never holds up the rest.
            withTimeoutOrNull(PRESENCE_CLOSE_TIMEOUT_MS) { presence?.cancelAndJoin() }
            presenceTransport.close()
            session?.close()
            pack?.close()
            opened?.close()
        }
    }

    /**
     * Runs [block] on the worker pool and waits at most [timeoutMs] for it on the calling thread. Backgrounding and
     * willTerminate both run on the main thread, and a Main-dispatched continuation, including
     * `Dispatchers.Main.immediate`, can be dropped or throw once the host is leaving. The bound lets a queue operation
     * that is suspended on main resume instead of leaving the checkpoint deadlocked behind it; work that outlives the
     * bound still continues on the worker pool.
     */
    private fun runDurably(timeoutMs: Long, block: suspend () -> Unit) {
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { block() }
        runBlocking(NonCancellable) { withTimeoutOrNull(timeoutMs) { work.join() } }
    }

    /**
     * Builds the Telegram session over [accounts]. A TDLib database left by an earlier sign-in restores its session at
     * once, so Telegram tracks in a restored queue can play as soon as the session is attached. Without a build identity
     * the session stays not configured, and a failure here leaves Telegram absent, never the host. Starting TDLib maps
     * and initialises its native library, so this runs on a worker.
     */
    private fun buildTelegramSession(accounts: VaultProviderAccountStore): IosTelegramClient? = try {
        val storageRoot = iosTelegramStorageRoot()
        IosTelegramClient(
            credentials = iosTelegramApiCredentials(),
            storageRoot = storageRoot,
            applicationVersion = iosApplicationVersion(),
            cancelRetainedDownloads = { cancelIosTelegramTransfers() },
            nativeAccount = IosTelegramNativeAccountPort(
                beginAttempt = accounts::beginNativeTelegramAttempt,
                acceptSession = accounts::acceptNativeTelegramSession,
                revokeSession = accounts::revokeNativeTelegramSession,
            ),
            log = { message ->
                println("Natune iOS: $message")
                diagnostics?.append(DiagnosticLevel.INFO, "Telegram", message)
            },
        ).also { if (iosTelegramHasSession(storageRoot)) it.start() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /**
     * Runs beside settings and the session graph, which never wait for it. The build happens on a worker, and the session
     * reaches playback on the main thread once it exists. A host closed meanwhile releases the session here, because
     * close() has already taken what was attached and nothing else would close this one.
     */
    private fun startTelegramInBackground(accounts: VaultProviderAccountStore) {
        scope.launch(NonCancellable) {
            val session = withContext(Dispatchers.Default) { buildTelegramSession(accounts) } ?: return@launch
            if (closed) {
                withContext(Dispatchers.Default) { session.close() }
                return@launch
            }
            playbackPort.attachTelegram(session)
            telegramSession.value = session
            // The graph may not exist yet; the assemble step re-hands the session it finds here.
            graph?.attachTelegram(session)
        }
    }

    private suspend fun openSettingsAndPack() {
        val opened = try {
            IosVaultSettings.open()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!closed) {
                settingsOpen.value = IosSettingsOpenState.Unavailable
                sessionState.value = IosSessionState.SettingsUnavailable
            }
            return
        }
        // close() can run while the Keychain read is on a worker. Never publish into a closed host.
        if (closed) {
            withContext(NonCancellable) { opened.close() }
            return
        }
        settings = opened
        // The one account store of the session, over the vault this host has just opened. Personal audio is admitted only
        // through it. It is both the account reader and the cookie source, as on the desktop host.
        val accounts = VaultProviderAccountStore(opened.vault)
        attachAccounts(accounts, accounts)
        startTelegramInBackground(accounts)
        val pack = try {
            iosJapaneseLanguagePackManager(
                scope = scope,
                workerDispatcher = Dispatchers.Default,
                snapshots = opened.vault.snapshots,
                editSettings = { edit -> opened.vault.edit(edit); Unit },
                networkAvailable = { iosNetworkAvailable() },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A pack that cannot be constructed leaves Japanese romanization absent; settings stay ready.
            null
        }
        japanesePack.value = pack
        settingsOpen.value = IosSettingsOpenState.Ready(opened)
        openSessionGraph(opened, accounts)
        if (foregroundRequested && !closed) scope.launch { pack?.onForeground() }
    }

    private suspend fun openSessionGraph(opened: IosVaultSettings, accounts: VaultProviderAccountStore) {
        val opening = try {
            IosSessionGraph.open(this, opened.vault, accounts, ::report)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("session graph did not open: ${failure::class.simpleName}")
            if (!closed) sessionState.value = IosSessionState.GraphUnavailable
            return
        }
        if (closed) {
            opening.close()
            return
        }
        graph = opening
        installPlaybackRetry(opening::retryAfterFailure)
        installSystemControls(
            metadata = opening::metadata,
            metadataChanges = opening.queue.state,
            next = { opening.scope.launch { opening.skipNext() } },
            previous = { opening.scope.launch { opening.skipPrevious() } },
            commandAvailability = opening.transportAvailability,
        )
        sessionState.value = IosSessionState.Ready(opening)
        opening.startAfterMount()
    }

    private companion object {
        const val PRESENCE_CLOSE_TIMEOUT_MS = 2_000L
        const val BACKGROUND_CHECKPOINT_TIMEOUT_MS = 1_000L
        const val QUEUE_CHECKPOINT_TIMEOUT_MS = 2_000L
        // Leaves the system time to terminate the process after the queue checkpoint and database close had their turn.
        const val GRAPH_CLOSE_TIMEOUT_MS = 5_000L
        const val LINK_ROUTE_CAPACITY = 8
    }
}
