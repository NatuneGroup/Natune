@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherClient
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherMessageCodec
import moe.rukamori.archivetune.shared.data.platform.ios.IosListenTogetherGzip
import moe.rukamori.archivetune.shared.data.platform.ios.IosListenTogetherPreferences
import moe.rukamori.archivetune.shared.data.platform.ios.IosListenTogetherSocketFactory
import moe.rukamori.archivetune.shared.data.platform.ios.IosListenTogetherStore
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramBotDirectory
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramBotStore
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramChannelLibrary
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramChannelSearch
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramClient
import moe.rukamori.archivetune.shared.data.telegram.TelegramBotsOwner
import moe.rukamori.archivetune.shared.data.telegram.TelegramBrowseOwner
import moe.rukamori.archivetune.shared.domain.settings.TelegramLosslessOnlyKey
import moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase
import moe.rukamori.archivetune.shared.data.persistence.RoomArtistBlockRepository
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.data.persistence.RoomListeningHistory
import moe.rukamori.archivetune.shared.data.persistence.RoomQueueCheckpointStore
import moe.rukamori.archivetune.shared.data.persistence.iosPortableStateDatabasePath
import moe.rukamori.archivetune.shared.data.persistence.openPortableStateDatabase
import moe.rukamori.archivetune.shared.data.platform.ios.IosKeychainCredentialStore
import moe.rukamori.archivetune.shared.data.platform.ios.IosPlaybackPool
import moe.rukamori.archivetune.shared.data.platform.ios.IosProviderCredentials
import moe.rukamori.archivetune.shared.data.platform.ios.iosNetworkAvailable
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.data.provider.OrderedAudioResolver
import moe.rukamori.archivetune.shared.data.provider.TidalAccountAuth
import moe.rukamori.archivetune.shared.data.repository.ContentRepositoryRouter
import moe.rukamori.archivetune.shared.data.repository.PortableQueueOwner
import moe.rukamori.archivetune.shared.data.repository.QueueHandoffState
import moe.rukamori.archivetune.shared.data.repository.TypedDelegatingQueueController
import moe.rukamori.archivetune.shared.data.repository.TypedRoomLibraryRepository
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.queue.QueueAdvancePolicy
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContinuationQueueController
import moe.rukamori.archivetune.shared.domain.settings.AutoSkipNextOnErrorKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.QueueAddAfterManualKey
import moe.rukamori.archivetune.shared.domain.settings.RepeatModeKey
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.host.HostLyrics
import moe.rukamori.archivetune.shared.ui.host.LibraryContentOwner
import moe.rukamori.archivetune.shared.ui.host.LibraryPageOwners
import moe.rukamori.archivetune.shared.ui.host.ListenHistoryDriver
import moe.rukamori.archivetune.shared.ui.host.TidalSessionRefresh
import moe.rukamori.archivetune.shared.ui.settings.ProviderAccountLifecycle
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore
import platform.Foundation.*

/**
 * The one session graph of the iOS process, built once the Keychain-backed settings are ready. It owns the portable
 * database, the HTTP client, the single queue owner, the listening history, the library owner and the lyrics owner,
 * and borrows the host's one player, playback port and account store, so no second player, vault or queue exists.
 *
 * Opening is all-or-nothing. A failed database is never replaced by memory or an empty stand-in, so the host shows its
 * unavailable boundary instead of a usable-looking empty session.
 */
