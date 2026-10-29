/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LyricsModeKey
import moe.rukamori.archivetune.constants.LyricsV2BounceFactorKey
import moe.rukamori.archivetune.constants.LyricsV2FillTransitionWidthKey
import moe.rukamori.archivetune.constants.LyricsV2GlowFactorKey
import moe.rukamori.archivetune.constants.LyricsV2AdaptiveScrollSpringKey
import moe.rukamori.archivetune.constants.LyricsV2EmphasizeLastWordKey
import moe.rukamori.archivetune.constants.LyricsV2InterludeDotsKey
import moe.rukamori.archivetune.constants.LyricsV2LrcBounceEnabledKey
import moe.rukamori.archivetune.constants.LyricsV2SoftWordRevealKey
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberPreferenceSnapshot
import androidx.compose.foundation.layout.asPaddingValues

internal fun lyricsAnimationDisabledReason(v2Selected: Boolean, animationsDisabled: Boolean, usesMotion: Boolean): Int? =
    when {
        !v2Selected -> R.string.lyrics_animation_style_v2_only
        animationsDisabled && usesMotion -> R.string.settings_lyrics_motion_disabled
        else -> null
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsAnimationSettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val prefs = rememberPreferenceSnapshot()
    val (bounceFactor, onBounceFactorChange) = prefs.state(LyricsV2BounceFactorKey, defaultValue = 1f)
    val (glowFactor, onGlowFactorChange) = prefs.state(LyricsV2GlowFactorKey, defaultValue = 1f)
    val (fillTransitionWidth, onFillTransitionWidthChange) = prefs.state(LyricsV2FillTransitionWidthKey, defaultValue = 8f)
    val (lrcBounceEnabled, onLrcBounceEnabledChange) = prefs.state(LyricsV2LrcBounceEnabledKey, defaultValue = true)
    val (adaptiveScrollSpring, onAdaptiveScrollSpringChange) = prefs.state(LyricsV2AdaptiveScrollSpringKey, defaultValue = true)
    val (softWordReveal, onSoftWordRevealChange) = prefs.state(LyricsV2SoftWordRevealKey, defaultValue = true)
    val (emphasizeLastWord, onEmphasizeLastWordChange) = prefs.state(LyricsV2EmphasizeLastWordKey, defaultValue = false)
    val (interludeDots, onInterludeDotsChange) = prefs.state(LyricsV2InterludeDotsKey, defaultValue = true)
    val v2Selected = prefs.current[LyricsModeKey] == "V2"
    val motionReason = lyricsAnimationDisabledReason(v2Selected, LocalAnimationsDisabled.current, usesMotion = true)
    val rendererReason = lyricsAnimationDisabledReason(v2Selected, LocalAnimationsDisabled.current, usesMotion = false)

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
            PreferenceGroup(title = stringResource(R.string.settings_lyrics_tuning)) {
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("lyrics_animation_style"),
                        title = { Text(stringResource(R.string.settings_lyrics_line_bounce)) },
                        description = stringResource(motionReason ?: R.string.settings_lyrics_line_bounce_desc),
                        isEnabled = motionReason == null,
                        icon = { Icon(painterResource(R.drawable.animation), null) },
                        trailingContent = {
                            Switch(
                                checked = lrcBounceEnabled,
                                enabled = motionReason == null,
                                onCheckedChange = onLrcBounceEnabledChange,
                            )
                        },
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("lyrics_adaptive_scroll_spring"),
                        title = { Text(stringResource(R.string.settings_lyrics_adaptive_scroll)) },
                        description = stringResource(rendererReason ?: R.string.settings_lyrics_adaptive_scroll_desc),
                        isEnabled = rendererReason == null,
                        icon = { Icon(painterResource(R.drawable.animation), null) },
                        trailingContent = {
                            Switch(
                                checked = adaptiveScrollSpring,
                                enabled = rendererReason == null,
                                onCheckedChange = onAdaptiveScrollSpringChange,
                            )
                        },
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("lyrics_soft_word_reveal"),
                        title = { Text(stringResource(R.string.settings_lyrics_soft_reveal)) },
                        description = stringResource(rendererReason ?: R.string.settings_lyrics_soft_reveal_desc),
                        isEnabled = rendererReason == null,
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        trailingContent = {
                            Switch(
                                checked = softWordReveal,
                                enabled = rendererReason == null,
                                onCheckedChange = onSoftWordRevealChange,
                            )
                        },
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("lyrics_emphasize_last_word"),
                        title = { Text(stringResource(R.string.settings_lyrics_last_word)) },
                        description = stringResource(motionReason ?: R.string.settings_lyrics_last_word_desc),
                        isEnabled = motionReason == null,
                        icon = { Icon(painterResource(R.drawable.animation), null) },
                        trailingContent = {
                            Switch(
                                checked = emphasizeLastWord,
                                enabled = motionReason == null,
                                onCheckedChange = onEmphasizeLastWordChange,
                            )
                        },
                    )
                }

                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("lyrics_interlude_dots"),
                        title = { Text(stringResource(R.string.settings_lyrics_interlude)) },
                        description = stringResource(motionReason ?: R.string.settings_lyrics_interlude_desc),
                        isEnabled = motionReason == null,
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        trailingContent = {
                            Switch(
                                checked = interludeDots,
                                enabled = motionReason == null,
                                onCheckedChange = onInterludeDotsChange,
                            )
                        },
                    )
                }

                item {
                    var bounceFactorDraft by remember(bounceFactor) { mutableFloatStateOf(bounceFactor) }
                    PreferenceEntry(
                        modifier = positions.modifierFor("lyrics_scale_animation"),
                        title = { Text(stringResource(R.string.settings_lyrics_bounce_amplitude)) },
                        description = motionReason?.let { stringResource(it) }
                            ?: stringResource(R.string.settings_lyrics_bounce_amplitude_desc, (bounceFactorDraft * 100).toInt()),
                        isEnabled = motionReason == null,
                        icon = { Icon(painterResource(R.drawable.animation), null) },
                        content = {
                            Slider(
                                value = bounceFactorDraft,
                                enabled = motionReason == null,
                                onValueChange = { bounceFactorDraft = it },
                                onValueChangeFinished = { onBounceFactorChange(bounceFactorDraft) },
                                valueRange = 0f..2f,
                            )
                        },
                    )
                }

                item {
                    var glowFactorDraft by remember(glowFactor) { mutableFloatStateOf(glowFactor) }
                    PreferenceEntry(
                        modifier = positions.modifierFor("lyrics_glow_animation"),
                        title = { Text(stringResource(R.string.settings_lyrics_glow)) },
                        description = motionReason?.let { stringResource(it) }
                            ?: stringResource(R.string.settings_lyrics_glow_desc, (glowFactorDraft * 100).toInt()),
                        isEnabled = motionReason == null,
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        content = {
                            Slider(
                                value = glowFactorDraft,
                                enabled = motionReason == null,
                                onValueChange = { glowFactorDraft = it },
                                onValueChangeFinished = { onGlowFactorChange(glowFactorDraft) },
                                valueRange = 0f..2f,
                            )
                        },
                    )
                }

                item {
                    var fillTransitionWidthDraft by remember(fillTransitionWidth) { mutableFloatStateOf(fillTransitionWidth) }
                    PreferenceEntry(
                        modifier = positions.modifierFor("lyrics_fade_animation"),
                        title = { Text(stringResource(R.string.settings_lyrics_fill)) },
                        description = rendererReason?.let { stringResource(it) }
                            ?: stringResource(R.string.settings_lyrics_fill_desc, fillTransitionWidthDraft.toInt()),
                        isEnabled = rendererReason == null,
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        content = {
                            Slider(
                                value = fillTransitionWidthDraft,
                                enabled = rendererReason == null,
                                onValueChange = { fillTransitionWidthDraft = it },
                                onValueChangeFinished = { onFillTransitionWidthChange(fillTransitionWidthDraft) },
                                valueRange = 2f..24f,
                            )
                        },
                    )
                }
            }
        }
    }
}
