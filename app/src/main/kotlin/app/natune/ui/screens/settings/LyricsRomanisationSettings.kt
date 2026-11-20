/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package app.natune.ui.screens.settings

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
import androidx.compose.material3.Text
import app.natune.ui.component.SettingsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.natune.LocalPlayerAwareWindowInsets
import app.natune.R
import app.natune.lyrics.AiLyricsRomanization
import app.natune.constants.LyricsRomanizeChineseKey
import app.natune.constants.LyricsRomanizeHindiKey
import app.natune.constants.LyricsRomanizeJapaneseKey
import app.natune.constants.LyricsRomanizeKoreanKey
import app.natune.constants.LyricsRomanizeOtherLanguagesKey
import app.natune.lyrics.JapaneseLanguagePackManager
import app.natune.lyrics.JapaneseLanguagePackState
import app.natune.ui.component.IconButton
import app.natune.ui.component.PreferenceGroup
import app.natune.ui.component.SwitchPreference
import app.natune.ui.utils.backToMain
import app.natune.utils.rememberPreference
import androidx.compose.foundation.layout.asPaddingValues

/**
 * Romanisation sub-page: houses every per-language romanisation toggle. Japanese romanisation is
 * gated on the Japanese language pack being installed.
 */
@Composable
fun LyricsRomanisationSettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val (lyricsRomanizeJapanese, onLyricsRomanizeJapaneseChange) =
        rememberPreference(LyricsRomanizeJapaneseKey, defaultValue = false)
    val (lyricsRomanizeKorean, onLyricsRomanizeKoreanChange) =
        rememberPreference(LyricsRomanizeKoreanKey, defaultValue = true)
    val (lyricsRomanizeChinese, onLyricsRomanizeChineseChange) =
        rememberPreference(LyricsRomanizeChineseKey, defaultValue = true)
    val (lyricsRomanizeHindi, onLyricsRomanizeHindiChange) =
        rememberPreference(LyricsRomanizeHindiKey, defaultValue = true)
    val (lyricsRomanizeOtherLanguages, onLyricsRomanizeOtherLanguagesChange) =
        rememberPreference(LyricsRomanizeOtherLanguagesKey, defaultValue = true)
    val japaneseLanguagePackState by JapaneseLanguagePackManager.state.collectAsStateWithLifecycle()
    // When AI romanisation is on it replaces every engine on this page (see
    // `LyricsRomanizationPreferences.aiHandled`). Greying the switches out rather than leaving them
    // looking live is the difference between "this setting is overridden" and "this setting is
    // broken" — the toggles would otherwise flip happily and change nothing on screen.
    val aiRomanizeLyrics = AiLyricsRomanization.rememberSettings().active
    val builtInEnabled = !aiRomanizeLyrics
    val overriddenDescription =
        if (aiRomanizeLyrics) stringResource(R.string.romanization_overridden_by_ai) else null

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.romanization)) },
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
        val scrollState = rememberScrollState()
        val positions = rememberPreferencePositions()
        androidx.compose.runtime.LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }
        Column(
            Modifier
                .padding(top = innerPadding.calculateTopPadding())
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal,
                    ),
                )
                // Chained before verticalScroll so it measures the viewport, not the scrolling content.
                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
        ) {
            PreferenceGroup(title = stringResource(R.string.romanization)) {
                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("lyrics_romanize_japanese"),
                        title = { Text(stringResource(R.string.lyrics_romanize_japanese)) },
                        description =
                            overriddenDescription
                                ?: if (japaneseLanguagePackState is JapaneseLanguagePackState.Installed) {
                                    null
                                } else {
                                    stringResource(R.string.language_pack_required)
                                },
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = lyricsRomanizeJapanese,
                        onCheckedChange = onLyricsRomanizeJapaneseChange,
                        isEnabled =
                            builtInEnabled && (japaneseLanguagePackState is JapaneseLanguagePackState.Installed || lyricsRomanizeJapanese),
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("lyrics_romanize_korean"),
                        title = { Text(stringResource(R.string.lyrics_romanize_korean)) },
                        description = overriddenDescription,
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = lyricsRomanizeKorean,
                        onCheckedChange = onLyricsRomanizeKoreanChange,
                        isEnabled = builtInEnabled,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("lyrics_romanize_chinese"),
                        title = { Text(stringResource(R.string.lyrics_romanize_chinese)) },
                        description = overriddenDescription,
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = lyricsRomanizeChinese,
                        onCheckedChange = onLyricsRomanizeChineseChange,
                        isEnabled = builtInEnabled,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("lyrics_romanize_hindi"),
                        title = { Text(stringResource(R.string.lyrics_romanize_hindi)) },
                        description = overriddenDescription,
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = lyricsRomanizeHindi,
                        onCheckedChange = onLyricsRomanizeHindiChange,
                        isEnabled = builtInEnabled,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("lyrics_romanize_other_languages", "lyrics_romanize_other"),
                        title = { Text(stringResource(R.string.lyrics_romanize_other_languages)) },
                        description = overriddenDescription,
                        icon = { Icon(painterResource(R.drawable.lyrics), null) },
                        checked = lyricsRomanizeOtherLanguages,
                        onCheckedChange = onLyricsRomanizeOtherLanguagesChange,
                        isEnabled = builtInEnabled,
                    )
                }
            }
        }
    }
}
