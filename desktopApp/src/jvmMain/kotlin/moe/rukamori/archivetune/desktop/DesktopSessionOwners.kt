package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import java.io.File
import java.nio.file.Path
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.backup.DesktopBackup
import moe.rukamori.archivetune.shared.data.playlistimport.PlaylistImportOwner
import moe.rukamori.archivetune.shared.data.playlistimport.YouTubePlaylistImportSearch
import moe.rukamori.archivetune.desktop.integration.DesktopTransportState
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.desktop.integration.auth.DesktopSecureCredentialStore
import moe.rukamori.archivetune.desktop.playback.DesktopPlaybackPort
import moe.rukamori.archivetune.desktop.playback.DesktopPlaybackSelfCheck
import moe.rukamori.archivetune.desktop.potoken.DesktopPoTokenOwner
import moe.rukamori.archivetune.desktop.potoken.desktopLocalPoTokenHelper
import moe.rukamori.archivetune.desktop.playback.NativeStreamLease
import moe.rukamori.archivetune.desktop.playback.TelegramFileBinding
import moe.rukamori.archivetune.desktop.provider.DesktopPlaybackPool
import moe.rukamori.archivetune.desktop.provider.DesktopResolvedStreamCache
import moe.rukamori.archivetune.desktop.provider.DesktopSourceAdmissions
import moe.rukamori.archivetune.desktop.provider.DesktopYouTubeResolver
import moe.rukamori.archivetune.desktop.provider.youTubeSignIn
import moe.rukamori.archivetune.desktop.telegram.TdlightClientSession
import moe.rukamori.archivetune.desktop.telegram.TelegramNativeAccountPort
import moe.rukamori.archivetune.shared.data.ai.AiRateLimiter
import moe.rukamori.archivetune.shared.data.ai.KtorAiTextService
import moe.rukamori.archivetune.shared.data.customartwork.FileCustomArtworkRepository
import moe.rukamori.archivetune.shared.data.customartwork.JvmCustomArtworkFiles
import moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase
import moe.rukamori.archivetune.shared.data.platform.desktop.jvmJapaneseLanguagePackManager
import moe.rukamori.archivetune.shared.data.persistence.RoomArtistBlockRepository
import moe.rukamori.archivetune.shared.data.recommend.PortableRecommendationSignals
import moe.rukamori.archivetune.shared.data.recommend.QueueTailRecommendation
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.data.persistence.RoomListeningHistory
import moe.rukamori.archivetune.shared.data.persistence.RoomPodcastProgressStore
import moe.rukamori.archivetune.shared.data.playlistexport.PlaylistExportOwner
import moe.rukamori.archivetune.shared.data.playlistexport.RoomPlaylistExportSource
import moe.rukamori.archivetune.shared.data.potoken.poTokenSessionId
import moe.rukamori.archivetune.shared.data.persistence.RoomQueueCheckpointStore
import moe.rukamori.archivetune.shared.data.download.DownloadKey
import moe.rukamori.archivetune.shared.data.download.JvmDownloadRepository
import moe.rukamori.archivetune.shared.data.localmedia.JvmLocalMediaScanner
import moe.rukamori.archivetune.shared.data.persistence.RoomStoredSongIndex
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.data.persistence.snapshotTo
import moe.rukamori.archivetune.shared.data.provider.JvmYouTubeBrowseTransport
import moe.rukamori.archivetune.shared.data.provider.KtorSponsorBlockRepository
import moe.rukamori.archivetune.shared.data.provider.OrderedAudioResolver
import moe.rukamori.archivetune.shared.data.provider.TidalAccountAuth
import moe.rukamori.archivetune.shared.data.provider.YouTubeAccountBinding
import moe.rukamori.archivetune.shared.data.provider.YouTubeContentRepository
import moe.rukamori.archivetune.shared.data.repository.ContentRepositoryRouter
import moe.rukamori.archivetune.shared.data.repository.PortableQueueOwner
import moe.rukamori.archivetune.shared.data.repository.TypedDelegatingQueueController
import moe.rukamori.archivetune.shared.data.repository.TypedRoomLibraryRepository
import moe.rukamori.archivetune.shared.data.repository.YouTubeSettlingLibrary
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.IcuLyricsTransliterator
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YouTubeLocale
import moe.rukamori.archivetune.shared.domain.settings.AutoSkipNextOnErrorKey
import moe.rukamori.archivetune.shared.domain.settings.AutoLoadMoreKey
import moe.rukamori.archivetune.shared.domain.settings.BlockedSongIdsKey
import moe.rukamori.archivetune.shared.domain.settings.parseBlockedSongRefs
import moe.rukamori.archivetune.shared.domain.settings.HideExplicitKey
import moe.rukamori.archivetune.shared.domain.settings.HideVideoKey
import moe.rukamori.archivetune.shared.domain.settings.ContentCountryKey
import moe.rukamori.archivetune.shared.domain.settings.ContentLanguageKey
import moe.rukamori.archivetune.shared.domain.settings.DataSyncIdKey
import moe.rukamori.archivetune.shared.domain.settings.InnerTubeCookieKey
import moe.rukamori.archivetune.shared.domain.settings.PlaybackPitchKey
import moe.rukamori.archivetune.shared.domain.settings.PlaybackSpeedKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerVolumeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.PreventDuplicateTracksInQueueKey
import moe.rukamori.archivetune.shared.domain.settings.TEMPO_PITCH_VALUE_MAX
import moe.rukamori.archivetune.shared.domain.settings.TEMPO_PITCH_VALUE_MIN
import moe.rukamori.archivetune.shared.domain.settings.TempoPitchRememberedKey
import moe.rukamori.archivetune.shared.domain.settings.QueueAddAfterManualKey
import moe.rukamori.archivetune.shared.domain.settings.QueueLockedKey
import moe.rukamori.archivetune.shared.domain.settings.UseLoginForBrowse
import moe.rukamori.archivetune.shared.domain.settings.VisitorDataKey
import moe.rukamori.archivetune.shared.domain.settings.WebClientPoTokenEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.DesktopPoTokenHelperEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.YtmSyncKey
import moe.rukamori.archivetune.shared.ui.host.HostLyrics
import moe.rukamori.archivetune.shared.ui.host.LibraryContentOwner
import moe.rukamori.archivetune.shared.ui.host.ListenHistoryDriver
import moe.rukamori.archivetune.shared.ui.host.PodcastProgressDriver
import moe.rukamori.archivetune.shared.ui.host.SponsorBlockDriver
import moe.rukamori.archivetune.shared.ui.host.TidalSessionRefresh
import moe.rukamori.archivetune.shared.ui.session.LyricsSurfaceRuntime
import moe.rukamori.archivetune.shared.ui.settings.DesktopSettingsStore
import moe.rukamori.archivetune.shared.ui.settings.ProviderAccountLifecycle
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore
import moe.rukamori.archivetune.shared.ui.settings.providerAccountScope

