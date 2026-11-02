@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.backup.IosRestoreFileOps
import moe.rukamori.archivetune.shared.data.customartwork.FileCustomArtworkRepository
import moe.rukamori.archivetune.shared.data.localmedia.IosLocalMediaSource
import moe.rukamori.archivetune.shared.data.platform.ios.IosCustomArtworkFiles
import moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase
import moe.rukamori.archivetune.shared.data.persistence.RoomArtistBlockRepository
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.data.persistence.RoomListeningHistory
import moe.rukamori.archivetune.shared.data.persistence.RoomLocalMediaLibrary
import moe.rukamori.archivetune.shared.data.persistence.RoomPlaylistTagsRepository
import moe.rukamori.archivetune.shared.data.persistence.RoomPodcastProgressStore
import moe.rukamori.archivetune.shared.data.persistence.RoomSongLyricsOffsetRepository
import moe.rukamori.archivetune.shared.data.persistence.RoomQueueCheckpointStore
import moe.rukamori.archivetune.shared.data.persistence.iosPortableStateDatabasePath
import moe.rukamori.archivetune.shared.data.persistence.openNow
import moe.rukamori.archivetune.shared.data.persistence.openPortableStateDatabase
import moe.rukamori.archivetune.shared.data.persistence.snapshotTo
import moe.rukamori.archivetune.shared.data.platform.ios.IosCanvasCacheStore
import moe.rukamori.archivetune.shared.data.platform.ios.IosCrossfadeHandoff
import moe.rukamori.archivetune.shared.data.platform.ios.IosListenTogether
import moe.rukamori.archivetune.shared.data.platform.ios.IosListenTogetherAvatar
import moe.rukamori.archivetune.shared.data.platform.ios.IosMediaDirectories
import moe.rukamori.archivetune.shared.data.platform.ios.IosMediaImporter
import moe.rukamori.archivetune.shared.data.platform.ios.IosNetworkOwner
import moe.rukamori.archivetune.shared.data.platform.ios.IosPlaybackPool
import moe.rukamori.archivetune.shared.data.platform.ios.IosPlaybackPort
import moe.rukamori.archivetune.shared.data.platform.ios.IosProviderCredentials
import moe.rukamori.archivetune.shared.data.platform.ios.IosTelegramClient
import moe.rukamori.archivetune.shared.data.platform.ios.IosTransportAvailability
import moe.rukamori.archivetune.shared.data.platform.ios.iosNetworkAvailable
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.data.platform.ios.iosTidalDeviceClient
import moe.rukamori.archivetune.shared.data.potoken.IosPoTokenSupport
import moe.rukamori.archivetune.shared.data.platform.ios.iosTidalPkceClient
import moe.rukamori.archivetune.shared.data.provider.IosYouTubeBrowseTransport
import moe.rukamori.archivetune.shared.data.provider.IosYouTubeResolver
import moe.rukamori.archivetune.shared.data.provider.KtorSponsorBlockRepository
import moe.rukamori.archivetune.shared.data.provider.OrderedAudioResolver
import moe.rukamori.archivetune.shared.data.provider.TidalAccountAuth
import moe.rukamori.archivetune.shared.data.provider.YouTubeAccountBinding
import moe.rukamori.archivetune.shared.data.provider.YouTubeCatalogueTransport
import moe.rukamori.archivetune.shared.data.provider.YouTubeContentRepository
import moe.rukamori.archivetune.shared.data.provider.catalogueTransport
import moe.rukamori.archivetune.shared.data.repository.ContentRepositoryRouter
import moe.rukamori.archivetune.shared.data.repository.PortableQueueOwner
import moe.rukamori.archivetune.shared.data.repository.QueueHandoffState
import moe.rukamori.archivetune.shared.data.repository.TypedDelegatingQueueController
import moe.rukamori.archivetune.shared.data.repository.TypedRoomLibraryRepository
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagsRepository
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.queue.QueueAdvancePolicy
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContinuationQueueController
import moe.rukamori.archivetune.shared.domain.settings.AutoSkipNextOnErrorKey
import moe.rukamori.archivetune.shared.domain.settings.AutoLoadMoreKey
import moe.rukamori.archivetune.shared.domain.settings.HideExplicitKey
import moe.rukamori.archivetune.shared.domain.settings.HideVideoKey
import moe.rukamori.archivetune.shared.domain.settings.ListenTogetherSettingsDefaults
import moe.rukamori.archivetune.shared.domain.settings.PlayerVolumeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.PreventDuplicateTracksInQueueKey
import moe.rukamori.archivetune.shared.domain.settings.QueueAddAfterManualKey
import moe.rukamori.archivetune.shared.domain.settings.QueueLockedKey
import moe.rukamori.archivetune.shared.domain.settings.RepeatModeKey
import moe.rukamori.archivetune.shared.domain.settings.WebClientPoTokenEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.YtmSyncKey
import moe.rukamori.archivetune.shared.domain.settings.listenTogetherAvatarIndex
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.host.HostLyrics
import moe.rukamori.archivetune.shared.ui.host.LibraryContentOwner
import moe.rukamori.archivetune.shared.ui.host.LibraryPageOwners
import moe.rukamori.archivetune.shared.ui.host.ListenHistoryDriver
import moe.rukamori.archivetune.shared.ui.host.PodcastProgressDriver
import moe.rukamori.archivetune.shared.ui.host.PodcastSponsorBlockDriver
import moe.rukamori.archivetune.shared.ui.host.TidalSessionRefresh
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.SharedSleepTimer
import moe.rukamori.archivetune.shared.ui.settings.ProviderAccountLifecycle
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore
import moe.rukamori.archivetune.shared.ui.settings.providerAccountScope
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
    val http: HttpClient,
    /** The proxy route this process's Darwin session was built with; it cannot change while the session lives. */
    val network: IosNetworkOwner,
    val player: MusicPlayerController,
    val queue: TypedDelegatingQueueController,
    val youtubeTransport: IosYouTubeBrowseTransport,
    /** The one BotGuard poToken owner; the settings page and the YouTube resolver share it. */
    internal val poTokens: IosPoTokenSupport,
    /** The typed YouTube catalogue over [youtubeTransport] and the account binding: search, albums, playlists, sections. */
    val youtubeContent: YouTubeContentRepository,
    /** The shelf feeds over the same InnerTubeX session: the home feed, charts, explore and new releases. */
    val youtubeCatalogue: YouTubeCatalogueTransport,
    internal val library: RoomLibraryRepository,
    val artistBlocks: RoomArtistBlockRepository,
    val libraryContent: LibraryContentOwner,
    val pageOwners: LibraryPageOwners,
    /** The one listening history the library, the listen driver and the statistics pages read and write. */
    val history: RoomListeningHistory,
    val lyrics: HostLyrics,
    val customArtwork: FileCustomArtworkRepository,
    val playbackPool: IosPlaybackPool,
    val artworkLoader: IosArtworkLoader,
    /** The on-disk canvas frame store the canvas owner and the canvas cache settings share. */
    val canvasStore: IosCanvasCacheStore,
    /** The room, chat and settings owner over the one Listen Together engine, started and closed with the graph. */
    val listenTogether: IosListenTogetherOwner,
    /** The YouTube Music uploads owner: picker transfers over its own client, closed with the graph. */
    val uploads: IosYouTubeUploads,
    /** Downloads: the shared portable queue over this host's source chain, stored in the app container. */
    val downloads: IosDownloadsOwner,
    /** Backup and restore: the portable-format gateway, the prepared-restore registry and the restart request. */
    val backup: IosBackupOwner,
    /** The imported local songs: the Files picker, the shared scan pipeline and the `local` library rows. */
    val localMedia: IosLocalMediaOwner,
    /** The Last.fm, Libre.fm and custom-endpoint scrobbler over the one player and queue, closed with the graph. */
    val lastFm: IosLastFm,
    /** The Discord owner: PKCE sheet sign-in and playback-driven presence, closed with the graph. */
    val discord: IosDiscordPresence,
    private val owner: PortableQueueOwner,
    private val playbackPort: IosPlaybackPort,
    private val database: PortableStateDatabase,
    private val listenDriver: ListenHistoryDriver,
    private val progressDriver: PodcastProgressDriver,
    private val drivers: List<Job>,
    private val reportLine: (String) -> Unit,
) {
    val podcastProgress = RoomPodcastProgressStore(database.podcastProgress())

    /** The persisted per-song lyrics offsets this host applies on every lyrics surface. */
    val songLyricsOffsets = RoomSongLyricsOffsetRepository(database.songLyricsOffsets())
    /** The playlist tag catalog and assignments, in the same portable file as the playlists; the tag manager is not mounted on iOS. */
    val playlistTags: PlaylistTagsRepository = RoomPlaylistTagsRepository(database.library())
    /** The crossfade or automix blend armed into the next song by the same occurrence-aware queue owner. */
    val handoff: StateFlow<QueueHandoffState?> get() = owner.handoff

    /** The one sleep timer of this process. It only pauses [player], so every surface shares it. */
    val sleepTimer = SharedSleepTimer(scope, player.snapshot, player::pause, ::iosNowMillis)

    /**
     * The "pause on device mute" policy: it watches the system output volume and the app-level output
     * level and pauses/resumes through the port the way the Android stream-mute policy does.
     */
    val outputMutePolicy = IosOutputMutePolicy(
        scope = scope,
        settings = settings,
        port = playbackPort,
        player = player,
        restoreVolume = ::setVolume,
        report = ::report,
    )

    /**
     * Next/previous enablement for the system transport: the same successors the on-screen controls compute, so the
     * lock screen offers a skip exactly when the queue reports one.
     */
    val transportAvailability: Flow<IosTransportAvailability> = combine(
        queue.state, player.snapshot, settings.snapshots,
    ) { queueState, playback, preferences ->
        val native = iosNativeState(queueState, playback, preferences)
        IosTransportAvailability(canPrevious = native.canPrevious, canNext = native.canNext)
    }.distinctUntilChanged()

    /**
     * Sets the app-level output volume, 0 to 1, on the engine and keeps it in [PlayerVolumeKey] as the Android player
     * does, so the same slider the Android app offers drives the output on this host too.
     */
    fun setVolume(volume: Float) {
        if (!volume.isFinite()) return
        val level = volume.coerceIn(0f, 1f)
        playbackPort.setVolume(level)
        scope.launch {
            try {
                settings.edit { set(PlayerVolumeKey, level) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("volume was not saved: ${failure::class.simpleName}")
            }
        }
    }

    fun report(message: String) = reportLine(message)

    /**
     * Hands the host's admitted Telegram session to the artwork path so a `tgart:` cover downloads its thumbnail
     * through it. The playback port's own attach stays the host's call; this only lets the loader reach the same
     * client, and an unadmitted or closed session answers nothing.
     */
    fun attachTelegram(session: IosTelegramClient) = artworkLoader.attachTelegram(session)

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
        owner.skipTo(target, 0L)
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
            owner.skipTo(state.playOrder[index - 1], 0L)
            true
        }
    }

    /** The item of [occurrenceId] in the queue, for the lock screen and Control Center. */
    fun metadata(occurrenceId: String): ContentItem? =
        queue.state.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item

    /**
     * Final shutdown. The open listen and podcast position are finished first so their play time and progress are kept,
     * then the queue is checkpointed while the owner can still write, and only then are the database and the client
     * released. The host's player, port and settings belong to the host, which closes them afterwards.
     */
    suspend fun close() = withContext(NonCancellable) {
        outputMutePolicy.close()
        playbackPort.cancelNext()
        discord.close()
        lastFm.close()
        listenTogether.close()
        uploads.close()
        listenDriver.close()
        progressDriver.close()
        lyrics.close()
        drivers.forEach { it.cancel() }
        // The download and import owners write rows and files, so both stop before the checkpoint and the database.
        downloads.close()
        localMedia.close()
        checkpointNow(queue, reportLine)
        owner.close()
        poTokens.close()
        http.close()
        database.close()
        scope.cancel()
    }

    companion object {
        /** The position past which a previous skip restarts the current occurrence instead of stepping back. */
        const val RESTART_THRESHOLD_MS = 3_000L
        private const val POSITION_CHECKPOINT_INTERVAL_MS = 10_000L

        /** The portable state file the staged restore swaps under its sidecar files. */
        private const val DATABASE_FILE = "portable-state.db"

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
            val databasePath = iosPortableStateDatabasePath()
            val databaseDirectory = databasePath.substringBeforeLast('/')
            // A restore staged by an earlier launch applies before the owners open the files: the staged library is
            // swapped in and validated before the staged settings merge, and the transaction keeps the originals
            // aside until the merge lands, so files and settings land or roll back together. A section that fails
            // rolls both back, keeps the pending set under restore-failed for diagnosis and surfaces on the page.
            val restoreStart = withContext(Dispatchers.Default) {
                IosRestoreStartup.begin(databaseDirectory, DATABASE_FILE, report)
            }
            val pending = restoreStart.pending
            // Recovery mode means a throwaway database only: the host shows the resolution surface and the
            // library files stay exactly as the failed restore left them until the user decides.
            var recoveryMode = restoreStart.blocked
            if (restoreStart.needsSettingsRollback && pending != null) {
                // The file rollback already ran; the vault still holds the merged settings and owes its
                // pre-apply snapshot. A failed restore of it keeps every marker — the resolution actions or
                // a later start finish the job rather than opening a mismatched pair.
                val vaultRestored = try {
                    IosRestoreStartup.rollbackPendingSettings(pending, IosRestoreFileOps(), settings)
                    true
                } catch (cancelled: CancellationException) {
                    scope.cancel()
                    throw cancelled
                } catch (failure: Exception) {
                    report("restored settings rollback failed: ${failure::class.simpleName}")
                    false
                }
                if (vaultRestored) {
                    runCatching {
                        IosRestoreFileOps().delete(IosRestoreRecovery.abortMarkerPath(pending.directory))
                    }
                    runCatching { pending.markFailed() }
                } else {
                    runCatching { IosRestoreRecovery.block(IosRestoreFileOps(), databaseDirectory) }
                    recoveryMode = true
                }
            }
            var restoreSettled = restoreStart.transaction == null
            var database = try {
                withContext(Dispatchers.Default) {
                    if (recoveryMode) {
                        openPortableStateDatabase(":memory:")
                    } else {
                        openGraphDatabase(databasePath, databaseDirectory, restoreStart, settings, report)
                            ?: openPortableStateDatabase(":memory:").also { recoveryMode = true }
                    }
                }
            } catch (failure: Throwable) {
                scope.cancel()
                throw failure
            }
            if (pending != null && !pending.failed && !recoveryMode) {
                if (pending.legacyFormat) {
                    try {
                        pending.applySettings(settings)
                    } catch (cancelled: CancellationException) {
                        database.close()
                        scope.cancel()
                        throw cancelled
                    } catch (failure: Exception) {
                        report("restored settings not applied: ${failure::class.simpleName}")
                        withContext(NonCancellable) {
                            // Room releases the files before the rollback replaces them.
                            runCatching { database.close() }
                            pending.rollbackSettings(settings)
                            pending.rollbackDatabase(databaseDirectory, DATABASE_FILE)
                            runCatching { pending.markFailed() }
                        }
                        database = try {
                            withContext(Dispatchers.Default) { openPortableStateDatabase(databasePath) }
                        } catch (reopen: Throwable) {
                            scope.cancel()
                            throw reopen
                        }
                    }
                } else if (restoreStart.transaction != null) {
                    var applied = true
                    if (restoreStart.needsSettingsApply) {
                        try {
                            IosRestoreStartup.applyPendingSettings(pending, IosRestoreFileOps(), settings)
                        } catch (cancelled: CancellationException) {
                            database.close()
                            scope.cancel()
                            throw cancelled
                        } catch (failure: Exception) {
                            applied = false
                            report("restored settings not applied: ${failure::class.simpleName}")
                            val ops = IosRestoreFileOps()
                            var vaultOk = true
                            withContext(NonCancellable) {
                                // Room releases the files before the rollback replaces them, and the abort
                                // decision is durable first: a kill re-enters the joint undo next start.
                                runCatching { database.close() }
                                val abortPersisted = runCatching {
                                    ops.writeAtomically(
                                        IosRestoreRecovery.abortMarkerPath(pending.directory),
                                        byteArrayOf(1),
                                    )
                                }.isSuccess
                                if (abortPersisted) {
                                    if (pending.hasSettingsBefore) {
                                        vaultOk = runCatching {
                                            IosRestoreStartup.rollbackPendingSettings(pending, ops, settings)
                                        }.isSuccess
                                        if (!vaultOk) report("restored settings rollback failed")
                                    }
                                    runCatching { restoreStart.transaction.rollbackCommitted() }
                                } else {
                                    // No durable abort decision: a kill now would re-read a half-undone set
                                    // as a fresh restore, so block and hold the set for recovery mode.
                                    report("restore abort marker not written")
                                    vaultOk = false
                                }
                                if (vaultOk) {
                                    runCatching { pending.markFailed() }
                                } else {
                                    // Keep the markers; recovery mode owns the session and the resolution
                                    // actions or a later start finish the undo.
                                    runCatching { IosRestoreRecovery.block(ops, databaseDirectory) }
                                }
                            }
                            database = try {
                                withContext(Dispatchers.Default) {
                                    if (vaultOk) {
                                        openPortableStateDatabase(databasePath)
                                    } else {
                                        recoveryMode = true
                                        openPortableStateDatabase(":memory:")
                                    }
                                }
                            } catch (reopen: Throwable) {
                                scope.cancel()
                                throw reopen
                            }
                        }
                    }
                    if (applied) {
                        try {
                            restoreStart.transaction.finalize()
                            restoreSettled = true
                        } catch (failure: Exception) {
                            // The journal stays for the next start to finish or re-apply from.
                            report("restore cleanup not finished: ${failure::class.simpleName}")
                        }
                    }
                }
            }
            // The Darwin engine reads its proxy when it creates the session, so the saved route is decided before
            // the client exists and stays pinned for this process.
            val network = IosNetworkOwner({ settings.snapshots.value }, report)
            val http = HttpClient(Darwin) { engine { network.configure(this) } }
            report(network.describe())
            return try {
                assemble(
                    host, scope, settings, accounts, database, databaseDirectory, http, network, report,
                    restoreFailed = pending?.failed == true && !recoveryMode,
                    recoveryMode = recoveryMode,
                ).also {
                    // A fully applied restore's staging goes away; a failed one rests under restore-failed,
                    // and an unfinished journal stays so the next start settles it instead of being deleted.
                    if (pending != null && !pending.failed && restoreSettled && !recoveryMode) {
                        try {
                            pending.finish()
                        } catch (failure: Exception) {
                            report("restore staging not removed: ${failure::class.simpleName}")
                        }
                    }
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    http.close()
                    database.close()
                }
                scope.cancel()
                throw failure
            }
        }

        /**
         * Opens the portable state database; when a staged library was swapped in, it is opened now so a file Room
         * refuses still lets the previous database come back instead of leaving a broken library. Null means the
         * vault rollback failed beside it: markers and the blocked flag stay, and the caller assembles recovery
         * mode instead of a mismatched pair.
         */
        private suspend fun openGraphDatabase(
            databasePath: String,
            databaseDirectory: String,
            start: IosRestoreStart,
            settings: VaultSettingsStore,
            report: (String) -> Unit,
        ): PortableStateDatabase? {
            val database = openPortableStateDatabase(databasePath)
            val swapped = start.databaseSwapped || start.pending?.databaseSwapped == true
            if (!swapped) return database
            try {
                database.openNow()
                return database
            } catch (cancelled: CancellationException) {
                database.close()
                throw cancelled
            } catch (failure: Exception) {
                report("restored library refused: ${failure::class.simpleName}")
                val ops = IosRestoreFileOps()
                var vaultOk = true
                withContext(NonCancellable) {
                    // Room releases the files before the rollback replaces them; the abort marker is durable
                    // first so a kill re-enters the joint undo instead of a fresh interpretation.
                    runCatching { database.close() }
                    start.pending?.let { pending ->
                        if (start.transaction != null) {
                            val abortPersisted = runCatching {
                                ops.writeAtomically(
                                    IosRestoreRecovery.abortMarkerPath(pending.directory),
                                    byteArrayOf(1),
                                )
                            }.isSuccess
                            if (abortPersisted) {
                                // A vault merge that already landed goes back with the files it belongs to.
                                if (pending.hasSettingsBefore) {
                                    vaultOk = runCatching {
                                        IosRestoreStartup.rollbackPendingSettings(pending, ops, settings)
                                    }.isSuccess
                                    if (!vaultOk) report("restored settings rollback failed")
                                }
                                runCatching { start.transaction.rollbackCommitted() }
                            } else {
                                // No durable abort decision: a kill now would re-read a half-undone set
                                // as a fresh restore, so block and hold for recovery mode.
                                report("restore abort marker not written")
                                vaultOk = false
                            }
                        } else {
                            pending.rollbackDatabase(databaseDirectory, DATABASE_FILE)
                        }
                    }
                    if (vaultOk) {
                        runCatching { start.pending?.markFailed() }
                    } else {
                        runCatching { IosRestoreRecovery.block(ops, databaseDirectory) }
                    }
                }
                if (!vaultOk) return null
                return openPortableStateDatabase(databasePath)
            }
        }

        private suspend fun assemble(
            host: IosHost,
            scope: CoroutineScope,
            vault: VaultSettingsStore,
            accounts: VaultProviderAccountStore,
            database: PortableStateDatabase,
            databaseDirectory: String,
            http: HttpClient,
            network: IosNetworkOwner,
            report: (String) -> Unit,
            restoreFailed: Boolean,
            recoveryMode: Boolean,
        ): IosSessionGraph {
            val accountLifecycle = ProviderAccountLifecycle(accounts, vault)
            // The device and PKCE clients come from the build's Info.plist keys; a build without them keeps
            // those sign-ins unavailable. A pasted refresh token is still verified with TIDAL.
            val tidalAuth = TidalAccountAuth(
                http,
                device = iosTidalDeviceClient(),
                pkce = iosTidalPkceClient(),
                nowMs = ::iosNowMillis,
            )
            val tidalSessionRefresh = TidalSessionRefresh(
                tidalAuth, accountLifecycle, vault, preferences = { vault.snapshots.value }, nowMs = ::iosNowMillis,
            )
            val songMetadata = moe.rukamori.archivetune.shared.data.persistence.RoomSongMetadataRepository(database.songMetadataOverrides())
            val library = RoomLibraryRepository(
                database.library(), listens = database.listens(), storedSongs = database.storedSongs(),
                songMetadata = songMetadata,
            )
            val artistBlocks = RoomArtistBlockRepository(database.artistBlocks(), nowMs = ::iosNowMillis)
            val blockedQueueArtists = artistBlocks.observeBlockedArtists().map { refs ->
                refs.filter { it.provider == ContentProvider.YOUTUBE.id }.mapTo(HashSet()) { it.id }
            }
            val queueArtistBlocks = blockedQueueArtists.stateIn(scope, SharingStarted.Eagerly, blockedQueueArtists.first())
            val history = RoomListeningHistory(database.listens(), offsetMsAt = ::iosOffsetMsAt)
            val player = host.player
            val playbackPool = IosPlaybackPool(
                http = http,
                vault = host.credentials,
                preferences = { vault.snapshots.value },
                scope = scope,
                log = report,
                nowMs = ::iosNowMillis,
            )
            // One InnerTubeX transport serves the catalogue and account paths; the resolver walks the shipped
            // player clients first and keeps InnerTubeX as the bounded extractor tier, like the desktop host.
            val youtubeTransport = IosYouTubeBrowseTransport(http)
            youtubeTransport.refreshSession(vault.snapshots.value)
            // One BotGuard owner feeds the resolver's minting and the settings generation, like the
            // shared BotGuardTokenGenerator object on Android.
            val poTokens = IosPoTokenSupport(
                http = http,
                transport = youtubeTransport,
                enabled = { vault.snapshots.value?.get(WebClientPoTokenEnabledKey) ?: false },
                report = report,
            )
            // The shipped app warms BotGuard once the auth state names a session, so a cold mint
            // never lands inside a resolve; the owner derives the session id from the transport
            // itself and stays a no-op while the poToken preference is off.
            scope.launch { poTokens.preWarm() }
            // The shelf feeds read the same session, so a signed-in home feed and the account listings agree.
            val youtubeCatalogue = youtubeTransport.catalogueTransport()
            val youtubeAccount = YouTubeAccountBinding(
                transport = youtubeTransport,
                accounts = accounts,
                personalScope = { providerAccountScope(ContentProvider.YOUTUBE.id, CredentialScope.PERSONAL) },
                syncEnabled = { vault.snapshots.value?.get(YtmSyncKey) ?: true },
            )
            val audioResolver = OrderedAudioResolver(
                providers = iosAudioProviders(http, accounts, { vault.snapshots.value }, tidalSessionRefresh, playbackPool),
                youtube = IosYouTubeResolver(
                    http = http,
                    transport = youtubeTransport,
                    preferences = { vault.snapshots.value },
                    personalScope = {
                        providerAccountScope(ContentProvider.YOUTUBE.id, CredentialScope.PERSONAL)
                    },
                    poTokens = poTokens,
                ),
            )
            // Both media owners write into the app container, which the host creates once here.
            val mediaDirectories = IosMediaDirectories().also { it.ensure() }
            val localMedia = IosLocalMediaOwner(
                host = host,
                importer = IosMediaImporter(mediaDirectories.imports),
                library = RoomLocalMediaLibrary(database.localTracks(), vault, songMetadata),
                source = IosLocalMediaSource(mediaDirectories.imports, mediaDirectories.importArtwork),
                scope = scope,
                report = report,
            )
            val downloads = IosDownloadsOwner(
                tasks = database.downloadTasks(),
                storedSongs = database.storedSongs(),
                library = library,
                settings = vault,
                resolver = audioResolver,
                root = mediaDirectories.downloads,
                client = http,
                scope = scope,
                nowMs = ::iosNowMillis,
                report = report,
            )
            // The one image cache this host keeps: the saved limit applies to the shared URL cache at open.
            iosApplyImageCacheLimit(vault.snapshots.value)
            // Backup archives hold a VACUUM INTO snapshot of this same database, staged beside it on restore.
            val backup = IosBackupOwner(
                settings = vault,
                snapshotDatabase = { target -> database.snapshotTo(target) },
                instanceDirectory = databaseDirectory,
                initialRestoreFailed = restoreFailed,
                initialRecoveryMode = recoveryMode,
            )
            val locators = IosQueueLocatorResolver(
                audio = audioResolver,
                port = host.playbackPort,
                preferences = { vault.snapshots.value },
                localFile = { ref -> localMedia.pathOf(ref.id) },
                downloadedFile = { ref -> downloads.committedFile(ref) },
            )
            // One typed router serves the queue's resolution and the chat song picker, so both search the same sources.
            val youtubeContent = YouTubeContentRepository(youtubeTransport, youtubeAccount)
            val contentRouter = ContentRepositoryRouter(listOf(TypedRoomLibraryRepository(library), youtubeContent))
            val handoff = IosCrossfadeHandoff(host.playbackPort, vault.snapshots)
            val owner = PortableQueueOwner(
                player = player,
                scope = scope,
                content = contentRouter,
                locators = locators,
                keepManualQueueInOrder = vault.snapshot()[QueueAddAfterManualKey] ?: true,
                repeatMode = { iosRepeatMode(vault.snapshots.value) },
                autoSkipNextOnError = { vault.snapshots.value?.get(AutoSkipNextOnErrorKey) ?: false },
                preventDuplicateTracksInQueue = { vault.snapshots.value?.get(PreventDuplicateTracksInQueueKey) ?: false },
                queueLocked = { vault.snapshots.value?.get(QueueLockedKey) ?: false },
                songMetadata = songMetadata,
                autoLoadMoreEnabled = { vault.snapshots.value?.get(AutoLoadMoreKey) ?: true },
                autoLoadMoreChanges = vault.snapshots.map { it?.get(AutoLoadMoreKey) ?: true },
                hideExplicit = { vault.snapshots.value?.get(HideExplicitKey) ?: false },
                hideVideo = { vault.snapshots.value?.get(HideVideoKey) ?: false },
                blockedArtistIds = { queueArtistBlocks.value },
                handoffEngine = handoff,
            )
            val queue = TypedDelegatingQueueController(owner, RoomQueueCheckpointStore(database.checkpoints()))
            try {
                queue.resume()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("saved queue was not restored: ${failure::class.simpleName}")
            }
            // The Discord owner borrows the host's ephemeral OAuth sheet and the shared websocket wrapper; it only
            // acts on playback and settings reads, so it costs nothing until the settings page starts its driver.
            val discord = IosDiscordPresence(
                scope = scope,
                http = http,
                settings = vault,
                browser = host.browser,
                player = player,
                queue = queue.state,
                report = report,
            )
            // The scrobbler sits on the one player and queue, so a like written below is mirrored to Last.fm by the
            // same owner that queues the scrobbles.
            val lastFm = IosLastFm(
                scope = scope,
                http = http,
                settings = vault,
                player = player,
                queue = queue.state,
                report = report,
            )
            val listenDriver = ListenHistoryDriver(
                scope = scope,
                player = player,
                queue = queue.state,
                history = history,
                preferences = vault.snapshots,
                report = report,
                nowMs = ::iosNowMillis,
            )
            // Written from the same snapshots; close() flushes the open episode before the database is released.
            val progressDriver = PodcastProgressDriver(
                scope = scope,
                player = player,
                queue = queue.state,
                store = RoomPodcastProgressStore(database.podcastProgress()),
                report = report,
                nowMs = ::iosNowMillis,
            )
            // Lives for the app scope: it holds no state the graph must close and only reads published snapshots.
            PodcastSponsorBlockDriver(
                scope = scope,
                player = player,
                queue = queue.state,
                preferences = vault.snapshots,
                repository = KtorSponsorBlockRepository(http),
            )
            // The build-time Lyprio pair is the only endpoint the provider can have; there is no user setting.
            val lyrics = HostLyrics(
                scope = scope,
                http = http,
                settings = vault,
                player = player,
                queue = queue.state,
                networkAvailable = { iosNetworkAvailable() },
                report = report,
                lyprioBuildBaseUrl = IosProviderCredentials.lyprioBaseUrl,
                lyprioBuildHmacKey = IosProviderCredentials.lyprioHmacKey,
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
                onLikeWritten = { ref, liked ->
                    try {
                        lastFm.mirrorLove(library.item(ref), liked)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        report("Last.fm love skipped: ${failure::class.simpleName}")
                    }
                },
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
                    // Checkpoints tick only while playWhenReady is set; a paused session sleeps until play.
                    player.snapshot.map { it?.playWhenReady == true }.distinctUntilChanged().collectLatest { playing ->
                        while (playing) {
                            delay(POSITION_CHECKPOINT_INTERVAL_MS)
                            checkpointNow(queue, report)
                        }
                    }
                },
            )
            val listenTogether = IosListenTogetherOwner(
                scope = scope,
                settings = vault,
                queue = queue,
                content = contentRouter,
                report = report,
                engine = IosListenTogether(
                    parentScope = scope,
                    settings = vault,
                    player = player,
                    queue = queue,
                    // The same finite clamp as the desktop host, before the one playback port.
                    setVolume = { level -> if (level.isFinite()) host.playbackPort.setVolume(level.coerceIn(0f, 1f)) },
                    localAvatarBytes = {
                        if (vault.snapshots.value?.listenTogetherAvatarIndex() == ListenTogetherSettingsDefaults.CUSTOM_AVATAR_INDEX) {
                            IosListenTogetherAvatar.load()
                        } else {
                            null
                        }
                    },
                    report = report,
                ),
            )
            val uploads = IosYouTubeUploads(scope, vault, queue, report) { network.configure(this) }
            // The custom cover store and its folder are shared with the artwork loader, which resolves a stored
            // cover to its file URI and reads it locally.
            val customArtworkFiles = IosCustomArtworkFiles()
            val customArtwork = FileCustomArtworkRepository(customArtworkFiles, nowMillis = ::iosNowMillis)
            val handoffHold = scope.launch {
                listenTogether.client.roomState.map { it != null }.distinctUntilChanged().collect { handoff.hold(it) }
            }
            val graph = IosSessionGraph(
                scope = scope,
                settings = vault,
                accounts = accounts,
                accountLifecycle = accountLifecycle,
                tidalAuth = tidalAuth,
                http = http,
                network = network,
                player = player,
                queue = queue,
                library = library,
                artistBlocks = artistBlocks,
                youtubeTransport = youtubeTransport,
                poTokens = poTokens,
                youtubeContent = youtubeContent,
                youtubeCatalogue = youtubeCatalogue,
                libraryContent = libraryContent,
                pageOwners = pageOwners,
                history = history,
                lyrics = lyrics,
                customArtwork = customArtwork,
                playbackPool = playbackPool,
                artworkLoader = IosArtworkLoader(http, customArtwork, customArtworkFiles),
                canvasStore = IosCanvasCacheStore().also { it.ensure() },
                listenTogether = listenTogether,
                uploads = uploads,
                downloads = downloads,
                backup = backup,
                localMedia = localMedia,
                lastFm = lastFm,
                discord = discord,
                owner = owner,
                playbackPort = host.playbackPort,
                database = database,
                listenDriver = listenDriver,
                progressDriver = progressDriver,
                drivers = drivers + handoffHold,
                reportLine = report,
            )
            host.telegram.value?.let { graph.attachTelegram(it) }
            lyrics.start()
            playbackPool.start()
            lastFm.start()
            listenTogether.start()
            // Re-arms a persisted download queue and publishes the imported songs the last session listed.
            downloads.start()
            localMedia.start()
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
internal val iosTextOrder: Comparator<String> = Comparator { first, second ->
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
