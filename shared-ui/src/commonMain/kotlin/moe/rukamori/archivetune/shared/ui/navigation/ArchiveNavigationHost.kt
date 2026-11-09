package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.home.HomeClassicAtmosphereBackground
import moe.rukamori.archivetune.shared.ui.home.HomeFeedDesign
import moe.rukamori.archivetune.shared.ui.home.HomePageHeader
import moe.rukamori.archivetune.shared.ui.home.HomeScreen
import moe.rukamori.archivetune.shared.ui.home.HomeSource
import moe.rukamori.archivetune.shared.ui.home.HomeWelcomeHeader
import moe.rukamori.archivetune.shared.ui.home.QqHomeScreen
import moe.rukamori.archivetune.shared.ui.home.variants.BitChordHomeVariant
import moe.rukamori.archivetune.shared.ui.home.variants.NatuneClassicHomeVariant
import moe.rukamori.archivetune.shared.ui.home.variants.SpotifyHomeVariant
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.*
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.*
import androidx.compose.ui.window.PopupProperties
import moe.rukamori.archivetune.shared.ui.screens.commenttogether.CommentTogetherBoundScreen
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.MediaInfoScreen
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.TrackInfoActions
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.TrackInfoAndSpecs
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.painterResource
import moe.rukamori.archivetune.shared.ui.host.LocalAndroidPhonePresentation
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.DefaultSearchSourceKey

/**
 * Mount inside ArchiveTuneShell's page-only content slot. Root/player/menu/service owners stay outside.
 * [page] binds real state to decoded arguments; it does not implement routing or screen composition.
 * [hostScreen] is REQUIRED for M6 settings and still-unported original/native surfaces. Returning Host
 * is not evidence those M4 surfaces have been ported. No production fixture or empty renderer is supplied.
 */
