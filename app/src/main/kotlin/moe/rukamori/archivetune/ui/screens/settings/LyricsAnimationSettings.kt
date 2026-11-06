/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LyricsV2InterludeDotsKey
import moe.rukamori.archivetune.constants.LyricsV2LrcBounceEnabledKey
import moe.rukamori.archivetune.constants.LyricsV2SoftWordRevealKey
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreference
import androidx.compose.foundation.layout.asPaddingValues

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsAnimationSettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val (lrcBounceEnabled, onLrcBounceEnabledChange) = rememberPreference(LyricsV2LrcBounceEnabledKey, defaultValue = true)
    val (softWordReveal, onSoftWordRevealChange) = rememberPreference(LyricsV2SoftWordRevealKey, defaultValue = true)
    val (interludeDots, onInterludeDotsChange) = rememberPreference(LyricsV2InterludeDotsKey, defaultValue = true)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(text = stringResource(R.string.lyrics_animation_style)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
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
        androidx.compose.runtime.LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }

        Column(
            modifier =
                Modifier
                    .padding(top = topPadding)
                    // Chained before verticalScroll so it measures the viewport, not the scrolling content.
                    .then(positions.containerModifier())
                    .verticalScroll(scrollState)
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal,
                        ),
                    ).padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
        ) {
            PreferenceGroup(title = "Animation Tuning") {
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("lyrics_animation_style"),
                        title = { Text("Line Bounce Effect") },
                        description = "Enable bounce animation for line-synced (LRC) lyrics",
                        icon = { Icon(painterResource(R.drawable.animation), null) },
                        trailingContent = {
                            Switch(
                                checked = lrcBounceEnabled,
                                onCheckedChange = onLrcBounceEnabledChange,
                            )
                        },
                    )
                }


                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("lyrics_soft_word_reveal"),
                        title = { Text("Soft Word Reveal") },
                        description = "Feather the sung-word edge with a sliding gradient instead of a hard clip",
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        trailingContent = {
                            Switch(
                                checked = softWordReveal,
                                onCheckedChange = onSoftWordRevealChange,
                            )
                        },
                    )
                }


                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("lyrics_interlude_dots"),
                        title = { Text("Interlude Dots") },
                        description = "Show breathing dots beside the note during instrumental breaks",
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        trailingContent = {
                            Switch(
                                checked = interludeDots,
                                onCheckedChange = onInterludeDotsChange,
                            )
                        },
                    )
                }



            }
        }
    }
}
