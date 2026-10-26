package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.File
import java.nio.file.Path
import java.text.Collator
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.backup.DesktopBackup
import moe.rukamori.archivetune.desktop.backup.DesktopPendingRestore
import moe.rukamori.archivetune.desktop.integration.DesktopMediaMetadata
import moe.rukamori.archivetune.desktop.integration.auth.DesktopSecureCredentialStore
import moe.rukamori.archivetune.desktop.playback.DesktopPlaybackPort
import moe.rukamori.archivetune.desktop.playback.NativeStreamLease
import moe.rukamori.archivetune.desktop.playback.TelegramFileBinding
import moe.rukamori.archivetune.desktop.provider.DesktopPlaybackPool
import moe.rukamori.archivetune.desktop.provider.DesktopYouTubeResolver
import moe.rukamori.archivetune.desktop.provider.youTubeSignIn
import moe.rukamori.archivetune.desktop.telegram.TdlightClientSession
import moe.rukamori.archivetune.desktop.telegram.TelegramNativeAccountPort
import moe.rukamori.archivetune.shared.data.customartwork.FileCustomArtworkRepository
import moe.rukamori.archivetune.shared.data.customartwork.JvmCustomArtworkFiles
import moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase
import moe.rukamori.archivetune.shared.data.persistence.RoomArtistBlockRepository
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.data.persistence.RoomListeningHistory
import moe.rukamori.archivetune.shared.data.persistence.RoomPlaylistTagsRepository
import moe.rukamori.archivetune.shared.data.playlistexport.PlaylistExportOwner
import moe.rukamori.archivetune.shared.data.playlistexport.RoomPlaylistExportSource
import moe.rukamori.archivetune.shared.data.persistence.RoomQueueCheckpointStore
import moe.rukamori.archivetune.shared.data.persistence.openNow
import moe.rukamori.archivetune.shared.data.persistence.openPortableStateDatabase
import moe.rukamori.archivetune.shared.data.persistence.snapshotTo
import moe.rukamori.archivetune.shared.data.provider.JvmYouTubeBrowseTransport
import moe.rukamori.archivetune.shared.data.provider.OrderedAudioResolver
import moe.rukamori.archivetune.shared.data.provider.TidalAccountAuth
import moe.rukamori.archivetune.shared.data.provider.YouTubeAccountBinding
import moe.rukamori.archivetune.shared.data.provider.YouTubeContentRepository
import moe.rukamori.archivetune.shared.data.repository.ContentRepositoryRouter
import moe.rukamori.archivetune.shared.data.repository.PortableQueueOwner
import moe.rukamori.archivetune.shared.data.repository.QueueHandoffState
import moe.rukamori.archivetune.shared.data.repository.TypedDelegatingQueueController
import moe.rukamori.archivetune.shared.data.repository.TypedRoomLibraryRepository
import moe.rukamori.archivetune.shared.data.repository.YouTubeSettlingLibrary
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkFiles
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagsRepository
import moe.rukamori.archivetune.shared.domain.queue.QueueAdvancePolicy
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContinuationQueueController
import moe.rukamori.archivetune.shared.domain.repository.CustomArtworkRepository
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.AutoSkipNextOnErrorKey
import moe.rukamori.archivetune.shared.domain.settings.DataSyncIdKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeCookieKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerVolumeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.QueueAddAfterManualKey
import moe.rukamori.archivetune.shared.domain.settings.RepeatModeKey
import moe.rukamori.archivetune.shared.domain.settings.UseLoginForBrowse
import moe.rukamori.archivetune.shared.domain.settings.VisitorDataKey
import moe.rukamori.archivetune.shared.domain.settings.YtmSyncKey
import moe.rukamori.archivetune.shared.ui.host.CatalogPageOwners
import moe.rukamori.archivetune.shared.ui.host.HostLyrics
import moe.rukamori.archivetune.shared.ui.host.LibraryContentOwner
import moe.rukamori.archivetune.shared.ui.host.LibraryPageOwners
import moe.rukamori.archivetune.shared.ui.host.ListenHistoryDriver
import moe.rukamori.archivetune.shared.ui.host.TidalSessionRefresh
import moe.rukamori.archivetune.shared.ui.settings.DesktopSettingsStore
import moe.rukamori.archivetune.shared.ui.settings.ProviderAccountLifecycle
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore
import moe.rukamori.archivetune.shared.ui.settings.providerAccountScope