@Composable
fun ArchiveNavigationHost(
    navigation: ShellNavigation,
    page: @Composable (ArchiveRoute) -> ArchiveGraphPage,
    actions: ArchiveGraphActions,
    artwork: ContentArtwork,
    newsImage: NewsImage,
    formatNewsDate: (Long) -> String,
    librarySourceSelector: @Composable () -> Unit,
    hostScreen: @Composable (ArchiveRoute, ShellNavigation) -> Unit,
    overlays: @Composable (ArchiveRoute) -> Unit,
    modifier: Modifier = Modifier,
    preferences: PreferenceSnapshot = PreferenceSnapshot.EMPTY,
    newsEntry: NewsEntry = NewsEntry(),
    /** A search shortcut asked for the field; the Search screen takes focus once and reports it consumed. */
    searchFocusRequested: Boolean = false,
    onSearchFocusConsumed: () -> Unit = {},
    settings: moe.rukamori.archivetune.shared.domain.settings.SettingsStore? = null,
    onFailure: () -> Unit = {},
) {
    NavigationEntryContent(navigation, modifier) {
        val route = requireNotNull(ArchiveRoutes.resolve(navigation.route)) { "Unregistered ArchiveTune route: ${navigation.route}" }
        val binding = page(route)
        val back = { navigation.back(); Unit }
        val settingsScope = rememberCoroutineScope()
        fun open(row: ContentOccurrence, collection: ContentRequest?, index: Int? = null) {
            if (row.item.ref.kind == ContentKind.SONG || row.item.ref.kind == ContentKind.EPISODE) {
                actions.playOccurrence(collection, QueueStart(row.item.ref, row.occurrenceId, row.setVideoId, index))
            } else navigation.open(ArchiveRoutes.content(row.item.ref))
        }
        fun content(page: PagedContentState, collection: ContentRequest?, load: () -> Unit, retry: () -> Unit) =
            ContentActions({ row -> open(row, collection, page.items.indexOfFirst { it.occurrenceId == row.occurrenceId }.takeIf { it >= 0 }) },
                actions.more, load, retry)
        fun discovery(tab: (DiscoveryTab) -> Unit, refresh: () -> Unit, retry: () -> Unit) = DiscoveryActions(
            back, tab, { navigation.open(ArchiveRoutes.browse(it)) }, { row, request -> open(row, request) }, actions.more,
            refresh, retry, { navigation.open("charts_screen") }, { navigation.open("new_release") }, { navigation.open("mood_and_genres") })
        when (binding) {
            is ArchiveGraphPage.Home -> {
                require(route.destination == ArchiveDestination.HOME)
                require(binding.source == HomeSource.YOUTUBE && binding.design in setOf(HomeFeedDesign.Classic, HomeFeedDesign.Muzo)) {
                    "Home ${binding.source} ${binding.design} binds its own variant page"
                }
                HomeScreen(binding.source, binding.availableSources, binding.sourceChange, binding.design, binding.shelves,
                    binding.page, content(binding.page, binding.page.request, binding.loadMore, binding.retry), artwork,
                    headerActions = { ArchiveEntryLinks(navigation, newsEntry) }, accountName = binding.accountName,
                    blurEffects = binding.blurEffects, activeIds = binding.activeIds, playing = binding.playing,
                    thumbnailAspectRatio = binding.thumbnailAspectRatio,
                    refreshing = binding.refreshing, onRefresh = binding.refresh,
                    chips = binding.chips, selectedChipKey = binding.selectedChipKey,
                    selectChip = binding.selectChip, showChips = binding.showChips)
            }
            is ArchiveGraphPage.HomeNatuneClassic -> {
                require(route.destination == ArchiveDestination.HOME)
                HomeVariantFrame(navigation, newsEntry, binding.source) { contentPadding ->
                    NatuneClassicHomeVariant(binding.state, binding.actions, artwork, binding.nowPlaying, binding.image,
                        contentPadding = contentPadding, thumbnailAspectRatio = binding.thumbnailAspectRatio)
                }
            }
            is ArchiveGraphPage.HomeSpotify -> {
                require(route.destination == ArchiveDestination.HOME)
                HomeVariantFrame(navigation, newsEntry, binding.source) { contentPadding ->
                    SpotifyHomeVariant(binding.state, binding.actions, artwork, binding.nowPlaying, binding.blurEffects,
                        contentPadding = contentPadding, thumbnailAspectRatio = binding.thumbnailAspectRatio)
                }
            }
            is ArchiveGraphPage.HomeBitChord -> {
                require(route.destination == ArchiveDestination.HOME)
                HomeVariantFrame(navigation, newsEntry, binding.source, atmosphere = binding.blurEffects,
                    greeting = if (LocalAndroidPhonePresentation.current) null else { { HomeWelcomeHeader(binding.accountName) } }) { contentPadding ->
                    BitChordHomeVariant(binding.state, binding.actions, artwork, binding.nowPlaying,
                        contentPadding = contentPadding, thumbnailAspectRatio = binding.thumbnailAspectRatio,
                        greeting = if (LocalAndroidPhonePresentation.current) { { HomeWelcomeHeader(binding.accountName) } } else null)
                }
            }
            is ArchiveGraphPage.HomeQq -> {
                require(route.destination == ArchiveDestination.HOME)
                HomeVariantFrame(navigation, newsEntry, binding.source) { contentPadding ->
                    QqHomeScreen(binding.state, binding.actions, artwork, binding.activeRef, binding.isPlaying,
                        contentPadding = contentPadding, refreshing = binding.refreshing)
                }
            }
            is ArchiveGraphPage.Search -> {
                require(route.destination == ArchiveDestination.SEARCH || route.destination == ArchiveDestination.SEARCH_RESULTS)
                SearchScreen(binding.query, binding.scope, binding.queryChange, binding.scopeChange,
                    { if (binding.query.isNotBlank()) navigation.open(ArchiveRoutes.search(binding.query,
                        if (binding.scope == SearchScope.Local) ContentProvider.LOCAL else binding.provider, binding.filter)) },
                    binding.voice, binding.recognition, binding.page,
                    content(binding.page, binding.page.request, binding.loadMore, binding.retry), artwork,
                    preferences = preferences,
                    focusRequested = searchFocusRequested, onFocusConsumed = onSearchFocusConsumed,
                    onBack = back.takeIf { navigation.canGoBack }, provider = binding.provider,
                    onProvider = settings?.let { store -> { provider -> settingsScope.launch {
                        try {
                            store.edit { this[DefaultSearchSourceKey] = when (provider) {
                                ContentProvider.SPOTIFY_CATALOG -> "SPOTIFY"
                                ContentProvider.APPLE -> "APPLE_MUSIC"
                                else -> "YOUTUBE"
                            } }
                            if (binding.query.isNotBlank()) navigation.open(ArchiveRoutes.search(binding.query, provider, binding.filter))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { onFailure() }
                    }; Unit } },
                    discovery = binding.discovery?.state,
                    discoveryActions = binding.discovery?.let { discovery(it.tab, it.refresh, it.retry) })
            }
            is ArchiveGraphPage.Library -> {
                require(route.destination == ArchiveDestination.LIBRARY)
                LibraryScreen(binding.filter, binding.order, binding.filterChange, binding.page,
                    content(binding.page, binding.page.request, binding.loadMore, binding.retry), artwork,
                    librarySourceSelector, { ArchiveEntryLinks(navigation, newsEntry, binding.myTopSize) }, mixHeader = binding.mixHeader,
                    settings = settings, preferences = preferences, onFailure = onFailure)
            }
            is ArchiveGraphPage.Discovery -> {
                require(route.destination == ArchiveDestination.DISCOVERY)
                DiscoveryScreen(binding.state, discovery(binding.tab, binding.refresh, binding.retry), artwork)
            }
            is ArchiveGraphPage.Charts -> {
                require(route.destination == ArchiveDestination.CHARTS)
                ChartsScreen(binding.sections, binding.loading, binding.failed, binding.continuation,
                    ChartsActions(back, { navigation.open(ArchiveRoutes.browse(it)) }, { row, request -> open(row, request) },
                        actions.more, binding.retry, binding.loadMore), artwork)
            }
            is ArchiveGraphPage.Moods -> {
                require(route.destination == ArchiveDestination.MOODS)
                MoodGenresScreen(binding.moods, binding.loading, binding.failed, back,
                    { navigation.open(ArchiveRoutes.browse(it)) }, binding.retry)
            }
            is ArchiveGraphPage.Browse -> {
                require(route.destination in setOf(ArchiveDestination.BROWSE, ArchiveDestination.YOUTUBE_BROWSE))
                BrowseScreen(binding.title, binding.page, content(binding.page, binding.collection, binding.loadMore, binding.retry), back, artwork)
            }
            is ArchiveGraphPage.Releases -> {
                require(route.destination == ArchiveDestination.RELEASES)
                NewReleasesScreen(binding.state, ReleasesActions(back, binding.category, binding.query,
                    { navigation.open(ArchiveRoutes.content(it)) }, binding.more, binding.refresh), artwork)
            }
            is ArchiveGraphPage.News -> {
                require(route.destination == ArchiveDestination.NEWS)
                NewsScreen(binding.state, NewsActions(back, binding.query, binding.refresh,
                    { navigation.open(ArchiveRoutes.article(it)) }, binding.markRead), newsImage, formatNewsDate)
            }
            is ArchiveGraphPage.Article -> {
                require(route.destination == ArchiveDestination.ARTICLE)
                NewsArticleScreen(binding.state, back, binding.retry, actions.openExternal, newsImage, formatNewsDate)
            }
            is ArchiveGraphPage.Podcasts -> {
                require(route.destination == ArchiveDestination.PODCASTS)
                PodcastBrowseScreen(binding.state, PodcastBrowseActions(back, binding.query, binding.subscriptionsOnly,
                    { navigation.open(ArchiveRoutes.content(it)) }, binding.subscribe, binding.retry, binding.loadMore), artwork)
            }
            is ArchiveGraphPage.Podcast -> {
                require(route.destination == ArchiveDestination.PODCAST)
                PodcastScreen(binding.state, PodcastScreenActions(back, actions.playCollection, binding.subscribe, binding.retry, binding.loadMore), artwork)
            }
            is ArchiveGraphPage.LocalSongs -> {
                require(route.destination == ArchiveDestination.LOCAL_SONGS)
                val a = binding.actions
                LocalSongsScreen(binding.state, LocalSongsActions(back, a.query, a.sort, a.requestPermission, a.scan,
                    a.minimumDuration, a.pickFolder, a.removeFolder, a.play, a.retry, a.more, a.importFiles), artwork)
            }
            is ArchiveGraphPage.Uploads -> {
                require(route.destination == ArchiveDestination.UPLOADED_SONGS)
                val a = binding.actions
                UploadedSongsScreen(binding.state,
                    UploadsActions(back, a.upload, a.delete, a.play, a.retry, a.loadMore), artwork)
            }
            is ArchiveGraphPage.Downloads -> {
                require(route.destination == ArchiveDestination.DOWNLOADS)
                val a = binding.actions
                DownloadLibraryScreen(binding.state, moe.rukamori.archivetune.shared.ui.screens.DownloadActions(
                    a.tab, a.query, back, a.retry, a.pause, a.resume, a.remove, a.open), artwork)
            }
            is ArchiveGraphPage.History -> {
                require(route.destination == ArchiveDestination.HISTORY)
                val a = binding.actions
                HistoryScreen(binding.state, HistoryActions(a.source, a.query, back, a.play, a.more, a.toggleSelection,
                    a.selectAllVisible, a.clearSelection, a.removeEvents, a.retry, a.loadMore), artwork)
            }
            is ArchiveGraphPage.Playlists -> {
                require(route.destination == ArchiveDestination.PLAYLISTS)
                val a = binding.actions
                PlaylistCollectionScreen(binding.state, PlaylistCollectionActions(back, a.query, a.sort, a.showHidden,
                    a.filterTag, a.grid, a.lock, a.create, { navigation.open("library/tags") },
                    { navigation.open(ArchiveRoutes.content(it)) }, a.more, a.assignTags, a.exportCsv, a.move, a.retry), artwork)
            }
            is ArchiveGraphPage.Statistics -> {
                require(route.destination == ArchiveDestination.STATISTICS)
                val a = binding.actions
                StatisticsScreen(binding.state, StatisticsActions(back, a.source, a.period, a.range, a.play,
                    { navigation.open(ArchiveRoutes.content(it)) }, a.more, a.shuffle,
                    { navigation.open("year_in_music?year=$it") }, a.retry),
                    artwork, binding.formatDuration, binding.formatWeekday)
            }
            is ArchiveGraphPage.YearInMusic -> {
                require(route.destination == ArchiveDestination.RECAP)
                val a = binding.actions
                YearInMusicScreen(binding.state, YearInMusicActions(back, a.year, a.retry, a.share, a.songMenu),
                    artwork, binding.formatDuration)
            }
            is ArchiveGraphPage.Tags -> {
                require(route.destination == ArchiveDestination.TAGS)
                val a = binding.actions
                PlaylistTagsScreen(binding.state, PlaylistTagsActions(back, a.retry, a.create, a.edit,
                    a.name, a.color, a.save, a.cancelEdit, a.delete, a.bulkAssign))
            }
            is ArchiveGraphPage.Artist -> {
                require(route.destination == ArchiveDestination.ARTIST)
                ArtistScreen(binding.artist, binding.page, binding.pageFailed, binding.library, binding.blockState,
                    binding.nowPlaying, artwork, NavigationArtistAlbumHost(navigation, binding.host))
            }
            is ArchiveGraphPage.ArtistSongs -> {
                require(route.destination == ArchiveDestination.ARTIST_SONGS)
                ArtistSongsScreen(binding.artistName, binding.songs, binding.sort, binding.nowPlaying, artwork,
                    NavigationArtistAlbumHost(navigation, binding.host))
            }
            is ArchiveGraphPage.ArtistAlbums -> {
                require(route.destination == ArchiveDestination.ARTIST_ALBUMS)
                ArtistAlbumsScreen(binding.title, binding.albums, binding.nowPlaying, artwork,
                    NavigationArtistAlbumHost(navigation, binding.host))
            }
            is ArchiveGraphPage.ArtistItems -> {
                require(route.destination == ArchiveDestination.ARTIST_ITEMS)
                ArtistItemsScreen(binding.title, binding.request, binding.layout, binding.repository, binding.nowPlaying,
                    artwork, NavigationArtistAlbumHost(navigation, binding.host))
            }
            is ArchiveGraphPage.Album -> {
                require(route.destination == ArchiveDestination.ALBUM)
                AlbumScreen(binding.album, binding.page, binding.loadState, binding.downloads, binding.nowPlaying, artwork,
                    NavigationArtistAlbumHost(navigation, binding.host), canvas = binding.canvas, canvasSlot = binding.canvasSlot)
            }
            is ArchiveGraphPage.Detail -> {
                require(route.destination in setOf(ArchiveDestination.ALBUM, ArchiveDestination.ARTIST, ArchiveDestination.PLAYLIST))
                val a = binding.actions
                DetailScreen(binding.kind, binding.header, binding.page, DetailActions(a.content, back,
                    a.playAll, a.shuffle, a.toggleLibrary, a.more), artwork, control = binding.control,
                    headerMosaicItems = binding.headerMosaicItems)
            }
            is ArchiveGraphPage.Account -> {
                require(route.destination == ArchiveDestination.ACCOUNT)
                AccountScreen(binding.state, AccountActions(back, binding.select,
                    { navigation.open(ArchiveRoutes.content(it.ref)) }, binding.more), artwork)
            }
            is ArchiveGraphPage.Login -> {
                require(route.destination == ArchiveDestination.LOGIN)
                YouTubeLoginScreen(binding.state, binding.actions.copy(back = back), binding.webSurface)
            }
            is ArchiveGraphPage.TelegramBots -> {
                require(route.destination == ArchiveDestination.TELEGRAM_BOTS)
                // This copy replaces the binding's ready-gated open, so the same gate is repeated before navigating.
                TelegramBotsScreen(binding.state, binding.actions.copy(back = back,
                    open = { bot -> if (binding.state.ready && !binding.state.loadFailed) navigation.open("telegram/bot/${bot.id}") }))
            }
            is ArchiveGraphPage.TelegramBrowse -> {
                require(route.destination in setOf(ArchiveDestination.TELEGRAM, ArchiveDestination.TELEGRAM_BROWSE))
                TelegramBrowseScreen(binding.state, binding.actions.copy(back = back), binding.avatar,
                    onPlaylistOpened = { navigation.open(ArchiveRoutes.content(ContentRef("telegram", ContentKind.PLAYLIST, it))) })
            }
            is ArchiveGraphPage.TelegramBotChat -> {
                require(route.destination == ArchiveDestination.TELEGRAM_BOT_CHAT)
                TelegramBotChatScreen(binding.state, binding.actions.copy(back = back), binding.artwork)
            }
            is ArchiveGraphPage.ListenTogether -> {
                require(route.destination == ArchiveDestination.LISTEN_TOGETHER)
                ListenTogetherScreen(binding.state, binding.actions.copy(back = back), artwork, binding.avatar,
                    showHeader = binding.showHeader)
            }
            is ArchiveGraphPage.CommentTogether -> {
                require(route.destination == ArchiveDestination.LISTEN_TOGETHER_CHAT)
                CommentTogetherBoundScreen(binding.owner, binding.avatar, binding.thumbnail)
            }
            is ArchiveGraphPage.TrackInfo -> {
                require(route.destination == ArchiveDestination.TRACK_INFO)
                // The track-info body has no close action of its own, so the page supplies the back control here. The sheet
                // hands video details to media info, and the route stays on this stack so that back returns to it.
                Column(Modifier.fillMaxSize().padding(LocalShellContentPadding.current)) {
                    ManagementHeader("", back)
                    TrackInfoAndSpecs(binding.state, TrackInfoActions(onShowVideoDetails = {
                        navigation.open(ArchiveRoutes.mediaInfo(binding.state.trackId))
                    }), Modifier.weight(1f), artwork = binding.artwork)
                }
            }
            is ArchiveGraphPage.MediaInfo -> {
                require(route.destination == ArchiveDestination.MEDIA_INFO)
                MediaInfoScreen(binding.state, binding.actions.copy(onClose = back), artwork = binding.artwork,
                    liveAudioChain = binding.liveAudioChain)
            }
            is ArchiveGraphPage.MusicRecognition -> {
                require(route.destination == ArchiveDestination.MUSIC_RECOGNITION)
                MusicRecognitionScreen(binding.state, binding.actions.copy(back = back,
                    search = { navigation.open(ArchiveRoutes.search(it, ContentProvider.YOUTUBE)) },
                    openUri = actions.openExternal,
                    // The binding's openDetails is the owner's action, which settles the Recognized result before the host
                    // navigates. Replacing it with a bare navigation would admit the same result again on re-entry.
                    openDetails = { stableKey ->
                        binding.actions.openDetails(stableKey)
                        navigation.open(ArchiveRoutes.musicRecognitionDetails(stableKey))
                    }),
                    binding.artwork, autoStartRequest = binding.autoStartRequest)
            }
            is ArchiveGraphPage.MusicRecognitionDetails -> {
                require(route.destination == ArchiveDestination.MUSIC_RECOGNITION_DETAILS)
                MusicRecognitionDetailsScreen(binding.track, binding.actions.copy(back = back,
                    // Baseline "listen again" returns to the recognition entry already on the stack and listens once there.
                    listenAgain = { navigation.reopen(ArchiveRoutes.musicRecognition(navigation.nextRequestToken())) { ArchiveRoutes.isMusicRecognition(it) } },
                    search = { navigation.open(ArchiveRoutes.search(it, ContentProvider.YOUTUBE)) },
                    openUri = actions.openExternal), binding.artwork)
            }
            ArchiveGraphPage.Host -> hostScreen(route, navigation)
        }
        overlays(route)
    }
}

/**
 * Navigation effects of the artist and album screens. Every data, queue, library, download and menu effect stays with
 * the owner; only the back stack moves here, so the screens never reach into [ShellNavigation] themselves.
 */
private class NavigationArtistAlbumHost(
    private val navigation: ShellNavigation,
    owner: ArtistAlbumHost,
) : ArtistAlbumHost by owner {
    override fun back() { navigation.back() }
    override fun backToMain() = navigation.backToMain()
    override fun openContent(ref: ContentRef) = navigation.open(ArchiveRoutes.content(ref))
    override fun openAlbum(ref: ContentRef) = navigation.open(ArchiveRoutes.content(ref.copy(kind = ContentKind.ALBUM)))
    override fun openArtistSongs(artist: ContentRef) = navigation.open(ArchiveRoutes.artistSongs(artist))
    override fun openArtistAlbums(artist: ContentRef) = navigation.open(ArchiveRoutes.artistAlbums(artist))
    override fun openArtistSection(request: ContentRequest, title: String, layout: ArtistShelfLayout) =
        navigation.open(ArchiveRoutes.artistItems(request, title, layout))
    override fun openDownloads() = navigation.open("auto_playlist/downloaded")
}

/**
 * The Home header above a variant body. Every Home page keeps the title, More links and source switcher of the generic
 * feed, drawn once here rather than repeated by each variant. The body lays out below the header with the shell's side
 * and bottom padding; the header already carries the top inset, so it is not counted twice. [greeting] is drawn above the
 * body for variants that expect the shell to greet, and [atmosphere] draws the baseline backdrop behind all of it.
 */
@Composable
private fun HomeVariantFrame(
    navigation: ShellNavigation,
    newsEntry: NewsEntry,
    home: HomeSourceBinding,
    atmosphere: Boolean = false,
    greeting: (@Composable () -> Unit)? = null,
    body: @Composable (PaddingValues) -> Unit,
) {
    val shell = LocalShellContentPadding.current
    val direction = LocalLayoutDirection.current
    Box(Modifier.fillMaxSize()) {
        if (atmosphere) {
            if (LocalAndroidPhonePresentation.current) moe.rukamori.archivetune.shared.ui.home.HomeAtmosphereBackground()
            else HomeClassicAtmosphereBackground()
        }
        Column(Modifier.fillMaxSize()) {
            HomePageHeader(home.source, home.available, home.change, headerActions = { ArchiveEntryLinks(navigation, newsEntry) },
                modifier = Modifier.fillMaxWidth().padding(top = shell.calculateTopPadding()))
            greeting?.invoke()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                body(PaddingValues(start = shell.calculateStartPadding(direction), end = shell.calculateEndPadding(direction),
                    bottom = shell.calculateBottomPadding()))
            }
        }
    }
}