internal class IosSessionGraph private constructor(
    val scope: CoroutineScope,
    val settings: VaultSettingsStore,
    val accounts: VaultProviderAccountStore,
    val accountLifecycle: ProviderAccountLifecycle,
    val tidalAuth: TidalAccountAuth,
    val playbackPool: IosPlaybackPool,
    /** The shared Listen Together client: both sanctioned servers, NSURLSessionWebSocketTask transport, zlib gzip. */
    val listenTogether: ListenTogetherClient,
    /** The signed-in TDLib session, or none; the browse and bot owners below bind to it. */
    val telegram: StateFlow<IosTelegramClient?>,
    val http: HttpClient,
    val player: MusicPlayerController,
    val queue: TypedDelegatingQueueController,
    val libraryContent: LibraryContentOwner,
    val pageOwners: LibraryPageOwners,
    val lyrics: HostLyrics,
    val artworkLoader: IosArtworkLoader,
    private val owner: PortableQueueOwner,
    private val database: PortableStateDatabase,
    private val listenDriver: ListenHistoryDriver,
    private val drivers: List<Job>,
    private val reportLine: (String) -> Unit,
) {
    /** The crossfade or automix blend armed into the next song. iOS connects no blend engine, so it stays null. */
    val handoff: StateFlow<QueueHandoffState?> get() = owner.handoff

    fun report(message: String) = reportLine(message)

    /**
     * The playback error dialog's Retry: loads the failed occurrence again at the position the player kept for it. An
     * occurrence that has left the queue is logged and not retried.
     */
    fun retryAfterFailure(occurrenceId: String) {
        scope.launch {
            try {
                val position = player.positionMs()
                    ?.takeIf { player.snapshot.value?.occurrenceId == occurrenceId }
                    ?.coerceAtLeast(0L) ?: 0L
                queue.select(occurrenceId, position)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("playback retry failed: ${failure::class.simpleName}")
            }
        }
    }

    /** Advances to the successor under the same repeat policy as an automatic end. False when the queue has none. */
    suspend fun skipNext(): Boolean = owner.withQueueAccess {
        val state = owner.state.value
        val current = state.currentOccurrenceId ?: return@withQueueAccess false
        val target = QueueAdvancePolicy.successor(iosRepeatMode(settings.snapshots.value), state.playOrder, current)
            ?: return@withQueueAccess false
        owner.select(target, 0L)
        true
    }

    /** Restarts the current occurrence after a few seconds of play, otherwise moves to its predecessor in play order. */
    suspend fun skipPrevious(): Boolean {
        if ((player.positionMs() ?: 0L) > RESTART_THRESHOLD_MS) {
            player.seekTo(0L)
            return true
        }
        return owner.withQueueAccess {
            val state = owner.state.value
            val current = state.currentOccurrenceId ?: return@withQueueAccess false
            val index = state.playOrder.indexOf(current)
            if (index <= 0) return@withQueueAccess false
            owner.select(state.playOrder[index - 1], 0L)
            true
        }
    }

    /** The item of [occurrenceId] in the queue, for the lock screen and Control Center. */
    fun metadata(occurrenceId: String): ContentItem? =
        queue.state.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item

    /**
     * Final shutdown. The open listen is finished first so its play time is kept, then the queue is checkpointed while
     * the owner can still write, and only then are the database and the client released. The host's player, port and
     * settings belong to the host, which closes them afterwards.
     */
    suspend fun close() = withContext(NonCancellable) {
        listenDriver.close()
        lyrics.close()
        drivers.forEach { it.cancel() }
        checkpointNow(queue, reportLine)
        owner.close()
        http.close()
        database.close()
        scope.cancel()
    }

    /**
     * A channel-browse owner bound to a running TDLib session. Channel search is live; opening a channel reports the
     * honest failure until an `LPtg` playlist/sync engine exists on this host.
     */
    fun telegramBrowse(client: IosTelegramClient): TelegramBrowseOwner =
        TelegramBrowseOwner(
            channelSearch = IosTelegramChannelSearch(client),
            channelLibrary = IosTelegramChannelLibrary(),
            losslessOnly = { settings.snapshots.value?.get(TelegramLosslessOnlyKey) == true },
            scope = scope,
        )

    /** A bot-management owner bound to a running TDLib session and the vault-backed bot list. */
    fun telegramBots(client: IosTelegramClient, ready: StateFlow<Boolean>): TelegramBotsOwner =
        TelegramBotsOwner(
            store = IosTelegramBotStore(settings),
            directory = IosTelegramBotDirectory(client),
            ready = ready,
            scope = scope,
            nowMs = ::iosNowMillis,
            newId = { NSUUID().UUIDString() },
        )

    companion object {
        /** The position past which a previous skip restarts the current occurrence instead of stepping back. */
        const val RESTART_THRESHOLD_MS = 3_000L
        private const val POSITION_CHECKPOINT_INTERVAL_MS = 10_000L

        /**
         * Opens the database, then builds the owners over it and the host's settings, accounts and player. A failure
         * releases what was opened and is rethrown. A saved queue that cannot be restored stays on disk.
         */
        suspend fun open(
            host: IosHost,
            settings: VaultSettingsStore,
            accounts: VaultProviderAccountStore,
            report: (String) -> Unit,
        ): IosSessionGraph {
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, failure ->
                    report("background command failed: ${failure::class.simpleName}")
                },
            )
            val database = try {
                withContext(Dispatchers.Default) { openPortableStateDatabase(iosPortableStateDatabasePath()) }
            } catch (failure: Throwable) {
                scope.cancel()
                throw failure
            }
            val http = HttpClient(Darwin)
            return try {
                assemble(host, scope, settings, accounts, database, http, report)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    http.close()
                    database.close()
                }
                scope.cancel()
                throw failure
            }
        }

        private suspend fun assemble(
            host: IosHost,
            scope: CoroutineScope,
            vault: VaultSettingsStore,
            accounts: VaultProviderAccountStore,
            database: PortableStateDatabase,
            http: HttpClient,
            report: (String) -> Unit,
        ): IosSessionGraph {
            val accountLifecycle = ProviderAccountLifecycle(accounts, vault)
            // A TIDAL client identity comes from the ARCHIVETUNE_TIDAL_* build settings; without one the device and
            // browser sign-ins report themselves unavailable, and a pasted refresh token is still verified with TIDAL.
            val tidalAuth = TidalAccountAuth(
                http,
                device = IosProviderCredentials.tidalDeviceClient,
                pkce = IosProviderCredentials.tidalPkceClient,
                nowMs = ::iosNowMillis,
            )
            val tidalSessionRefresh = TidalSessionRefresh(
                tidalAuth, accountLifecycle, vault, preferences = { vault.snapshots.value }, nowMs = ::iosNowMillis,
            )
            val playbackPool = IosPlaybackPool(http, IosKeychainCredentialStore(), { vault.snapshots.value }, scope, report)
            // The shared client on its own serial thread, over the NSURLSessionWebSocketTask transport. iOS publishes
            // no network-change events to it; a dropped socket still reaches the client's retry rules.
            val listenTogether = ListenTogetherClient(
                scope = CoroutineScope(
                    scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default.limitedParallelism(1),
                ),
                sockets = IosListenTogetherSocketFactory(
                    NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration),
                ),
                codec = ListenTogetherMessageCodec(gzip = IosListenTogetherGzip()),
                preferences = IosListenTogetherPreferences(vault),
                store = IosListenTogetherStore(vault, report),
                network = MutableStateFlow(iosNetworkAvailable()),
                nowMs = ::iosNowMillis,
            )
            val library = RoomLibraryRepository(database.library(), listens = database.listens())
            val artistBlocks = RoomArtistBlockRepository(database.artistBlocks(), nowMs = ::iosNowMillis)
            val history = RoomListeningHistory(database.listens(), offsetMsAt = ::iosOffsetMsAt)
            val player = host.player
            val locators = IosQueueLocatorResolver(
                audio = OrderedAudioResolver(
                    providers = iosAudioProviders(http, accounts, { vault.snapshots.value }, tidalSessionRefresh, playbackPool),
                    youtube = IosYouTubeUnavailable,
                ),
                port = host.playbackPort,
                preferences = { vault.snapshots.value },
            )
            val owner = PortableQueueOwner(
                player = player,
                scope = scope,
                content = ContentRepositoryRouter(listOf(TypedRoomLibraryRepository(library))),
                locators = locators,
                keepManualQueueInOrder = vault.snapshot()[QueueAddAfterManualKey] ?: true,
                repeatMode = { iosRepeatMode(vault.snapshots.value) },
                autoSkipNextOnError = { vault.snapshots.value?.get(AutoSkipNextOnErrorKey) ?: false },
            )
            val queue = TypedDelegatingQueueController(owner, RoomQueueCheckpointStore(database.checkpoints()))
            try {
                queue.resume()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("saved queue was not restored: ${failure::class.simpleName}")
            }
            val listenDriver = ListenHistoryDriver(
                scope = scope,
                player = player,
                queue = queue.state,
                history = history,
                preferences = vault.snapshots,
                report = report,
                nowMs = ::iosNowMillis,
            )
            val lyrics = HostLyrics(
                scope = scope,
                http = http,
                settings = vault,
                player = player,
                queue = queue.state,
                networkAvailable = { iosNetworkAvailable() },
                report = report,
            )
            val libraryContent = LibraryContentOwner(
                settings = vault,
                library = library,
                // No YouTube account binding exists on this host, so every library change stays local.
                writes = library,
                statistics = history,
                blocked = artistBlocks.observeBlockedArtists(),
                textOrder = iosTextOrder,
                nowMs = ::iosNowMillis,
                offsetMsAt = ::iosOffsetMsAt,
                report = report,
            )
            val pageOwners = LibraryPageOwners(
                content = libraryContent,
                settings = vault,
                queue = queue,
                history = history,
                blocked = artistBlocks.observeBlockedArtists(),
                scope = scope,
                report = report,
            )
            val drivers = listOf(
                scope.launch {
                    owner.automaticAdvanceFailures.collect { report("automatic advance failed: ${it::class.simpleName}") }
                },
                scope.launch {
                    // The owner's failures include songs no source resolved, which never reach the engine.
                    owner.failures.collect { failure ->
                        report("playback failed for ${failure.occurrenceId}: ${failure.reason}")
                        // With auto-skip on the queue moves on, so no stopped song needs the error dialog.
                        if (vault.snapshots.value?.get(AutoSkipNextOnErrorKey) != true) host.showPlaybackFailure(failure)
                    }
                },
                scope.launch {
                    queue.state.map { it.revision }.distinctUntilChanged().collect { revision ->
                        if (revision > 0L) checkpointNow(queue, report)
                    }
                },
                scope.launch {
                    while (isActive) {
                        delay(POSITION_CHECKPOINT_INTERVAL_MS)
                        if (player.snapshot.value?.playWhenReady == true) checkpointNow(queue, report)
                    }
                },
            )
            val graph = IosSessionGraph(
                scope = scope,
                settings = vault,
                accounts = accounts,
                accountLifecycle = accountLifecycle,
                tidalAuth = tidalAuth,
                http = http,
                player = player,
                queue = queue,
                libraryContent = libraryContent,
                pageOwners = pageOwners,
                lyrics = lyrics,
                artworkLoader = IosArtworkLoader(http),
                playbackPool = playbackPool,
                listenTogether = listenTogether,
                telegram = host.telegram,
                owner = owner,
                database = database,
                listenDriver = listenDriver,
                drivers = drivers,
                reportLine = report,
            )
            lyrics.start()
            playbackPool.start()
            return graph
        }
    }
}

internal fun iosRepeatMode(preferences: PreferenceSnapshot?): QueueRepeatMode =
    preferences?.get(RepeatModeKey)?.let { QueueRepeatMode.fromStoredValue(it) } ?: QueueRepeatMode.OFF

/** The local time zone offset at [epochMs], for the history day boundaries and the My Top windows. */
internal fun iosOffsetMsAt(epochMs: Long): Long =
    NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)) * 1000L

/**
 * The text order the portable library sorts with: the user's locale, case-insensitive, so one artist never splits into
 * two groups by case.
 */
private val iosTextOrder: Comparator<String> = Comparator { first, second ->
    NSString.create(string = first).localizedCaseInsensitiveCompare(second).toInt()
}

/** One queue checkpoint. A failure is logged and never escapes into playback or shutdown. */
private suspend fun checkpointNow(queue: ContinuationQueueController, report: (String) -> Unit) {
    try {
        queue.checkpoint()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        report("queue checkpoint failed: ${failure::class.simpleName}")
    }
}
