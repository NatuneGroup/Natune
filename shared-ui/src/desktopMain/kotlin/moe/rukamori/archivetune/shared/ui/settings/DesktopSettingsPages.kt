package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import java.util.Locale
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Desktop-only host pages for the settings routes the shared templates do not cover: JioSaavn, Content
 * and Apple Music. Every row is a real persisted preference through the one SettingsController.
 */
@Composable
private fun DesktopRowsPage(
    controller: SettingsController,
    rows: (PreferenceSnapshot) -> List<SettingsRow>,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot ?: run { CircularProgressIndicator(); return }
    SettingsPage(
        rows = rows(preferences),
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        onFailure = onFailure,
    )
}

@Composable
fun JioSaavnSettings(
    controller: SettingsController,
    onFailure: () -> Unit,
    scrollTo: String?,
) = DesktopRowsPage(controller, { preferences ->
    buildList {
        add(SettingsRow.Toggle("jiosaavn_enable", Res.string.m6_source_jio, JioSaavnEnabledKey, false))
        add(
            SettingsRow.Choice(
                "jiosaavn_audio_quality", Res.string.m6_audio_quality, SaavnAudioQualityKey, "QUALITY_320",
                listOf(
                    SettingsRow.Option("QUALITY_320", Res.string.m6_quality_320),
                    SettingsRow.Option("QUALITY_160", Res.string.m6_quality_160),
                    SettingsRow.Option("QUALITY_96", Res.string.m6_quality_96),
                ),
                enabled = preferences[JioSaavnEnabledKey, false],
            ),
        )
    }
}, onFailure, scrollTo)

/** A host-drawn choice row: the ISO catalogs are runtime text, which SettingsRow.Option cannot hold. */
@Composable
private fun DesktopValueChoice(
    title: String,
    current: String,
    options: List<Pair<String, String>>,
    onPick: suspend (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(options.firstOrNull { it.first == current }?.second ?: current)
        },
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(Res.string.m6_integration_ok)) } },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(options) { (value, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            modifier = Modifier.clickable {
                                open = false
                                scope.launch { onPick(value) }
                            },
                        )
                    }
                }
            },
        )
    }
}

private val languageOptions: List<Pair<String, String>> by lazy {
    listOf("system" to "System default") +
        Locale.getISOLanguages().sorted().map { tag ->
            tag to Locale.forLanguageTag(tag).displayLanguage.replaceFirstChar { it.uppercase() }
        }
}

private val countryOptions: List<Pair<String, String>> by lazy {
    listOf("system" to "System default") +
        Locale.getISOCountries().sorted().map { code -> code to Locale("", code).displayCountry }
}

/**
 * Settings > Content on this host. Every filter is read by the desktop content policy or the desktop resolver, so no
 * row writes a preference nothing consults: the catalogue language and country shape browse, hiding explicit content and
 * video songs filters search and library lists, and age-restricted playback decides whether the resolver may ask for
 * age-gate bypass clients.
 */
@Composable
fun ContentSettings(
    controller: SettingsController,
    onFailure: () -> Unit,
    scrollTo: String?,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot ?: run { CircularProgressIndicator(); return }
    val languageLabel = stringResource(Res.string.m6_content_language)
    val countryLabel = stringResource(Res.string.m6_content_country)
    SettingsPage(
        rows = listOf(
            SettingsRow.Custom("content_language", Res.string.m6_content_language) {
                DesktopValueChoice(languageLabel, preferences[ContentLanguageKey, "system"], languageOptions) {
                    controller.edit { this[ContentLanguageKey] = it }
                }
            },
            SettingsRow.Custom("content_country", Res.string.m6_content_country) {
                DesktopValueChoice(countryLabel, preferences[ContentCountryKey, "system"], countryOptions) {
                    controller.edit { this[ContentCountryKey] = it }
                }
            },
            SettingsRow.Toggle("hide_explicit", Res.string.m6_hide_explicit, HideExplicitKey, false),
            SettingsRow.Toggle("hide_video", Res.string.m6_hide_video, HideVideoKey, false),
            SettingsRow.Toggle("allow_age_restricted", Res.string.m6_allow_age_restricted, AllowAgeRestrictedKey, false),
        ),
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        onFailure = onFailure,
    )
}


