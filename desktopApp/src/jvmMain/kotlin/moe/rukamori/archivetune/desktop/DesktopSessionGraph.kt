package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.text.Collator
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.backup.DesktopBackup
import moe.rukamori.archivetune.desktop.backup.DesktopPendingRestore
import moe.rukamori.archivetune.desktop.integration.DesktopMediaMetadata
import moe.rukamori.archivetune.desktop.integration.auth.DesktopSecureCredentialStore
import moe.rukamori.archivetune.desktop.playback.DesktopPlaybackPort
import moe.rukamori.archivetune.desktop.provider.DesktopPlaybackPool
import moe.rukamori.archivetune.desktop.provider.DesktopYouTubeResolver
import moe.rukamori.archivetune.desktop.telegram.TdlightClientSession
import moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase
import moe.rukamori.archivetune.shared.data.persistence.RoomArtistBlockRepository
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.data.persistence.RoomListeningHistory
import moe.rukamori.archivetune.shared.data.persistence.RoomPlaylistTagsRepository
import moe.rukamori.archivetune.shared.data.persistence.RoomPodcastProgressStore
import moe.rukamori.archivetune.shared.data.playlistexport.PlaylistExportOwner
import moe.rukamori.archivetune.shared.data.playlistexport.RoomPlaylistExportSource
import moe.rukamori.archivetune.shared.data.persistence.openNow
import moe.rukamori.archivetune.shared.data.download.JvmDownloadRepository
import moe.rukamori.archivetune.shared.data.persistence.openPortableStateDatabase
import moe.rukamori.archivetune.shared.data.provider.JvmYouTubeCatalogueTransport
import moe.rukamori.archivetune.shared.data.provider.TidalAccountAuth
import moe.rukamori.archivetune.shared.data.provider.YouTubeContentRepository
import moe.rukamori.archivetune.shared.data.repository.PortableQueueOwner
import moe.rukamori.archivetune.shared.data.repository.QueueHandoffState
import moe.rukamori.archivetune.shared.data.repository.TypedDelegatingQueueController
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkFiles
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.JapaneseLanguagePackManager
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playlisttags.PlaylistTagsRepository
import moe.rukamori.archivetune.shared.domain.queue.QueueAdvancePolicy
import moe.rukamori.archivetune.shared.domain.repository.ContinuationQueueController
import moe.rukamori.archivetune.shared.domain.repository.CustomArtworkRepository
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.shared.domain.settings.PlayerVolumeKey
import moe.rukamori.archivetune.shared.ui.host.CatalogPageOwners
import moe.rukamori.archivetune.shared.ui.host.HostLyrics
import moe.rukamori.archivetune.shared.ui.host.LibraryContentOwner
import moe.rukamori.archivetune.shared.ui.host.LibraryPageOwners
import moe.rukamori.archivetune.shared.ui.host.ListenHistoryDriver
import moe.rukamori.archivetune.shared.ui.host.PodcastProgressDriver
import moe.rukamori.archivetune.shared.ui.session.LyricsSurfaceRuntime
import moe.rukamori.archivetune.shared.ui.settings.DesktopSettingsStore
import moe.rukamori.archivetune.shared.ui.settings.ProviderAccountLifecycle
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore

/**
 * The one production session graph of the desktop process. It owns the ordinary settings file, the platform vault
 * routes, the portable database, the playback port, the single queue owner and the listening history, and it is the
 * only place that wires them together. Window and root services borrow it; none of them creates a second queue,
 * clock, settings file, vault or database.
 *
 * Opening is all-or-nothing. A failed vault or database is never replaced by memory, a plaintext file or an empty
 * stand-in, so the caller shows the unavailable boundary instead of a usable-looking empty session.
 */
