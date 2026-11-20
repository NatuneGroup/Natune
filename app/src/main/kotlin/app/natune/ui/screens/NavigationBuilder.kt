/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune.ui.screens

import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.natune.constants.BackAnimationStyle
import app.natune.BuildConfig
import app.natune.constants.HomeScreenStyle
import app.natune.constants.HomeScreenStyleKey
import app.natune.constants.HomeSource
import app.natune.constants.LibraryStyle
import app.natune.constants.UpdateChannelKey
import app.natune.effectiveUpdateChannel
import app.natune.constants.UpdateChannel
import app.natune.defaultUpdateChannel
import app.natune.musicrecognition.MusicRecognitionRoute
import app.natune.utils.rememberEnumPreference
import app.natune.musicrecognition.MusicRecognitionDetailsRoute
import app.natune.ui.component.rememberLibraryStyle
import app.natune.ui.screens.BrowseScreen
import app.natune.ui.screens.artist.ArtistAlbumsScreen
import app.natune.ui.screens.artist.ArtistItemsScreen
import app.natune.ui.screens.artist.ArtistScreen
import app.natune.ui.screens.artist.ArtistSongsScreen
import app.natune.ui.screens.library.AppleMusicLibraryScreen
import app.natune.ui.screens.library.LibraryScreen
import app.natune.ui.screens.library.LocalSongScreen
import app.natune.ui.screens.library.UploadedSongsScreen
import app.natune.ui.screens.musicrecognition.MusicRecognitionScreen
import app.natune.ui.screens.musicrecognition.MusicRecognitionDetailsScreen
import app.natune.ui.screens.playlist.AutoPlaylistScreen
import app.natune.ui.screens.playlist.CachePlaylistScreen
import app.natune.ui.screens.playlist.LocalPlaylistScreen
import app.natune.ui.screens.playlist.OnlinePlaylistScreen
import app.natune.ui.screens.playlist.SpotifyPlaylistScreen
import app.natune.ui.screens.playlist.TopPlaylistScreen
import app.natune.ui.screens.podcast.PodcastRoute
import app.natune.ui.screens.podcast.PodcastScreen
import app.natune.ui.screens.search.OnlineSearchResult
import app.natune.ui.screens.search.OnlineSearchResultArgument
import app.natune.ui.screens.search.OnlineSearchProviderArgument
import app.natune.ui.screens.search.OnlineSearchResultRoute
import app.natune.ui.screens.search.OnlineSearchResultRoutePrefix
import app.natune.ui.screens.search.SearchScreen
import app.natune.ui.screens.spotifyhome.SpotifyHomeScreen
import app.natune.ui.screens.settings.AboutScreen
import app.natune.ui.screens.settings.AccountSettings
import app.natune.ui.screens.settings.AiIntegrationSettings
import app.natune.ui.screens.settings.AndroidAutoSettings
import app.natune.ui.screens.settings.AodCustomizedScreen
import app.natune.ui.screens.settings.AppearanceExtrasSettings
import app.natune.ui.screens.settings.NavigationBarSettings
import app.natune.ui.screens.settings.LIQUID_GLASS_SETTINGS_ROUTE
import app.natune.ui.screens.settings.LiquidGlassSettings
import app.natune.ui.screens.settings.AppearanceSection
import app.natune.ui.screens.settings.AppearanceSectionSettings
import app.natune.ui.screens.settings.AppearanceSettings
import app.natune.ui.screens.settings.BackupAndRestore
import app.natune.ui.screens.settings.CanvasSettings
import app.natune.ui.screens.settings.ChangelogScreen
import app.natune.ui.screens.settings.ContentSettings
import app.natune.ui.screens.settings.CrashLogDetailScreen
import app.natune.ui.screens.settings.CrashLogsScreen
import app.natune.ui.screens.settings.CustomizeBackground
import app.natune.ui.screens.settings.DebugSettings
import app.natune.ui.screens.settings.DiscordSettings
import app.natune.ui.screens.settings.ExportDownloadedSongsScreen
import app.natune.ui.screens.settings.HiddenPlaylistsScreen
import app.natune.ui.screens.settings.IconScreen
import app.natune.ui.screens.settings.IntegrationScreen
import app.natune.ui.screens.settings.ListenTogetherSettings
import app.natune.ui.screens.settings.DeviceConnectSettings
import app.natune.ui.screens.settings.InternetSettings
import app.natune.ui.screens.settings.TidalSettings
import app.natune.ui.screens.settings.QobuzSettings
import app.natune.ui.screens.settings.AmazonSettings
import app.natune.ui.screens.settings.QqMusicSettings
import app.natune.ui.screens.settings.SpotifyStreamingSettings
import app.natune.ui.screens.settings.DeezerSettings
import app.natune.ui.screens.settings.JioSettings
import app.natune.ui.screens.settings.TidalLoginScreen
import app.natune.ui.screens.settings.TIDAL_LOGIN_ROUTE
import app.natune.ui.screens.settings.QobuzLoginScreen
import app.natune.ui.screens.settings.QOBUZ_LOGIN_ROUTE
import app.natune.ui.screens.settings.AppleMusicLoginScreen
import app.natune.ui.screens.settings.AppleMusicSettings
import app.natune.ui.screens.settings.APPLE_MUSIC_LOGIN_ROUTE
import app.natune.ui.screens.settings.DeezerLoginScreen
import app.natune.ui.screens.settings.DEEZER_LOGIN_ROUTE
import app.natune.ui.screens.settings.LASTFM_LOGIN_ROUTE
import app.natune.ui.screens.settings.LastFmLoginScreen
import app.natune.ui.screens.settings.LASTFM_LIBREFM_LOGIN_ROUTE
import app.natune.ui.screens.settings.LibreFmLoginScreen
import app.natune.ui.screens.settings.TELEGRAM_LOGIN_ROUTE
import app.natune.ui.screens.settings.TelegramLoginScreen
import app.natune.ui.screens.settings.YOUTUBE_OAUTH_ROUTE
import app.natune.ui.screens.settings.YouTubeOAuthLoginScreen
import app.natune.ui.screens.settings.TelegramSettings
import app.natune.ui.screens.settings.LastFMSettings
import app.natune.ui.screens.settings.LastFmDashboardScreen
import app.natune.ui.screens.settings.LanguagePackSettings
import app.natune.ui.screens.settings.LogcatScreen
import app.natune.ui.screens.settings.LyricsAnimationSettings
import app.natune.ui.screens.settings.LyricsSettings
import app.natune.ui.screens.settings.LyricsProvidersSettings
import app.natune.ui.screens.settings.LyricsRomanisationSettings
import app.natune.ui.screens.settings.PO_TOKEN_ROUTE
import app.natune.ui.screens.settings.PalettePickerScreen
import app.natune.ui.screens.settings.PlayerSettings
import app.natune.ui.screens.settings.PoTokenScreen
import app.natune.ui.screens.settings.PrivacySettings
import app.natune.ui.screens.settings.SettingsScreen
import app.natune.ui.screens.settings.SourceSettings
import app.natune.ui.screens.settings.SpotifyCatalogSettings
import app.natune.ui.screens.settings.ModulesSettings
import app.natune.ui.screens.settings.StorageSettings
import app.natune.ui.screens.settings.DownloadsSettings
import app.natune.ui.screens.settings.ThemeCreatorScreen
import app.natune.ui.screens.settings.UpdateScreen
import app.natune.viewmodels.OnlineSearchSort

