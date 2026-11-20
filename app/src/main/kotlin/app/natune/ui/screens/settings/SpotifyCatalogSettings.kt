package app.natune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.natune.LocalPlayerAwareWindowInsets
import app.natune.R
import app.natune.constants.ShowSpotifyPlaylistsKey
import app.natune.constants.SpotifyHistorySyncEnabledKey
import app.natune.shared.domain.source.SpotifyProviderIds
import app.natune.spotify.SpotifyProviderPreferences
import app.natune.spotify.SpotifyCatalogEnabledKey
import app.natune.spotify.SpotifyAccountViewModel
import app.natune.ui.component.IconButton
import app.natune.ui.component.PreferenceGroup
import app.natune.ui.component.SettingsTopAppBar
import app.natune.ui.component.SwitchPreference
import app.natune.ui.utils.backToMain
import app.natune.utils.rememberPreference
import app.natune.utils.rememberPreferenceSnapshot

/**
 * The Spotify catalog account: sign-in, the Library and history switches, the playlist reload and the native
 * metadata extension. Everything here used to be drawn inline on the Integrations hub, which now only links to
 * this page. The source switch and the audio path stay on Sources, and the streaming account keeps its own page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyCatalogSettings(
    navController: NavController,
    scrollTo: String? = null,
    spotifyAccountViewModel: SpotifyAccountViewModel = hiltViewModel(),
) {
    val spotifyState by spotifyAccountViewModel.uiState.collectAsStateWithLifecycle()
    val extensionPrefs = rememberPreferenceSnapshot()
    val (metadataExtensionEnabled, onMetadataExtensionEnabledChange) =
        extensionPrefs.state(
            SpotifyCatalogEnabledKey,
            SpotifyProviderPreferences.legacyEnabled(SpotifyProviderIds.CATALOG, extensionPrefs.current),
        )
    val (showSpotifyPlaylists, onShowSpotifyPlaylistsChange) = rememberPreference(ShowSpotifyPlaylistsKey, false)
    val (syncSpotifyHistory, onSyncSpotifyHistoryChange) = rememberPreference(SpotifyHistorySyncEnabledKey, false)
    var showSpotifyLogin by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(spotifyState.isAuthenticated) {
        if (spotifyState.isAuthenticated) {
            showSpotifyLogin = false
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.spotify_account)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        val topPadding = innerPadding.calculateTopPadding()
        val scrollState = rememberScrollState()
        val positions = rememberPreferencePositions()

        LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }

        Column(
            Modifier
                .padding(top = topPadding)
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                // Chained before verticalScroll so it measures the viewport, not the scrolling content.
                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
        ) {
            PreferenceGroup(
                modifier = positions.modifierFor("external_sources"),
                title = stringResource(R.string.external_sources),
            ) {
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("extensions_spotify_metadata"),
                        title = { Text(stringResource(R.string.extensions_spotify_metadata)) },
                        checked = metadataExtensionEnabled,
                        onCheckedChange = onMetadataExtensionEnabledChange,
                    )
                }
                spotifyAccountPreferences(
                    state = spotifyState,
                    showPlaylists = showSpotifyPlaylists,
                    syncHistory = syncSpotifyHistory,
                    onConnectClick = { showSpotifyLogin = true },
                    onShowPlaylistsChange = onShowSpotifyPlaylistsChange,
                    onSyncHistoryChange = onSyncSpotifyHistoryChange,
                    onReloadClick = spotifyAccountViewModel::reloadPlaylists,
                    onLogoutClick = { spotifyAccountViewModel.logout() },
                    modifierFor = { key -> positions.modifierFor(key) },
                )
            }
        }
    }

    if (showSpotifyLogin) {
        SpotifyLoginSheet(
            onDismiss = { showSpotifyLogin = false },
            onCookiesCaptured = { spDc, spKey ->
                showSpotifyLogin = false
                spotifyAccountViewModel.connectWithCookies(spDc = spDc, spKey = spKey)
            },
        )
    }

    spotifyState.errorMessage?.let { error ->
        SpotifyErrorDialog(
            message = error,
            onDismiss = spotifyAccountViewModel::dismissError,
        )
    }
}