private const val TELEGRAM_DIRECTORY = "telegram"
private const val DOWNLOAD_DIRECTORY = "downloads"
private const val STORED_DOWNLOAD_ORIGIN = "DOWNLOAD"
private const val POSITION_CHECKPOINT_INTERVAL_MS = 10_000L

/**
 * Builds the desktop's owners over the stores opened by [DesktopSessionGraph.open].
 * Feature owners belong here so the graph remains a small lifetime and command facade.
 * Construction failures go back to the graph, which releases the open stores.
 */
internal suspend fun assembleDesktopSessionOwners(
    scope: CoroutineScope,
    directory: Path,
    ordinary: DesktopSettingsStore,
    vault: VaultSettingsStore,
    database: PortableStateDatabase,
    http: HttpClient,
    network: DesktopNetworkOwner,
): DesktopSessionGraph {
    val accounts = VaultProviderAccountStore(vault)
    // Verified runtime leases (Qobuz lists, the streaming Spotify profile) live one layer above the persisted slots.
    val sourceAdmissions = DesktopSourceAdmissions(accounts, accounts)
    val accountLifecycle = ProviderAccountLifecycle(accounts, vault)
    val tidalAuth = TidalAccountAuth(
        http,
        device = DesktopCredentials.tidalDeviceClient,
        pkce = DesktopCredentials.tidalPkceClient,
        nowMs = System::currentTimeMillis,
    )
    val tidalSessionRefresh = TidalSessionRefresh(tidalAuth, accountLifecycle, vault, preferences = { vault.snapshots.value })
    val textOrder = deviceTextOrder()
    val songMetadata = moe.rukamori.archivetune.shared.data.persistence.RoomSongMetadataRepository(database.songMetadataOverrides())
    val library = RoomLibraryRepository(database.library(), listens = database.listens(), songMetadata = songMetadata)
    val artistBlocks = RoomArtistBlockRepository(database.artistBlocks(), nowMs = { System.currentTimeMillis() })
    val blockedQueueArtists = artistBlocks.observeBlockedArtists().map { refs ->
        refs.filter { it.provider == ContentProvider.YOUTUBE.id }.mapTo(HashSet()) { it.id }
    }
    val queueArtistBlocks = blockedQueueArtists.stateIn(scope, SharingStarted.Eagerly, blockedQueueArtists.first())
    val history = RoomListeningHistory(
        database.listens(),
        offsetMsAt = { epochMs -> TimeZone.getDefault().getOffset(epochMs).toLong() },
    )
    // One port reads the accounts and cookies the settings UI writes, so every admission sees the same owner.
    // A command the playback thread cannot apply publishes one PlaybackFailure; its message is the user-visible,
    // localized reason from resources so a broken command never looks like a play button that does nothing.
    val playback = DesktopPlaybackPort(
        accounts = sourceAdmissions,
        cookies = sourceAdmissions,
        commandFailureText = { DesktopStrings["playback_failed"] },
    )
    vault.snapshots.value?.get(PlayerVolumeKey)?.takeIf { it.isFinite() }?.let { playback.setVolume(it.coerceIn(0f, 1f)) }
    val player = MusicPlayerController(playback, scope)
    // The opt-in "remember speed and pitch" restore: without the flag the port keeps neutral parameters.
    vault.snapshots.value?.takeIf { it[TempoPitchRememberedKey] == true }?.let { persisted ->
        persisted[PlaybackSpeedKey]
            ?.takeIf(Float::isFinite)
            ?.coerceIn(TEMPO_PITCH_VALUE_MIN, TEMPO_PITCH_VALUE_MAX)
            ?.let(player::setPlaybackSpeed)
        persisted[PlaybackPitchKey]
            ?.takeIf(Float::isFinite)
            ?.coerceIn(TEMPO_PITCH_VALUE_MIN, TEMPO_PITCH_VALUE_MAX)
            ?.let(player::setPlaybackPitch)
    }
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
    val audioSources = desktopAudioSources(
        http = http,
        accounts = accounts,
        preferences = { vault.snapshots.value },
        admissions = sourceAdmissions,
        tidalSessionRefresh = tidalSessionRefresh,
        pool = playbackPool,
        cacheDirectory = directory.resolve("cache/librespot"),
    )
    // One BotGuard owner feeds the primary walk, the InnerTubeX provider and settings generation,
    // as Android's BotGuardTokenGenerator object feeds every resolver there. On a build with empty
    // BotGuard keys the managed helper supplies the tokens; its own switch defaults on so a stock
    // install is not left untokened. An explicitly configured BotGuard deployment mints by default;
    // a blank build leaves the stored web-client preference in charge.
    val localPoTokenHelperEnabled: () -> Boolean = {
        vault.snapshots.value?.get(DesktopPoTokenHelperEnabledKey) ?: true
    }
    val poTokens = DesktopPoTokenOwner(
        http = http,
        enabled = { vault.snapshots.value?.get(WebClientPoTokenEnabledKey) ?: DesktopCredentials.botGuardConfigured },
        localHelper = desktopLocalPoTokenHelper(
            scope = scope,
            instanceDirectory = directory,
            enabled = localPoTokenHelperEnabled,
            log = ::desktopDiagnostic,
        ),
        localHelperEnabled = localPoTokenHelperEnabled,
    )
    val youtubeResolver = DesktopYouTubeResolver(
        http,
        preferences = { vault.snapshots.value },
        search = youtubeTransport,
        signIn = { vault.snapshots.value.youTubeSignIn() },
        poTokens = poTokens,
        // A resolved googlevideo URL is reused only for the same video, quality, account and route, and only while its
        // own `expire` still leaves the engine room to read it. A rotating pool cannot name the route that will read a
        // cached URL, so it answers null and the resolver caches nothing for the length of that rotation.
        cache = DesktopResolvedStreamCache(),
        networkScope = { network.routeScope() },
    )
    // The shipped app warms BotGuard once the auth state names a session, so a cold mint never
    // lands inside a bounded tier; the same warm runs off the graph scope and stays a no-op while
    // the poToken preference is off or no session id exists yet.
    scope.launch {
        val signIn = vault.snapshots.value.youTubeSignIn()
        val sessionId =
            poTokenSessionId(
                signIn?.dataSyncId,
                signIn?.visitorData,
                hasLoginContext = signIn != null && !signIn.dataSyncId.isNullOrBlank(),
            )
        if (sessionId != null) poTokens.preWarm(sessionId)
    }
    // One resolver for playback and downloads alike, over the same providers and source chain.
    val audioResolver = OrderedAudioResolver(
        providers = audioSources.providers,
        youtube = youtubeResolver,
    )
    val storedIndex = RoomStoredSongIndex(database.storedSongs())
    val downloadStore = JvmDownloadRepository(
        root = directory.resolve(DOWNLOAD_DIRECTORY),
        client = http,
        index = storedIndex,
    )
    val songCache = DesktopSongCache(directory.resolve("cache/songs"), http, database.storedSongs(), storedIndex,
        playback, { vault.snapshots.value }, scope)
    // A committed download answers before the network does, so a stored song keeps playing offline.
    val storedFile: suspend (String, String) -> java.nio.file.Path? = { provider, mediaId ->
        var found: java.nio.file.Path? = null
        for (row in database.storedSongs().rows(STORED_DOWNLOAD_ORIGIN)) {
            if (row.provider != provider || row.mediaId != mediaId) continue
            val source = runCatching { AudioSourceType.valueOf(row.source) }.getOrNull() ?: continue
            val quality = row.quality ?: continue
            found = downloadStore.find(DownloadKey(mediaId, source, quality, provider))
            if (found != null) break
        }
        found ?: songCache.fileOf(provider, mediaId)
    }
    val locators = DesktopQueueLocatorResolver(
        audio = audioResolver,
        port = playback,
        preferences = { vault.snapshots.value },
        storedFile = storedFile,
        cacheAudio = songCache::consider,
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
    // One router feeds the queue and the chat song picker, so a pick resolves exactly what playback resolves.
    val contentRouter = ContentRepositoryRouter(listOf(TypedRoomLibraryRepository(library), youtubeContent))
    val blockedSongs = {
        parseBlockedSongRefs(vault.snapshots.value?.get(BlockedSongIdsKey).orEmpty()).toSet()
    }
    // The tail ranks the page it already fetched against the same store, block list and local clock the UI shows.
    val queueRecommendation = QueueTailRecommendation(
        signals = PortableRecommendationSignals(
            listens = database.listens(),
            artistBlocks = database.artistBlocks(),
            blockedSongRefs = blockedSongs,
        ),
        nowMs = System::currentTimeMillis,
        offsetMsAt = { epochMs -> TimeZone.getDefault().getOffset(epochMs).toLong() },
    )
    val owner = PortableQueueOwner(
        player = player,
        scope = scope,
        content = contentRouter,
        locators = locators,
        keepManualQueueInOrder = vault.snapshot()[QueueAddAfterManualKey] ?: true,
        repeatMode = { repeatModeOf(vault.snapshots.value) },
        autoSkipNextOnError = { vault.snapshots.value?.get(AutoSkipNextOnErrorKey) ?: false },
        preventDuplicateTracksInQueue = { vault.snapshots.value?.get(PreventDuplicateTracksInQueueKey) ?: false },
        queueLocked = { vault.snapshots.value?.get(QueueLockedKey) ?: false },
        autoLoadMoreEnabled = { vault.snapshots.value?.get(AutoLoadMoreKey) ?: true },
        autoLoadMoreChanges = vault.snapshots.map { it?.get(AutoLoadMoreKey) ?: true },
        hideExplicit = { vault.snapshots.value?.get(HideExplicitKey) ?: false },
        hideVideo = { vault.snapshots.value?.get(HideVideoKey) ?: false },
        blockedArtistIds = { queueArtistBlocks.value },
        blockedSongRefs = blockedSongs,
        handoffEngine = DesktopCrossfadeHandoff(playback, vault.snapshots, handoffHold),
        songMetadata = songMetadata,
        recommendation = queueRecommendation,
    )
    val queue = TypedDelegatingQueueController(owner, RoomQueueCheckpointStore(database.checkpoints(), database.library()))
    try {
        queue.resume()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        desktopDiagnostic("saved queue was not restored: ${failure.javaClass.simpleName}")
    }
    // The startup reconcile drops listed rows whose files are gone, as the store's contract requires.
    scope.launch {
        try {
            downloadStore.reconcile()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            desktopDiagnostic("download reconciliation failed: ${failure.javaClass.simpleName}")
        }
    }
    val metadata = combine(queue.state, player.snapshot, vault.snapshots) { state, snapshot, preferences ->
        state.metadataFor(snapshot?.occurrenceId, repeatModeOf(preferences))
    }.stateIn(scope, SharingStarted.Eagerly, null)
    val transport = combine(queue.state, vault.snapshots) { state, preferences ->
        DesktopTransportState(
            shuffleEnabled = state.shuffleEnabled,
            repeat = repeatModeOf(preferences),
            volume = preferences?.get(PlayerVolumeKey)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
        )
    }.stateIn(scope, SharingStarted.Eagerly, DesktopTransportState())
    val listenDriver = ListenHistoryDriver(
        scope = scope,
        player = player,
        queue = queue.state,
        history = history,
        preferences = vault.snapshots,
        report = ::desktopDiagnostic,
    )
    // Written from the same snapshots; close() flushes the open episode before the database is released.
    val progressDriver = PodcastProgressDriver(
        scope = scope,
        player = player,
        queue = queue.state,
        store = RoomPodcastProgressStore(database.podcastProgress()),
        report = ::desktopDiagnostic,
    )
    // Lives for the app scope: it holds no state the graph must close and only reads published snapshots.
    SponsorBlockDriver(
        scope = scope,
        player = player,
        queue = queue.state,
        resolved = owner.loadedRequest,
        preferences = vault.snapshots,
        repository = KtorSponsorBlockRepository(http),
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
    // The build-time Lyprio pair is the only endpoint the provider can have; there is no user setting.
    val lyricsStore = DesktopLyricsStore(directory)
    val lyrics = HostLyrics(
        scope = scope,
        http = http,
        settings = vault,
        player = player,
        queue = queue.state,
        networkAvailable = { true },
        report = ::desktopDiagnostic,
        lyprioBuildBaseUrl = DesktopCredentials.lyprioBaseUrl,
        lyprioBuildHmacKey = DesktopCredentials.lyprioHmacKey,
        readSaved = lyricsStore::saved,
        saveFetched = { item, raw -> lyricsStore.fetched(item, raw); Unit },
        extraProviderNames = setOf("YouTube Music", "YouTube Subtitle"),
        extraProviders = { listOf(moe.rukamori.archivetune.lyrics.YouTubeLyricsProvider,
            moe.rukamori.archivetune.lyrics.YouTubeSubtitleLyricsProvider) },
    )
    val pack = try {
        jvmJapaneseLanguagePackManager(
            scope = scope,
            workerDispatcher = Dispatchers.IO,
            snapshots = vault.snapshots,
            editSettings = { edit -> vault.edit(edit); Unit },
            networkAvailable = { true },
            instanceDirectory = directory,
            http = http,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A pack that cannot be constructed leaves Japanese romanization absent; settings stay ready.
        null
    }
    // One limiter paces lyrics, settings probes and top-mix generation as a single AI caller.
    val aiRateLimiter = AiRateLimiter()
    val lyricsSurface = LyricsSurfaceRuntime(
        scope = scope,
        store = vault,
        service = KtorAiTextService(http, aiRateLimiter),
        lyrics = lyrics.current,
        packTokenizer = pack?.tokenizer ?: kotlinx.coroutines.flow.flowOf(null),
        transliterator = IcuLyricsTransliterator(),
        identitySalt = "desktop",
        report = ::desktopDiagnostic,
    )
    lyricsSurface.start()
    val lyricsEdits = moe.rukamori.archivetune.shared.ui.menu.LyricsMenuEdits { before, after, current ->
        val item = queue.state.value.entries.firstOrNull { it.occurrenceId == before.mediaId }?.item
        item?.let { lyricsStore.compareAndSet(it, before, after, current) }?.also { lyrics.retry() }
    }
    moe.rukamori.archivetune.shared.ui.session.LyricsTranslationPersistence(scope, lyricsSurface.sessions, lyricsEdits,
        read = { occurrenceId -> queue.state.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item
            ?.let { lyricsStore.snapshot(it, occurrenceId) } },
        onFailure = { desktopDiagnostic("AI lyrics durable edit was refused") })
    // App start is the one foreground event a desktop host has; the pack consumes it once.
    scope.launch { pack?.onForeground() }
    // Built after the settings owner is open, so the restored room session and blocked list read real values.
    val listenTogether = DesktopListenTogether(
        parentScope = scope,
        settings = vault,
        player = player,
        queue = queue,
        // A host's volume is applied to the engine only, so the user's own saved level is not overwritten.
        setVolume = { level -> if (level.isFinite()) playback.setVolume(level.coerceIn(0f, 1f)) },
        content = contentRouter,
        picture = DesktopListenTogetherPicture(directory),
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
            // Checkpoints tick only while playWhenReady is set; a paused session sleeps until play.
            player.snapshot.map { it?.playWhenReady == true }.distinctUntilChanged().collectLatest { playing ->
                while (playing) {
                    delay(POSITION_CHECKPOINT_INTERVAL_MS)
                    checkpointNow(queue)
                }
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
    // The library feature owners sit on the same library, history, queue and download store as above.
    val localMediaOwner = DesktopLocalMediaOwner(
        scanner = JvmLocalMediaScanner(),
        settings = vault,
        queue = queue,
        scope = scope,
        report = ::desktopDiagnostic,
    )
    val downloadsOwner = DesktopDownloadsOwner(
        playback = playback,
        exportTags = { item, file ->
            val document = lyrics.current.value
            val activeItem = queue.state.value.entries.firstOrNull { it.occurrenceId == document?.occurrenceId }?.item
            tagDesktopExport(http, item, lyricsStore.saved(item)?.raw ?: document?.raw?.takeIf { activeItem?.ref == item.ref }, file)
        },
        downloads = downloadStore,
        storedSongs = database.storedSongs(),
        resolver = audioResolver,
        library = library,
        settings = vault,
        queue = queue,
        scope = scope,
        report = ::desktopDiagnostic,
    )
    val statisticsOwner = DesktopStatisticsOwner(
        statisticsRepository = history,
        history = history,
        scope = scope,
    )
    val mediaInfoOwner = DesktopMediaInfoOwner(
        library = library,
        downloads = downloadsOwner,
        localMedia = localMediaOwner,
        playingMediaId = {
            val state = owner.state.value
            state.entries.firstOrNull { it.occurrenceId == state.currentOccurrenceId }?.item?.ref?.id
        },
    )
    // The override folders sit directly in the instance directory, as Android keeps them in its files directory.
    val customArtworkFiles = JvmCustomArtworkFiles(directory)
    val customArtwork = FileCustomArtworkRepository(customArtworkFiles)
    // The backup file picker and the graph property each own a thin export facade over the same database.
    val playlistExport = PlaylistExportOwner(RoomPlaylistExportSource(database.library()))
    val graph = DesktopSessionGraph(
        youtubeResolver = youtubeResolver,
        poTokens = poTokens,
        instanceDirectory = directory,
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
        radio = DesktopRadioOwner(owner),
        metadata = metadata,
        transport = transport,
        textOrder = textOrder,
        libraryContent = libraryContent,
        owner = owner,
        playback = playback,
        ordinarySettings = ordinary,
        database = database,
        listenDriver = listenDriver,
        progressDriver = progressDriver,
        lastFm = lastFm,
        lyrics = lyrics,
        japaneseLanguagePack = pack,
        lyricsSurface = lyricsSurface,
        lyricsStore = lyricsStore,
        lyricsEdits = lyricsEdits,
        aiRateLimiter = aiRateLimiter,
        listenerPresence = listenerPresence,
        locators = locators,
        youtubeContent = youtubeContent,
        customArtwork = customArtwork,
        customArtworkFiles = customArtworkFiles,
        http = http,
        network = network,
        playbackPool = playbackPool,
        audioSources = audioSources,
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
            playlistExport = playlistExport,
            playlistImport = PlaylistImportOwner(
                library = library,
                search = YouTubePlaylistImportSearch(youtubeContent),
            ),
            playlistRows = { libraryContent.playlistRows().first() },
            report = ::desktopDiagnostic,
        ),
        storedSongIndex = storedIndex,
        downloadStore = downloadStore,
        songCache = songCache,
        downloads = downloadsOwner,
        localMedia = localMediaOwner,
        statistics = statisticsOwner,
        mediaInfo = mediaInfoOwner,
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
    network.start(scope)
    // The startup playback self-check. The engine warm answers whether the library loaded; this adds the output device
    // and the attestation path, so a host that cannot make a sound names which of the three failed in the window
    // instead of playing nothing on the first press of play.
    scope.launch {
        DesktopPlaybackSelfCheck(attestation = { poTokens.attestationStatus() }, report = graph::notify).run()
    }
    lastFm.start()
    lyrics.start()
    listenerPresence.start()
    playbackPool.start()
    listenTogether.start()
    graph.backup.start()
    return graph
}

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
    // The content locale the App applies at launch and restores from the same two preferences: the device's country
    // and language, validated like Android's code maps before a user's pinned choice replaces them. A region-less or
    // non-ISO host locale (en-001, und) never reaches the wire — YouTube rejects it with a 400.
    val device = Locale.getDefault()
    YouTube.locale = YouTubeLocale(
        gl = snapshot?.get(ContentCountryKey)?.takeIf { it != "system" }
            ?: device.country.takeIf { it.length == 2 }
            ?: "US",
        hl = snapshot?.get(ContentLanguageKey)?.takeIf { it != "system" }
            ?: device.language.takeIf { it.length == 2 }
            ?: "en",
    )
}
