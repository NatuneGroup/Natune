package app.natune.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.natune.R
import app.natune.ui.component.EditTextPreference
import app.natune.ui.component.ListPreference
import app.natune.ui.component.SwitchPreference

/**
 * The manual translator controls that used to live in the Discord experiments. The language picker writes the live
 * `translatorTargetLang` key the lyrics and presence code read; the legacy AI-page language key is written beside it
 * by the caller so an existing choice survives the merge.
 */
internal fun LazyListScope.translatorSettingsRows(
    positions: PreferencePositions,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    contexts: String,
    onContextsChange: (String) -> Unit,
    targetLanguage: String,
    languageCodes: List<String>,
    languageNames: Map<String, String>,
    onTargetLanguageChange: (String) -> Unit,
) {
    item {
        ListPreference(
            modifier = positions.modifierFor("target_language"),
            title = { Text(stringResource(R.string.target_language)) },
            icon = { Icon(painterResource(R.drawable.translate), null) },
            selectedValue = targetLanguage,
            values = languageCodes,
            valueText = { code -> languageNames[code] ?: code },
            onValueSelected = onTargetLanguageChange,
        )
    }
    item {
        SwitchPreference(
            modifier = positions.modifierFor("enable_translator"),
            title = { Text(stringResource(R.string.enable_translator)) },
            description = stringResource(R.string.enable_translator_desc),
            icon = { Icon(painterResource(R.drawable.translate), null) },
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
    }
    if (enabled) {
        item {
            EditTextPreference(
                modifier = positions.modifierFor("translator_contexts"),
                title = { Text(stringResource(R.string.context_info)) },
                icon = { Icon(painterResource(R.drawable.translate), null) },
                value = contexts,
                onValueChange = onContextsChange,
                isInputValid = { true },
            )
        }
    }
}
