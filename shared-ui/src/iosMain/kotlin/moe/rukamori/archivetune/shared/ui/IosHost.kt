@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookieSource
import moe.rukamori.archivetune.shared.data.platform.ios.*
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapaneseLanguagePackManager
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.platform.ChallengeEvaluator
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.ui.settings.IosSettingsOpenState
import moe.rukamori.archivetune.shared.ui.settings.IosVaultSettings
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore

/**
 * One host per process, owned by the Swift app session (ArchiveTuneSession) and created at launch. Detaching or
 * recreating the SwiftUI controller and backgrounding never close it; [close] is the final shutdown and is idempotent.
 * Settings open on a worker after construction; the UI observes [settingsState] and never waits for it.
 * Root/repository integration must use these same instances; this host never owns a queue.
 */
class IosHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val playback = IosPlaybackPort()
    /** The one controller the app session re-presents. Detaching it closes nothing; the services stay owned here. */
    val viewController = MainViewController()
    val player = MusicPlayerController(playback, scope)
    val credentials = IosKeychainCredentialStore()
    val files = IosPlatformFiles()
    val browser = IosBrowserAuthPort { requireNotNull(viewController.view.window) }
    val embeddedBrowser = IosEmbeddedBrowserAuthPort { viewController }

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

    /**
     * The one TDLib session of this host, created once the account store exists, because its admission is that store's
     * native Telegram account. Null until settings are ready.
     */
    val telegram: StateFlow<IosTelegramClient?> = telegramSession.asStateFlow()
    private var closed = false

    init {
        scope.launch { openSettingsAndPack() }
    }

    /** Prepared provider audio enters playback only through the iOS admission rules in [IosPlaybackPort]. */
    fun loadPrepared(
        occurrenceId: String,
        mediaId: String,
        prepared: PreparedAudio,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
    ) {
        playback.submitPrepared(occurrenceId, mediaId, prepared, startPositionMs, playWhenReady)
    }

    /**
     * Supplies the account store that personal progressive, native and TIDAL audio admit against. The root calls it once
     * the vault is ready, with the single store it owns for the session, so no default or dummy store is ever used. Until
     * then that audio is refused, and the anonymous progressive and Deezer paths are unaffected.
     */
    fun attachAccounts(accounts: AcceptedAccountReader, cookies: ScopedCookieSource) {
        check(!closed)
        playback.attachAccounts(accounts, cookies)
    }

    /**
     * Forwarded by the Swift app session on each real activation. The pack spends its single per-process
     * consideration here; a call before settings are ready is remembered and delivered once they are.
     */
    fun foregrounded() {
        if (closed) return
        foregroundRequested = true
        val pack = japanesePack.value ?: return
        scope.launch { pack.onForeground() }
    }

    /**
     * Called once by the authoritative shared queue/repository composition, not by a demo queue. Single-shot per
     * host: a second call fails rather than registering duplicate remote-command targets.
     */
    fun installSystemControls(
        metadata: (occurrenceId: String) -> ContentItem?,
        next: () -> Unit,
        previous: () -> Unit,
    ): IosNowPlaying {
        check(!closed && nowPlaying == null)
        return IosNowPlaying(player, metadata, next, previous).also { nowPlaying = it }
    }

    /** Final process shutdown, called only by the Swift app session at willTerminate. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
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
        // Teardown outlives the cancelled host scope: the pack joins its worker, then the settings owner is released.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            session?.close()
            pack?.close()
            opened?.close()
        }
    }

    /**
     * Builds the Telegram session over [accounts] and hands it to playback. A TDLib database left by an earlier sign-in
     * restores its session at once, so Telegram tracks in a restored queue can play before settings are opened. Without
     * a build identity the session stays not configured, and a failure here leaves Telegram absent, never the host.
     */
    private fun openTelegram(accounts: VaultProviderAccountStore) {
        val session = try {
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
                log = { message -> println("ArchiveTune iOS: $message") },
            ).also { if (iosTelegramHasSession(storageRoot)) it.start() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return
        playback.attachTelegram(session)
        telegramSession.value = session
    }

    private suspend fun openSettingsAndPack() {
        val opened = try {
            IosVaultSettings.open()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!closed) settingsOpen.value = IosSettingsOpenState.Unavailable
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
        openTelegram(accounts)
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
        if (foregroundRequested) pack?.onForeground()
    }
}
