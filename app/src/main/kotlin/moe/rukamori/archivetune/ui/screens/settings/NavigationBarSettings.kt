/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.HideNavigationBarLabelsKey
import moe.rukamori.archivetune.constants.NavigationBarHideOnScrollKey
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.component.preferenceGroup
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreferenceSnapshot

/**
 * The bar has one look. Its style, treatments, glow and dimensions stay stored and rendered — the Apple Music preset
 * still writes the style it displaces and restores it — but they are not choices: only the label and scroll rows remain.
 */
@Composable
fun NavigationBarSettings(navController: NavController, scrollTo: String? = null) {
    val prefs = rememberPreferenceSnapshot()
    val (hideNavigationBarLabels, onHideNavigationBarLabelsChange) =
        prefs.state(HideNavigationBarLabelsKey, defaultValue = false)
    val (hideOnScroll, onHideOnScrollChange) =
        prefs.state(NavigationBarHideOnScrollKey, defaultValue = false)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.navigation_bar_settings_title)) },
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
        val listState = rememberLazyListState()
        val positions = rememberPreferencePositions()
        val generalTitle = stringResource(R.string.general)

        LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, listState) }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
            modifier =
                Modifier
                    .padding(top = topPadding)
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                    .then(positions.containerModifier()),
        ) {
            preferenceGroup(title = generalTitle) {
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("hide_navigation_bar_labels"),
                        title = { Text(stringResource(R.string.hide_navigation_bar_labels)) },
                        description = stringResource(R.string.hide_navigation_bar_labels_desc),
                        icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                        checked = hideNavigationBarLabels,
                        onCheckedChange = onHideNavigationBarLabelsChange,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("navigation_bar_hide_on_scroll"),
                        title = { Text(stringResource(R.string.navigation_bar_hide_on_scroll)) },
                        description = stringResource(R.string.navigation_bar_hide_on_scroll_desc),
                        icon = { Icon(painterResource(R.drawable.nav_bar), null) },
                        checked = hideOnScroll,
                        onCheckedChange = onHideOnScrollChange,
                    )
                }
            }
        }
    }
}