class DesktopSessionGraph internal constructor(
    /** Shared lifetime of every owner here. Cancelled only by [close], after each owner has released its resources. */
    val scope: CoroutineScope,
    /** The one logical settings view. Credential values are reached only through its vault routes. */
    val settings: VaultSettingsStore,
    /** The one provider account owner, over [settings]. Provider consumers reach accounts only through it. */
    val accounts: VaultProviderAccountStore,
    /** The one personal sign-in lifecycle over [accounts]. Settings sign-ins and the TIDAL refresh commit through it. */
    internal val accountLifecycle: ProviderAccountLifecycle,
    /** The locked instance directory this graph opened; update staging and the like resolve under it. */
    internal val instanceDirectory: Path,
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
    /** Song-seeded radio over the same queue: seamless for the current song, a fresh watch-next queue otherwise. */
    internal val radio: DesktopRadioOwner,
    /** Lifetime-bound projection of the queue and the player for the OS media controls. Never a second queue. */
    val metadata: StateFlow<DesktopMediaMetadata?>,
    /** The one device collator every sorted list of this host uses, so one artist never splits into two groups. */
    val textOrder: Comparator<String>,
    /** The one owner of the Library sections and playlist orders, read through [settings] and [library]. */
    internal val libraryContent: LibraryContentOwner,
    /** The YouTube resolve owner; the source chooser's refresh drops its searched-id cache entry. */
    internal val youtubeResolver: DesktopYouTubeResolver,
    private val owner: PortableQueueOwner,
    /** The engine behind [player], reached directly only for the output volume, which is not a queue command. */
    private val playback: DesktopPlaybackPort,
    private val ordinarySettings: DesktopSettingsStore,
    private val database: PortableStateDatabase,
    private val listenDriver: ListenHistoryDriver,
    private val progressDriver: PodcastProgressDriver,
    /** The one scrobbling owner: the signed Last.fm-compatible service, its pending queue and the playback driver. */
    internal val lastFm: DesktopLastFm,
    /** The one lyrics owner: the shared provider runtime following the player's current occurrence. */
    internal val lyrics: HostLyrics,
    /** The optional offline Japanese dictionary owner; null only when it could not be constructed. */
    internal val japaneseLanguagePack: JapaneseLanguagePackManager?,
    /** The one owner of the AI lyric sessions and of every lyric surface's phonetic readings. */
    internal val lyricsSurface: LyricsSurfaceRuntime,
    /** The one AI request limiter every desktop AI text service shares. */
    internal val aiRateLimiter: moe.rukamori.archivetune.shared.data.ai.AiRateLimiter,
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
    /** The process proxy policy, shared with Internet settings and installed before the HTTP clients. */
    internal val network: DesktopNetworkOwner,
    /** The one Playback Pool client: the ArchivePool account feed, its vault cache and the community instance list. */
    internal val playbackPool: DesktopPlaybackPool,
    /** The audio source tiers and the player-menu source search, over the runtime admissions. */
    internal val audioSources: DesktopAudioSources,
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
    /** The index every stored song row reads through, kept so the download reconcile can be scheduled. */
    internal val storedSongIndex: moe.rukamori.archivetune.shared.data.persistence.StoredSongIndex,
    /** The on-disk download repository behind [downloads]: hashed files plus the stored-song index. */
    internal val downloadStore: moe.rukamori.archivetune.shared.data.download.JvmDownloadRepository,
    /** The `downloads` owner: transfers over the same ordered resolver and the same store. */
    internal val downloads: DesktopDownloadsOwner,
    /** The `local_songs` owner: the folder scan and its persisted filter/sort settings. */
    internal val localMedia: DesktopLocalMediaOwner,
    /** The `stats` and `year_in_music` owner over [history]. */
    internal val statistics: DesktopStatisticsOwner,
    /** The `track_info` and `media_info` fact owner. */
    internal val mediaInfo: DesktopMediaInfoOwner,
    /** The `news` source, inactive exactly like the Android baseline's repository. */
    internal val news: DesktopNewsRepository,
    private val drivers: List<Job>,
) {
    internal val podcastProgress = RoomPodcastProgressStore(database.podcastProgress())
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

    /** The `uploaded_songs` owner: YouTube Music upload, shelf browse and delete over the one settings sign-in. */
    internal val uploads = DesktopYouTubeUploads(scope, settings, queue, { message -> report(message) })

    /** Search, albums and finite playlists over the same YouTube catalogue the queue router reads. */
    internal val catalogPages = CatalogPageOwners(youtubeContent, pageOwners)

    // Shares the InnerTube client the browse transport signed in: home, discovery, charts, moods, browse and
    // releases all read through it, and [catalogueOwners] holds the rows each page showed so a tap can queue them.
    internal val catalogue = JvmYouTubeCatalogueTransport()
    internal val catalogueOwners = DesktopCatalogueOwners(catalogue, youtubeContent, pageOwners)

    /** The one artwork loader of the window slots. It fetches over the same client as the sources and holds no other resource. */
    internal val artworkLoader = DesktopArtworkLoader(http, customArtwork, customArtworkFiles) { fileId ->
        telegram.downloadFileBlocking(fileId)
    }

    /** Android's artwork fallback for a Telegram track with no `tgart:` model: cache its inline minithumbnail. */
    internal fun telegramTrackArtworkUri(
        fileId: Int,
        title: String?,
        artist: String?,
        uniqueKey: String,
        minithumbnail: ByteArray?,
    ): String? = telegramArtworkUri(fileId, title, artist) ?: runCatching {
        if (minithumbnail == null || minithumbnail.isEmpty()) return null
        val directory = instanceDirectory.resolve("telegram_artwork")
        Files.createDirectories(directory)
        val safeKey = uniqueKey.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "track" }
        val file = directory.resolve("$safeKey.jpg")
        if (!Files.exists(file)) Files.write(file, minithumbnail)
        file.toUri().toString()
    }.getOrNull()

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

    /**
     * Final shutdown, after the window has stopped asking for work. The open listen and podcast position are finished
     * first so their play time and progress are kept, then the queue is checkpointed while the owner can still write,
     * and only then are the stores released.
     */
    suspend fun close() = withContext(NonCancellable) {
        listenTogether.close()
        listenDriver.close()
        progressDriver.close()
        lastFm.close()
        lyricsSurface.close()
        japaneseLanguagePack?.close()
        lyrics.close()
        listenerPresence.close()
        drivers.forEach { it.cancel() }
        checkpointNow(queue)
        owner.close()
        locators.close()
        telegram.close()
        uploads.close()
        audioSources.close()
        http.close()
        player.close()
        network.close()
        settings.close()
        ordinarySettings.close()
        database.close()
        scope.cancel()
    }

    companion object {
        private const val SETTINGS_FILE = "settings.preferences"
        private const val DATABASE_FILE = "portable-state.db"
        /** The position past which a previous skip restarts the current occurrence instead of stepping back. */
        internal const val RESTART_THRESHOLD_MS = 3_000L

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
            val network = DesktopNetworkOwner { vault.snapshots.value }
            val http = try {
                network.applyNetworkSettings(vault.snapshot())
                network.httpClient()
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    network.close()
                    database.close()
                    vault.close()
                    ordinary.close()
                }
                scope.cancel()
                throw failure
            }
            return try {
                assembleDesktopSessionOwners(scope, directory, ordinary, vault, database, http, network).also {
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
                    network.close()
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


    }
}

/** Opaque vault references must match [A-Za-z0-9_-]{1,128}; a dashless UUID is 32 hexadecimal characters. */
private fun newCredentialReference(): String = UUID.randomUUID().toString().replace("-", "")

/** One queue checkpoint. A failure is logged and never escapes into playback or shutdown. */
internal suspend fun checkpointNow(queue: ContinuationQueueController) {
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