@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.navigationBuilder(
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior,
    latestVersionName: () -> String,
    disableAnimations: Boolean = false,
    onClearUpdateBadge: () -> Unit = {},
    onSearchQuery: (String) -> Unit = {},
    onVoiceSearch: () -> Unit = {},
    homeListState: LazyListState? = null,
    searchListState: LazyListState? = null,
    homeScrollConnection: NestedScrollConnection? = null,
    searchScrollConnection: NestedScrollConnection? = null,
    // A provider, not a value: the NavHost graph is built once (the builder lambda stays stable),
    // so a by-value sort would be frozen at graph-build time. Reading the provider inside the
    // destination content keeps the sort live.
    onlineSearchSort: () -> OnlineSearchSort = { OnlineSearchSort.DEFAULT },
    backAnimationStyle: () -> BackAnimationStyle = { BackAnimationStyle.PREDICTIVE },
) {
    composable(Screens.Home.route) {
        // Three separate home pages behind one tab, picked by HomeSourceKey and switched from the
        // HomeSourceToggleButton in the top app bar. They are not layered: whichever is showing
        // owns the tab, keeps its own layout style, and leaves the others exactly as they were.
        // rememberHomeSource already resolves a page whose session has gone back to YOUTUBE.
        when (rememberHomeSource()) {
            HomeSource.SPOTIFY -> {
                SpotifyHomeScreen(navController, headerScrollConnection = homeScrollConnection)
                return@composable
            }

            HomeSource.QQ -> {
                // No navController: the QQ page plays its sections rather than opening anything.
                QqHomeScreen(headerScrollConnection = homeScrollConnection)
                return@composable
            }

            // The app's own home, in whichever of its two styles is configured.
            HomeSource.YOUTUBE -> Unit
        }

        val homeScreenStyle by rememberEnumPreference(HomeScreenStyleKey, HomeScreenStyle.Default)
        when (homeScreenStyle) {
            HomeScreenStyle.RUKAMORI -> {
                NatuneClassicHomeScreen(
                    navController,
                    headerScrollConnection = homeScrollConnection,
                    listState = homeListState,
                )
            }

            HomeScreenStyle.DEFAULT -> {
                HomeScreen(
                    navController,
                    headerScrollConnection = homeScrollConnection,
                    listState = homeListState,
                )
            }
        }
    }
    composable(
        Screens.Library.route,
    ) {
        // The Apple Music Experience's library half: a large-title list of sections instead of the
        // fork's chip row. Dispatched here rather than inside LibraryScreen so the two layouts stay
        // separate screens, each owning its own scroll state, insets and section state.
        if (rememberLibraryStyle().first == LibraryStyle.APPLE_MUSIC) {
            AppleMusicLibraryScreen(navController)
        } else {
            LibraryScreen(navController)
        }
    }
    composable("listen_together") {
        ListenTogetherScreen(navController, showTopBar = false)
    }
    composable(
        route = "listen_together_from_topbar",
    ) {
        ListenTogetherScreen(navController, showTopBar = true)
    }
    composable("listen_together/chat") {
        CommentTogetherScreen(navController)
    }
    composable(Screens.Search.route) {
        SearchScreen(
            navController = navController,
            onSearchQuery = onSearchQuery,
            onVoiceSearch = onVoiceSearch,
            headerScrollConnection = searchScrollConnection,
            listState = searchListState,
        )
    }
    composable("local_songs") {
        LocalSongScreen(navController)
    }
    composable("uploaded_songs") {
        UploadedSongsScreen(navController)
    }
    composable("history") {
        HistoryScreen(navController)
    }
    composable("stats") {
        StatsScreen(navController)
    }
    composable("news") {
        NewsScreen(navController)
    }
    composable(
        route = "view_news/{newsId}",
        arguments =
            listOf(
                navArgument("newsId") { type = NavType.StringType },
            ),
    ) {
        ViewNewsScreen(navController)
    }
    composable(
        route = "year_in_music?year={year}",
        arguments =
            listOf(
                navArgument("year") {
                    type = NavType.IntType
                    defaultValue = -1
                },
            ),
    ) { backStackEntry ->
        val selectedYear = backStackEntry.arguments?.getInt("year")?.takeIf { it > 0 }
        YearInMusicScreen(
            navController = navController,
            initialYear = selectedYear,
        )
    }
    composable(MusicRecognitionRoute) {
        MusicRecognitionScreen(navController)
    }
    composable(MusicRecognitionDetailsRoute) { backStackEntry ->
        val encodedTrack = backStackEntry.arguments?.getString("encodedTrack").orEmpty()
        MusicRecognitionDetailsScreen(navController, encodedTrack)
    }
    composable(Screens.MoodAndGenres.route) {
        MoodAndGenresScreen(navController)
    }
    composable("account") {
        AccountScreen(navController, scrollBehavior)
    }
    composable("new_release") {
        NewReleaseScreen(navController, scrollBehavior)
    }
    composable("charts_screen") {
        ChartsScreen(navController)
    }
    composable(
        route = "browse/{browseId}",
        arguments =
            listOf(
                navArgument("browseId") {
                    type = NavType.StringType
                },
            ),
    ) {
        BrowseScreen(
            navController,
            scrollBehavior,
        )
    }
    composable(
        route = OnlineSearchResultRoute,
        arguments =
            listOf(
                navArgument(OnlineSearchResultArgument) {
                    type = NavType.StringType
                },
                navArgument(OnlineSearchProviderArgument) {
                    type = NavType.StringType
                    defaultValue = app.natune.constants.SearchProvider.YOUTUBE.name
                },
            ),
        // PREDICTIVE uses the NavHost's pushed-screen slides (scrubbed by the back gesture);
        // ORIGINAL keeps this route's fades.
        enterTransition = {
            if (disableAnimations) {
                fadeIn(tween(0))
            } else if (backAnimationStyle() == BackAnimationStyle.PREDICTIVE) {
                slideInHorizontally(
                    animationSpec = tween(360, easing = FastOutSlowInEasing),
                    initialOffsetX = { it },
                ) + fadeIn(tween(240, delayMillis = 40, easing = FastOutSlowInEasing))
            } else {
                fadeIn(tween(250))
            }
        },
        exitTransition = {
            if (disableAnimations) {
                fadeOut(tween(0))
            } else if (targetState.destination.route?.startsWith(OnlineSearchResultRoutePrefix) == true) {
                fadeOut(tween(200))
            } else {
                fadeOut(tween(200)) + slideOutHorizontally { -it / 2 }
            }
        },
        popEnterTransition = {
            if (disableAnimations) {
                fadeIn(tween(0))
            } else if (initialState.destination.route?.startsWith(OnlineSearchResultRoutePrefix) == true) {
                fadeIn(tween(250))
            } else {
                fadeIn(tween(250)) + slideInHorizontally { -it / 2 }
            }
        },
        popExitTransition = {
            if (disableAnimations) {
                fadeOut(tween(0))
            } else if (backAnimationStyle() == BackAnimationStyle.PREDICTIVE) {
                slideOutHorizontally(
                    animationSpec = tween(360, easing = FastOutSlowInEasing),
                    targetOffsetX = { it },
                ) + fadeOut(tween(280, easing = LinearOutSlowInEasing))
            } else {
                fadeOut(tween(200))
            }
        },
    ) {
        OnlineSearchResult(
            navController = navController,
            searchSort = onlineSearchSort(),
        )
    }
    composable(
        route = "album/{albumId}",
        arguments =
            listOf(
                navArgument("albumId") {
                    type = NavType.StringType
                },
            ),
    ) {
        AlbumScreen(navController, scrollBehavior)
    }
    composable(
        route = PodcastRoute,
        arguments =
            listOf(
                navArgument("browseId") {
                    type = NavType.StringType
                },
            ),
    ) {
        PodcastScreen(navController)
    }
    composable(
        route = "artist/{artistId}",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ArtistScreen(navController, scrollBehavior)
    }
    composable(
        route = "artist/{artistId}/songs",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ArtistSongsScreen(navController, scrollBehavior)
    }
    composable(
        route = "artist/{artistId}/albums",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        ArtistAlbumsScreen(navController, scrollBehavior)
    }
    composable(
        route = "artist/{artistId}/items?browseId={browseId}&params={params}",
        arguments =
            listOf(
                navArgument("artistId") {
                    type = NavType.StringType
                },
                navArgument("browseId") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
    ) {
        ArtistItemsScreen(navController, scrollBehavior)
    }
    composable(
        route = "online_playlist/{playlistId}",
        arguments =
            listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        OnlinePlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "local_playlist/{playlistId}",
        arguments =
            listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        LocalPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "spotify_playlist/{playlistId}",
        arguments =
            listOf(
                navArgument("playlistId") {
                    type = NavType.StringType
                },
            ),
    ) {
        SpotifyPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "auto_playlist/{playlist}?tab={tab}",
        arguments =
            listOf(
                navArgument("playlist") {
                    type = NavType.StringType
                },
                navArgument("tab") {
                    type = NavType.StringType
                    defaultValue = "downloaded"
                },
            ),
    ) {
        AutoPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "cache_playlist/{playlist}",
        arguments =
            listOf(
                navArgument("playlist") {
                    type = NavType.StringType
                },
            ),
    ) {
        CachePlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "top_playlist/{top}",
        arguments =
            listOf(
                navArgument("top") {
                    type = NavType.StringType
                },
            ),
    ) {
        TopPlaylistScreen(navController, scrollBehavior)
    }
    composable(
        route = "youtube_browse/{browseId}?params={params}",
        arguments =
            listOf(
                navArgument("browseId") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("params") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
    ) {
        YouTubeBrowseScreen(navController)
    }
    composable("settings") {
        SettingsScreen(navController, latestVersionName())
    }
    composable("settings/account") {
        AccountSettings(navController, latestVersionName())
    }
    composable("settings/hidden_playlists") {
        HiddenPlaylistsScreen(navController)
    }
    composable(
        route = "settings/appearance?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AppearanceSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/appearance/theme?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AppearanceSectionSettings(navController, AppearanceSection.THEME, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/appearance/player?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AppearanceSectionSettings(navController, AppearanceSection.PLAYER, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/appearance/interface?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AppearanceSectionSettings(navController, AppearanceSection.INTERFACE, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/appearance/extras?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AppearanceExtrasSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/appearance/navigation_bar?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        NavigationBarSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "$LIQUID_GLASS_SETTINGS_ROUTE?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        LiquidGlassSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("settings/appearance/icon") {
        IconScreen(navController)
    }
    composable(
        route = "settings/appearance/aod_customized?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AodCustomizedScreen(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("settings/appearance/palette_picker") {
        PalettePickerScreen(navController)
    }
    composable(
        route = "settings/appearance/lyrics_animations?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        LyricsAnimationSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("settings/appearance/theme_creator") {
        ThemeCreatorScreen(navController)
    }
    composable(
        route = "settings/content?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        ContentSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/lyrics?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        LyricsSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/lyrics/providers?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        LyricsProvidersSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/lyrics/romanisation?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        LyricsRomanisationSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("settings/language_packs") {
        LanguagePackSettings(navController)
    }
    composable(
        route = "settings/internet?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        InternetSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/player?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        PlayerSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable("settings/canvas") {
        CanvasSettings(navController)
    }
    composable(
        route = "settings/player/ytdlp?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val readRuntime = androidx.compose.runtime.remember(context) {
            suspend { app.natune.kmp.settings.readAndroidYtDlpRuntime(context) }
        }
        app.natune.shared.ui.settings.YtDlpRuntimeSettings(readRuntime)
    }
    composable(
        route = "settings/sources?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        SourceSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable("settings/modules") {
        ModulesSettings(navController)
    }
    composable("settings/android_auto") {
        AndroidAutoSettings(navController)
    }
    composable(
        route = "settings/storage?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        StorageSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("settings/storage/export_songs") {
        ExportDownloadedSongsScreen(navController)
    }
    composable(
        route = "settings/downloads?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        DownloadsSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/privacy?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        PrivacySettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/backup_restore?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        BackupAndRestore(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/discord?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        DiscordSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/integration?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        IntegrationScreen(navController, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/spotify?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        SpotifyCatalogSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable(route = "settings/integrations/listen_together") {
        ListenTogetherSettings(navController, scrollBehavior)
    }
    composable(route = "settings/integrations/device_connect") {
        DeviceConnectSettings(navController)
    }
    composable(
        route = "settings/tidal?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        TidalSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/qobuz?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        QobuzSettings(navController, it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/deezer?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        DeezerSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/amazon?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AmazonSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/jiosaavn?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        JioSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/qqmusic?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        QqMusicSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/spotify_streaming?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        SpotifyStreamingSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(TIDAL_LOGIN_ROUTE) {
        TidalLoginScreen(navController)
    }
    composable(QOBUZ_LOGIN_ROUTE) {
        QobuzLoginScreen(navController)
    }
    composable(DEEZER_LOGIN_ROUTE) {
        DeezerLoginScreen(navController)
    }
    composable(APPLE_MUSIC_LOGIN_ROUTE) {
        AppleMusicLoginScreen(navController)
    }
    composable(
        route = "settings/applemusic",
    ) {
        AppleMusicSettings(navController)
    }
    composable(LASTFM_LOGIN_ROUTE) {
        LastFmLoginScreen(navController)
    }
    composable(LASTFM_LIBREFM_LOGIN_ROUTE) {
        LibreFmLoginScreen(navController)
    }
    composable(
        route = "settings/telegram?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        TelegramSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(TELEGRAM_LOGIN_ROUTE) {
        TelegramLoginScreen(navController)
    }
    composable(TELEGRAM_BROWSE_ROUTE) {
        TelegramBrowseScreen(navController)
    }
    composable(TELEGRAM_BOTS_ROUTE) {
        TelegramBotsScreen(navController)
    }
    composable(
        route = "$TELEGRAM_BOT_CHAT_ROUTE_BASE/{botId}",
        arguments = listOf(navArgument("botId") { type = NavType.StringType }),
    ) { entry ->
        TelegramBotChatScreen(
            botId = entry.arguments?.getString("botId").orEmpty(),
            navController = navController,
        )
    }
    composable(
        route = "settings/ai_integration?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        AiIntegrationSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/lastfm?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        LastFMSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("lastfm_dashboard") {
        LastFmDashboardScreen(navController)
    }
    composable(
        route = "settings/discord/experimental?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        app.natune.ui.screens.settings
            .DiscordExperimental(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable(
        route = "settings/misc?scrollTo={scrollTo}",
        arguments = listOf(navArgument("scrollTo") { type = NavType.StringType; nullable = true; defaultValue = null }),
    ) {
        DebugSettings(navController, scrollTo = it.savedStateHandle["scrollTo"])
    }
    composable("settings/logcat") {
        LogcatScreen(navController)
    }
    composable("settings/crash_logs") {
        CrashLogsScreen(navController)
    }
    composable(
        route = "settings/crash_logs/{recordId}",
        arguments = listOf(navArgument("recordId") { type = NavType.StringType }),
    ) {
        CrashLogDetailScreen(navController, recordId = it.arguments?.getString("recordId"))
    }
    if (BuildConfig.UPDATER_AVAILABLE) {
        composable("settings/update") {
            UpdateScreen(navController, onUpToDate = onClearUpdateBadge)
        }
    }
    composable(
        route = "settings/changelog?channel={channel}",
        arguments =
            listOf(
                navArgument("channel") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        val channelName = backStackEntry.arguments?.getString("channel")
        val storedChannel by rememberEnumPreference(UpdateChannelKey, defaultUpdateChannel)
        val channel = if (channelName == null) {
            effectiveUpdateChannel(storedChannel)
        } else {
            UpdateChannel.fromStoredName(channelName, effectiveUpdateChannel(storedChannel))
        }
        ChangelogScreen(navController, channel = channel)
    }
    composable("settings/about") {
        AboutScreen(navController)
    }
    composable(PO_TOKEN_ROUTE) {
        PoTokenScreen(navController)
    }
    composable("customize_background") {
        CustomizeBackground(navController)
    }
    composable(
        route = "$LOGIN_ROUTE?$LOGIN_URL_ARGUMENT={$LOGIN_URL_ARGUMENT}",
        arguments =
            listOf(
                navArgument(LOGIN_URL_ARGUMENT) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
    ) { backStackEntry ->
        LoginScreen(
            navController,
            startUrl = backStackEntry.arguments?.getString(LOGIN_URL_ARGUMENT)?.let(Uri::decode),
        )
    }
    // The other half of YouTube sign-in: no WebView, no cookie — a code typed at google.com/device.
    composable(YOUTUBE_OAUTH_ROUTE) {
        YouTubeOAuthLoginScreen(navController)
    }
}
