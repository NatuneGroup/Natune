package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*

/** Android Content presentation; the host supplies only its locale catalogs. */
@Composable
fun ContentSettings(
    controller: SettingsController,
    languages: List<Pair<String, String>>,
    countries: List<Pair<String, String>>,
    onFailure: () -> Unit,
    scrollTo: String? = null,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot ?: run { CircularProgressIndicator(); return }
    SettingsPage(listOf(
        SettingsRow.RuntimeChoice("content_language", Res.string.m6_content_language, ContentLanguageKey, "system", languages),
        SettingsRow.RuntimeChoice("content_country", Res.string.m6_content_country, ContentCountryKey, "system", countries),
        SettingsRow.Toggle("hide_explicit", Res.string.m6_hide_explicit, HideExplicitKey, false),
        SettingsRow.Toggle("hide_ai_mix", Res.string.m6_hide_ai_mix, HideAiMixKey, false),
    ), preferences, controller, scrollTo, onFailure = onFailure)
}

/** A platform-only page retains Android's rows instead of replacing them with a message. */
@Composable
fun UnavailablePhoneSettings(controller: SettingsController, onFailure: () -> Unit, scrollTo: String? = null) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot ?: run { CircularProgressIndicator(); return }
    SettingsPage(emptyList(), preferences, controller, scrollTo, onFailure = onFailure)
}

@Composable
fun SharedJioSaavnSettings(controller: SettingsController, onFailure: () -> Unit, scrollTo: String? = null) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot ?: run { CircularProgressIndicator(); return }
    SettingsPage(listOf(
        SettingsRow.Toggle("jiosaavn_enable", Res.string.m6_source_jio, JioSaavnEnabledKey, false),
        SettingsRow.Choice("jiosaavn_audio_quality", Res.string.m6_audio_quality, SaavnAudioQualityKey, "QUALITY_320",
            listOf(SettingsRow.Option("QUALITY_320", Res.string.m6_quality_320),
                SettingsRow.Option("QUALITY_160", Res.string.m6_quality_160), SettingsRow.Option("QUALITY_96", Res.string.m6_quality_96)),
            enabled = preferences[JioSaavnEnabledKey, false]),
    ), preferences, controller, scrollTo, onFailure = onFailure)
}