/**
 * What the More list shows for News. A build without a feed hides the entry, as the Android profile menu does, and
 * [unread] puts the Android unread dot on it.
 */
@Immutable
data class NewsEntry(val available: Boolean = true, val unread: Boolean = false)

/** One real entry-point list shared by Home and Library, rather than routes discoverable only in tests. */
@Composable
private fun ArchiveEntryLinks(navigation: ShellNavigation, newsEntry: NewsEntry, myTopSize: Int? = null) {
    var expanded by remember { mutableStateOf(false) }
    val desktop = LocalDesktopLayout.current
    Box {
        if (LocalAndroidPhonePresentation.current) {
            Row {
                IconButton(onClick = { navigation.open("settings") }, modifier = Modifier.testTag("archive-settings")) {
                    Icon(painterResource(Res.drawable.m4_icon_settings), stringResource(Res.string.m4_settings))
                }
                IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("archive-destinations")) {
                    Icon(painterResource(Res.drawable.m6_icon_account), stringResource(Res.string.m4_account))
                }
            }
        } else TextButton({ expanded = true }, Modifier.testTag("archive-destinations")) { Text(stringResource(Res.string.m4_more)) }
        DropdownMenu(expanded, { expanded = false }, properties = PopupProperties(focusable = !desktop)) {
            // The My top entry opens the list the baseline card opens. The caller passes null when that card is hidden.
            val myTop = myTopSize?.let { "top_playlist/$it" to Res.string.m4_my_top }
            listOfNotNull(
                "discovery" to Res.string.m4_discover, "mood_and_genres" to Res.string.m4_moods_genres,
                "charts_screen" to Res.string.m4_charts, "new_release" to Res.string.m4_new_releases,
                "podcasts" to Res.string.m4_podcasts, ("news" to Res.string.m4_news).takeIf { newsEntry.available },
                "local_songs" to Res.string.m4_local_songs, "auto_playlist/downloaded" to Res.string.m4_downloads,
                "uploaded_songs" to Res.string.uploads_title,
                "history" to Res.string.m4_history, "library/playlists" to Res.string.m4_playlists, myTop,
                "library/tags" to Res.string.m4_manage_tags, "stats" to Res.string.m4_statistics,
                "year_in_music" to Res.string.m4_year_in_music, "listen_together" to Res.string.m4_listen_together,
                "telegram/bots" to Res.string.m4_telegram, "telegram/browse" to Res.string.m4_telegram_browse_title,
                "account" to Res.string.m4_account,
                "settings" to Res.string.m4_settings,
            ).forEach { (route, label) ->
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = {
                    expanded = false; navigation.open(route)
                }, modifier = Modifier.testTag("archive-open-$route"),
                    trailingIcon = if (route == "news" && newsEntry.unread) {
                        { Badge(Modifier.testTag("archive-news-unread")) }
                    } else null)
            }
        }
    }
}