/**
 * The one production session graph of the desktop process. It owns the ordinary settings file, the platform vault
 * routes, the portable database, the playback port, the single queue owner and the listening history, and it is the
 * only place that wires them together. Window and root services borrow it; none of them creates a second queue,
 * clock, settings file, vault or database.
 *
 * Opening is all-or-nothing. A failed vault or database is never replaced by memory, a plaintext file or an empty
 * stand-in, so the caller shows the unavailable boundary instead of a usable-looking empty session.
 */
class DesktopSessionGraph private constructor(
    /** Shared lifetime of every owner here. Cancelled only by [close], after each owner has released its resources. */
    val scope: CoroutineScope,
    /** The one logical settings view. Credential values are reached only through its vault routes. */
    val settings: VaultSettingsStore,
    /** The one provider account owner, over [settings]. Provider consumers reach accounts only through it. */
    val accounts: VaultProviderAccountStore,
    /** The one personal sign-in lifecycle over [accounts]. Settings sign-ins and the TIDAL refresh commit through it. */
    internal val accountLifecycle: ProviderAccountLifecycle,
    /** The TIDAL account endpoints, with the device and PKCE clients this build or launch supplies. */
    internal val tidalAuth: TidalAccountAuth,
    /**
     * The one TDLight session of this process. Its admitted account is the store's native Telegram account, and playback
     * reads Telegram files only through it.
     */
    internal val telegram: TdlightClientSession,
    val library: RoomLibraryRepository,
    val artistBlocks: RoomArtistBlockRepository,
    val history: RoomListeningHistory,
    /** The one player. Its snapshot is the only playback clock the queue, the metadata and the listen driver read. */
    val player: MusicPlayerController,
    /** The one checkpointed queue facade the root binds to. Every queue command runs inside the owner's lock. */
    val queue: TypedDelegatingQueueController,
    /** Lifetime-bound projection of the queue and the player for the OS media controls. Never a second queue. */
    val metadata: StateFlow<DesktopMediaMetadata?>,
    /** The one device collator every sorted list of this host uses, so one artist never splits into two groups. */
    val textOrder: Comparator<String>,
    /** The one owner of the Library sections and playlist orders, read through [settings] and [library]. */
    internal val libraryContent: LibraryContentOwner,
    private val owner: PortableQueueOwner,
    /** The engine behind [player], reached directly only for the output volume, which is not a queue command. */
    private val playback: DesktopPlaybackPort,
    private val ordinarySettings: DesktopSettingsStore,
    private val database: PortableStateDatabase,
    private val listenDriver: ListenHistoryDriver,
    /** The one scrobbling owner: the signed Last.fm-compatible service, its pending queue and the playback driver. */
    internal val lastFm: DesktopLastFm,
    /** The one lyrics owner: the shared provider runtime following the player's current occurrence. */
    internal val lyrics: HostLyrics,
    /** The one listener-presence owner: the opt-in document and the playback-driven heartbeat loop. */
    internal val listenerPresence: DesktopListenerPresence,
    private val locators: DesktopQueueLocatorResolver,
    /** The one YouTube catalogue the queue router reads. Host searches go through it, over the same transport and account. */
    internal val youtubeContent: YouTubeContentRepository,
    /** The user's custom covers and canvases, in the override folders of the instance directory. */
    internal val customArtwork: CustomArtworkRepository,
    private val customArtworkFiles: CustomArtworkFiles,
    /**
     * The one HTTP client the source providers and the settings sign-ins share. It is closed after the queue stops
     * asking for sources.
     */
    internal val http: HttpClient,
    /** The one Playback Pool client: the ArchivePool account feed, its vault cache and the community instance list. */
    internal val playbackPool: DesktopPlaybackPool,
    /** Pulls the signed-in YouTube Music library into [library]; the account settings force it after a switch. */
    internal val youtubeLibrarySync: DesktopYouTubeLibrarySync,
    /** The one Listen Together owner: the room client and the driver that keeps [player] in step with the room. */
    internal val listenTogether: DesktopListenTogether,
    /** The opt-in feedback service, the stored crash report and the playback failure the player last stopped on. */
    internal val feedback: DesktopFeedback,
    /** The music video behind the player cover, through the same YouTube resolver the queue plays from. */
    internal val videoArtwork: DesktopVideoArtwork,
    /** Manual and Google Drive backups, and the restore staged for the next start. */
    internal val backup: DesktopBackup,
    private val drivers: List<Job>,
) {
    /** The playlist tag catalog and assignments, in the same portable file as the playlists. */
    internal val playlistTags: PlaylistTagsRepository = RoomPlaylistTagsRepository(database.library())

    /** The playlist file exports, read from the same portable file. */
    internal val playlistExport = PlaylistExportOwner(RoomPlaylistExportSource(database.library()))

    /** The owners the shared Library and playlist pages read and write: the same library, queue and history as above. */
    internal val pageOwners = LibraryPageOwners(
        content = libraryContent,
        settings = settings,
        queue = queue,
        history = history,
        blocked = artistBlocks.observeBlockedArtists(),
        scope = scope,
        report = { message -> desktopDiagnostic(message) },
    )

    /** Search, albums and finite playlists over the same YouTube catalogue the queue router reads. */
    internal val catalogPages = CatalogPageOwners(youtubeContent, pageOwners)

    /** The one artwork loader of the window slots. It fetches over the same client as the sources and holds no other resource. */
    internal val artworkLoader = DesktopArtworkLoader(http, customArtwork, customArtworkFiles)

    /** The one sleep timer of this process. It only pauses [player], so every window shares it. */
    internal val sleepTimer = DesktopSleepTimer(this)

    /** The crossfade or automix blend armed into the next song, which the player artwork follows. */
    internal val handoff: StateFlow<QueueHandoffState?> get() = owner.handoff

    /**
     * Sets the output volume, 0 to 1, on the engine and keeps it in [PlayerVolumeKey] as the Android player does, so the
     * next session starts at the same level. A failed save is logged; the engine keeps the new level either way.
     */
    fun setVolume(volume: Float) {
        val level = if (volume.isFinite()) volume.coerceIn(0f, 1f) else return
        playback.setVolume(level)
        scope.launch {
            try {
                settings.edit { set(PlayerVolumeKey, level) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                desktopDiagnostic("volume was not saved: ${failure.javaClass.simpleName}")
            }
        }
    }

    /** Writes one sanitized diagnostic line to the local log. Host diagnostics are not telemetry. */
    fun report(message: String) = desktopDiagnostic(message)

    /**
     * The playback error dialog's Retry: loads the failed occurrence again at the position the player kept for it, as
     * the Android dialog prepares the player again. An occurrence that has left the queue is logged and not retried.
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
                desktopDiagnostic("playback retry failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    /** Advances to the successor under the same repeat policy as an automatic end. False when the queue has none. */
    suspend fun skipNext(): Boolean = owner.withQueueAccess {
        val state = owner.state.value
        val current = state.currentOccurrenceId ?: return@withQueueAccess false
        val target = QueueAdvancePolicy.successor(repeatModeOf(settings.snapshots.value), state.playOrder, current)
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

    /**
     * Final shutdown, after the window has stopped asking for work. The open listen is finished first so its play time
     * is kept, then the queue is checkpointed while the owner can still write, and only then are the stores released.
     */
    suspend fun close() = withContext(NonCancellable) {
        listenTogether.close()
        listenDriver.close()
        lastFm.close()
        lyrics.close()
        listenerPresence.close()
        drivers.forEach { it.cancel() }
        checkpointNow(queue)
        owner.close()
        locators.close()
        telegram.close()
        http.close()
        player.close()
        settings.close()
        ordinarySettings.close()
        database.close()
        scope.cancel()
    }

    companion object {
        private const val SETTINGS_FILE = "settings.preferences"
        private const val DATABASE_FILE = "portable-state.db"
        private const val TELEGRAM_DIRECTORY = "telegram"
        /** The position past which a previous skip restarts the current occurrence instead of stepping back. */
        internal const val RESTART_THRESHOLD_MS = 3_000L
        private const val POSITION_CHECKPOINT_INTERVAL_MS = 10_000L

        /**
         * Opens the stores for [instanceDirectory], then builds the owners over them in dependency order. A failure closes
         * the stores already opened and cancels the scope, which stops every collector started so far, and is then
         * rethrown. A saved queue that cannot be restored stays on disk: the empty queue it leaves behind never
         * overwrites that checkpoint.
         */
        suspend fun open(instanceDirectory: Path): DesktopSessionGraph {
            val directory = instanceDirectory.toAbsolutePath().normalize()
            // A transport command or driver that fails with nothing left to catch it is logged by class name only, so no
            // exception message, path or payload reaches the local log through the default handler's stack trace.
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, failure ->
                    desktopDiagnostic("background command failed: ${failure.javaClass.simpleName}")
                },
            )
            // A restore staged by the previous process goes in before any owner opens the files it replaces.
            val pending = stagedRestore(directory)
            val ordinary = try {
                DesktopSettingsStore.open(directory.resolve(SETTINGS_FILE))
            } catch (failure: Throwable) {
                scope.cancel()
                throw failure
            }
            val vault = try {
                VaultSettingsStore.open(
                    ordinary = ordinary,
                    vault = DesktopSecureCredentialStore(),
                    newReference = ::newCredentialReference,
                )
            } catch (failure: Throwable) {
                withContext(NonCancellable) { ordinary.close() }
                scope.cancel()
                throw failure
            }
            if (pending != null) {
                try {
                    pending.applySettings(vault)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    desktopDiagnostic("restored settings not applied: ${failure.javaClass.simpleName}")
                }
            }
            val database = try {
                openDatabase(directory, pending)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    vault.close()
                    ordinary.close()
                }
                scope.cancel()
                throw failure
            }
            // The engine starts lazily, so building the client opens no connection. Every failure path below closes it.
            val http = HttpClient(CIO)
            return try {
                assemble(scope, directory, ordinary, vault, database, http).also {
                    if (pending != null) {
                        try {
                            withContext(Dispatchers.IO) { pending.finish() }
                        } catch (failure: Exception) {
                            desktopDiagnostic("restore staging not removed: ${failure.javaClass.simpleName}")
                        }
                    }
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    http.close()
                    database.close()
                    vault.close()
                    ordinary.close()
                }
                scope.cancel()
                throw failure
            }
        }

        /** The staged restore with its library file already in place, or null. A failed swap is undone and logged. */
        private fun stagedRestore(directory: Path): DesktopPendingRestore? {
            val pending = try {
                DesktopPendingRestore.read(directory)
            } catch (failure: Exception) {
                desktopDiagnostic("staged restore unreadable: ${failure.javaClass.simpleName}")
                return null
            } ?: return null
            try {
                pending.swapDatabase(directory, DATABASE_FILE)
            } catch (failure: Exception) {
                desktopDiagnostic("restored library not applied: ${failure.javaClass.simpleName}")
                try {
                    pending.rollbackDatabase(directory, DATABASE_FILE)
                } catch (rollback: Exception) {
                    desktopDiagnostic("library rollback failed: ${rollback.javaClass.simpleName}")
                }
            }
            return pending
        }

        /**
         * Opens the portable database. A restored file is opened at once, so a schema Room refuses puts the previous
         * file back instead of leaving the session unavailable.
         */
        private suspend fun openDatabase(directory: Path, pending: DesktopPendingRestore?): PortableStateDatabase {
            val path = directory.resolve(DATABASE_FILE).toString()
            val database = openPortableStateDatabase(path)
            if (pending?.databaseSwapped != true) return database
            try {
                database.openNow()
                return database
            } catch (cancelled: CancellationException) {
                database.close()
                throw cancelled
            } catch (failure: Exception) {
                desktopDiagnostic("restored library could not be opened: ${failure.javaClass.simpleName}")
                database.close()
                withContext(Dispatchers.IO) { pending.rollbackDatabase(directory, DATABASE_FILE) }
                return openPortableStateDatabase(path)
            }
        }

        /**
         * Builds the owners over the stores that [open] has already opened. A failure here is rethrown to [open], which
         * releases those stores before the caller sees it.
         */
        private suspend fun assemble(
            scope: CoroutineScope,
            directory: Path,
            ordinary: DesktopSettingsStore,
            vault: VaultSettingsStore,
            database: PortableStateDatabase,
            http: HttpClient,
        ): DesktopSessionGraph {
            val accounts = VaultProviderAccountStore(vault)
            val accountLifecycle = ProviderAccountLifecycle(accounts, vault)
            val tidalAuth = TidalAccountAuth(
                http,
                device = DesktopCredentials.tidalDeviceClient,
                pkce = DesktopCredentials.tidalPkceClient,
                nowMs = System::currentTimeMillis,
            )
            val tidalSessionRefresh = TidalSessionRefresh(tidalAuth, accountLifecycle, vault, preferences = { vault.snapshots.value })
            val textOrder = deviceTextOrder()
            val library = RoomLibraryRepository(database.library(), listens = database.listens())
            val artistBlocks = RoomArtistBlockRepository(database.artistBlocks(), nowMs = { System.currentTimeMillis() })
            val history = RoomListeningHistory(
                database.listens(),
                offsetMsAt = { epochMs -> TimeZone.getDefault().getOffset(epochMs).toLong() },
            )
            // One port reads the accounts and cookies the settings UI writes, so every admission sees the same owner.
            val playback = DesktopPlaybackPort(accounts = accounts, cookies = accounts)
            vault.snapshots.value?.get(PlayerVolumeKey)?.takeIf { it.isFinite() }?.let { playback.setVolume(it.coerceIn(0f, 1f)) }
            val player = MusicPlayerController(playback, scope)
            val telegramRoot = directory.resolve(TELEGRAM_DIRECTORY).toFile()
            val telegram = TdlightClientSession(
                credentials = DesktopCredentials.telegramApi,
                storageRoot = telegramRoot,
                applicationVersion = DesktopSessionGraph::class.java.`package`?.implementationVersion ?: "1.0.0",
                cancelRetainedDownloads = { NativeStreamLease.cancelTelegramTransfers() },
                nativeAccount = TelegramNativeAccountPort(
                    beginAttempt = accounts::beginNativeTelegramAttempt,
                    acceptSession = accounts::acceptNativeTelegramSession,
                    revokeSession = accounts::revokeNativeTelegramSession,
                ),
                log = ::desktopDiagnostic,
            )
            playback.bindTelegramFiles {
                telegram.fileOperations()?.let { operations ->
                    TelegramFileBinding(operations, accounts, CredentialScope.PERSONAL.name)
                }
            }
            // One YouTube transport and one account binding serve the browse, account and like paths. A remote change acts
            // for the personal sign-in only. Sync off, or no sign-in, keeps every change local.
            val youtubeTransport = JvmYouTubeBrowseTransport()
            youtubeTransport.applyPersistedSession(vault.snapshots.value)
            val youtubeAccount = YouTubeAccountBinding(
                transport = youtubeTransport,
                accounts = accounts,
                personalScope = { providerAccountScope(ContentProvider.YOUTUBE.id, CredentialScope.PERSONAL) },
                syncEnabled = { vault.snapshots.value?.get(YtmSyncKey) ?: true },
            )
            // The pool cache has its own vault entry, so pooled accounts never overwrite a personal sign-in.
            val playbackPool = DesktopPlaybackPool(
                http = http,
                vault = DesktopSecureCredentialStore(),
                preferences = { vault.snapshots.value },
                scope = scope,
                log = ::desktopDiagnostic,
            )
            val youtubeResolver = DesktopYouTubeResolver(
                http,
                preferences = { vault.snapshots.value },
                search = youtubeTransport,
                signIn = { vault.snapshots.value.youTubeSignIn() },
            )
            val locators = DesktopQueueLocatorResolver(
                audio = OrderedAudioResolver(
                    providers = desktopAudioProviders(http, accounts, { vault.snapshots.value }, tidalSessionRefresh, playbackPool),
                    youtube = youtubeResolver,
                ),
                port = playback,
                preferences = { vault.snapshots.value },
            )
            val youtubeContent = YouTubeContentRepository(youtubeTransport, youtubeAccount)
            val youtubeLibrarySync = DesktopYouTubeLibrarySync(
                content = youtubeContent,
                account = youtubeAccount,
                library = library,
                beforeSync = { youtubeTransport.applyPersistedSession(vault.snapshots.value) },
                report = ::desktopDiagnostic,
            )
            // Set once Listen Together and the sleep timer exist; until then nothing holds the transitions back.
            val handoffHold = MutableStateFlow(false)
            val owner = PortableQueueOwner(
                player = player,
                scope = scope,
                content = ContentRepositoryRouter(listOf(TypedRoomLibraryRepository(library), youtubeContent)),
                locators = locators,
                keepManualQueueInOrder = vault.snapshot()[QueueAddAfterManualKey] ?: true,
                repeatMode = { repeatModeOf(vault.snapshots.value) },
                autoSkipNextOnError = { vault.snapshots.value?.get(AutoSkipNextOnErrorKey) ?: false },
                handoffEngine = DesktopCrossfadeHandoff(playback, vault.snapshots, handoffHold),
            )
            val queue = TypedDelegatingQueueController(owner, RoomQueueCheckpointStore(database.checkpoints()))
            try {
                queue.resume()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                desktopDiagnostic("saved queue was not restored: ${failure.javaClass.simpleName}")
            }
            val metadata = combine(queue.state, player.snapshot) { state, snapshot ->
                state.metadataFor(snapshot?.occurrenceId, repeatModeOf(vault.snapshots.value))
            }.stateIn(scope, SharingStarted.Eagerly, null)
            val listenDriver = ListenHistoryDriver(
                scope = scope,
                player = player,
                queue = queue.state,
                history = history,
                preferences = vault.snapshots,
                report = ::desktopDiagnostic,
            )
            val lastFm = DesktopLastFm(
                scope = scope,
                http = http,
                settings = vault,
                player = player,
                queue = queue.state,
                instanceDirectory = directory,
                report = ::desktopDiagnostic,
            )
            // The JVM publishes no network events; a lookup offline simply misses, and a cached answer still shows.
            val lyrics = HostLyrics(
                scope = scope,
                http = http,
                settings = vault,
                player = player,
                queue = queue.state,
                networkAvailable = { true },
                report = ::desktopDiagnostic,
            )
            // Built after the settings owner is open, so the restored room session and blocked list read real values.
            val listenTogether = DesktopListenTogether(
                parentScope = scope,
                settings = vault,
                player = player,
                queue = queue,
                // A host's volume is applied to the engine only, so the user's own saved level is not overwritten.
                setVolume = { level -> if (level.isFinite()) playback.setVolume(level.coerceIn(0f, 1f)) },
                report = ::desktopDiagnostic,
            )
            val listenerPresence = DesktopListenerPresence(
                parentScope = scope,
                http = http,
                player = player,
                instanceDirectory = directory,
            )
            val feedback = DesktopFeedback(http, directory)
            val drivers = listOf(
                scope.launch {
                    // The core client follows every change to the sign-in. A new sign-in, including one restored at
                    // start, pulls the account library once with sync on, as the baseline home screen does.
                    var wasSignedIn = false
                    vault.snapshots.map { it.youTubeSessionFields() }.distinctUntilChanged().collect {
                        val snapshot = vault.snapshots.value
                        youtubeTransport.applyPersistedSession(snapshot)
                        val signedIn = snapshot.youTubeSignIn() != null
                        if (signedIn && !wasSignedIn && (snapshot?.get(YtmSyncKey) ?: true)) {
                            launch { youtubeLibrarySync.performFullSync() }
                        }
                        wasSignedIn = signedIn
                    }
                },
                scope.launch {
                    owner.automaticAdvanceFailures.collect { desktopDiagnostic("automatic advance failed: ${it.javaClass.simpleName}") }
                },
                scope.launch {
                    // The owner's failures include songs no source resolved, which never reach the engine.
                    owner.failures.collect { failure ->
                        desktopDiagnostic("playback failed for ${failure.occurrenceId}: ${failure.reason}")
                        // With auto-skip on the queue moves on, as on Android, so no stopped song needs the error dialog.
                        if (vault.snapshots.value?.get(AutoSkipNextOnErrorKey) != true) {
                            val item = queue.state.value.entries.firstOrNull { it.occurrenceId == failure.occurrenceId }?.item
                            feedback.showPlaybackFailure(DesktopPlaybackFailureNotice(failure, item?.title, item?.ref?.provider))
                        }
                    }
                },
                scope.launch { DesktopMemoryHygiene.trimAfterSongChanges(owner.state.map { it.currentOccurrenceId }) },
                scope.launch {
                    queue.state.map { it.revision }.distinctUntilChanged().collect { revision ->
                        if (revision > 0L) checkpointNow(queue)
                    }
                },
                scope.launch {
                    while (isActive) {
                        delay(POSITION_CHECKPOINT_INTERVAL_MS)
                        if (player.snapshot.value?.playWhenReady == true) checkpointNow(queue)
                    }
                },
            )
            val libraryContent = LibraryContentOwner(
                settings = vault,
                library = library,
                writes = YouTubeSettlingLibrary(local = library, account = youtubeAccount),
                statistics = history,
                blocked = artistBlocks.observeBlockedArtists(),
                textOrder = textOrder,
                nowMs = System::currentTimeMillis,
                offsetMsAt = { epochMs -> TimeZone.getDefault().getOffset(epochMs).toLong() },
                report = ::desktopDiagnostic,
                onLikeWritten = { ref, liked ->
                    try {
                        lastFm.mirrorLove(library.item(ref), liked)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        desktopDiagnostic("Last.fm love skipped: ${failure.javaClass.simpleName}")
                    }
                },
            )
            // The override folders sit directly in the instance directory, as Android keeps them in its files directory.
            val customArtworkFiles = JvmCustomArtworkFiles(directory)
            val customArtwork = FileCustomArtworkRepository(customArtworkFiles)
            val graph = DesktopSessionGraph(
                scope = scope,
                settings = vault,
                accounts = accounts,
                accountLifecycle = accountLifecycle,
                tidalAuth = tidalAuth,
                telegram = telegram,
                library = library,
                artistBlocks = artistBlocks,
                history = history,
                player = player,
                queue = queue,
                metadata = metadata,
                textOrder = textOrder,
                libraryContent = libraryContent,
                owner = owner,
                playback = playback,
                ordinarySettings = ordinary,
                database = database,
                listenDriver = listenDriver,
                lastFm = lastFm,
                lyrics = lyrics,
                listenerPresence = listenerPresence,
                locators = locators,
                youtubeContent = youtubeContent,
                customArtwork = customArtwork,
                customArtworkFiles = customArtworkFiles,
                http = http,
                playbackPool = playbackPool,
                youtubeLibrarySync = youtubeLibrarySync,
                listenTogether = listenTogether,
                feedback = feedback,
                videoArtwork = DesktopVideoArtwork(scope, player, youtubeResolver, ::desktopDiagnostic),
                backup = DesktopBackup(
                    scope = scope,
                    http = http,
                    settings = vault,
                    snapshotDatabase = { target -> database.snapshotTo(target) },
                    instanceDirectory = directory,
                    googleClient = { DesktopCredentials.googleDesktopClient },
                    vault = DesktopSecureCredentialStore(),
                    openBrowser = { url -> openDesktopUri(url, ::desktopDiagnostic) },
                    report = ::desktopDiagnostic,
                ),
                drivers = drivers,
            )
            // A TDLib database left by an earlier sign-in restores its session, so Telegram tracks in a restored queue
            // can play without opening the settings page first. A fresh install starts no client until sign-in. The
            // client starts only once the graph exists, so a failed assembly never leaves one running.
            if (File(telegramRoot, "db").isDirectory) telegram.start()
            // A room guest needs deterministic track starts and the end-of-song timer needs the song's own end, so
            // either holds crossfade and automix back, as Android gates them on the room state.
            scope.launch {
                combine(listenTogether.client.roomState, graph.sleepTimer.state) { room, sleep ->
                    room != null || sleep.pauseWhenSongEnd
                }.distinctUntilChanged().collect { handoffHold.value = it }
            }
            // One scan before the first slot asks, so a custom cover shows on the first frame that has it.
            scope.launch {
                try {
                    customArtwork.ensureLoaded()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    desktopDiagnostic("custom artwork was not scanned: ${failure.javaClass.simpleName}")
                }
            }
            lastFm.start()
            lyrics.start()
            listenerPresence.start()
            playbackPool.start()
            listenTogether.start()
            graph.backup.start()
            return graph
        }
    }
}

/** Opaque vault references must match [A-Za-z0-9_-]{1,128}; a dashless UUID is 32 hexadecimal characters. */
private fun newCredentialReference(): String = UUID.randomUUID().toString().replace("-", "")

/** The persisted values the core YouTube client reads; any change re-applies the session. */
private fun PreferenceSnapshot?.youTubeSessionFields(): List<Any?> =
    listOf(this?.get(InnerTubeCookieKey), this?.get(VisitorDataKey), this?.get(DataSyncIdKey), this?.get(UseLoginForBrowse))

private fun JvmYouTubeBrowseTransport.applyPersistedSession(snapshot: PreferenceSnapshot?) {
    val signIn = snapshot.youTubeSignIn()
    applySession(
        cookie = signIn?.cookie,
        visitorData = snapshot?.get(VisitorDataKey),
        dataSyncId = signIn?.dataSyncId,
        useLoginForBrowse = snapshot?.get(UseLoginForBrowse) != false,
    )
}

internal fun repeatModeOf(preferences: PreferenceSnapshot?): QueueRepeatMode =
    preferences?.get(RepeatModeKey)?.let { QueueRepeatMode.fromStoredValue(it) } ?: QueueRepeatMode.OFF

private fun QueueState.metadataFor(occurrenceId: String?, repeat: QueueRepeatMode): DesktopMediaMetadata? {
    val id = occurrenceId ?: currentOccurrenceId ?: return null
    val occurrence = entries.firstOrNull { it.occurrenceId == id } ?: return null
    val item = occurrence.item
    return DesktopMediaMetadata(
        occurrenceId = id,
        title = item.title,
        artists = item.artists.map { it.name },
        album = item.album.orEmpty(),
        artworkUri = item.artworkUri,
        canNext = QueueAdvancePolicy.successor(repeat, playOrder, id) != null,
        canPrevious = playOrder.indexOf(id) > 0,
    )
}

/** One queue checkpoint. A failure is logged and never escapes into playback or shutdown. */
private suspend fun checkpointNow(queue: ContinuationQueueController) {
    try {
        queue.checkpoint()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        desktopDiagnostic("queue checkpoint failed: ${failure.javaClass.simpleName}")
    }
}

/** Process diagnostics go to the local error stream and the local log only. They never carry credentials or payloads. */
internal fun desktopDiagnostic(message: String) {
    DesktopDiagnosticLog.host(message)
}

/**
 * The text collator the portable library sorts with: the device locale at primary strength, so accents and case do not
 * split one artist into two groups. Constructed once per host; [Collator] is synchronized internally.
 */
internal fun deviceTextOrder(locale: Locale = Locale.getDefault()): Comparator<String> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return Comparator<String> { first, second -> collator.compare(first, second) }
}
