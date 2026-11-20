/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.natune.utils

import androidx.compose.runtime.MutableState
import androidx.datastore.preferences.core.MutablePreferences
import app.natune.constants.FlamingoShowVolumeBarKey
import app.natune.constants.PlayerDesignStyle
import app.natune.constants.ShowPlayerVolumeBarKey

fun effectivePlayerVolumeVisibility(
    style: PlayerDesignStyle,
    canonical: Boolean?,
    legacyFlamingo: Boolean?,
): Boolean = if (style == PlayerDesignStyle.FLAMINGO) {
    legacyFlamingo ?: canonical ?: true
} else {
    canonical ?: true
}

internal fun MutablePreferences.setPlayerVolumeVisibility(visible: Boolean) {
    this[ShowPlayerVolumeBarKey] = visible
    remove(FlamingoShowVolumeBarKey)
}

/** Only an explicit edit consolidates the old per-style choice; reads are non-destructive. */
fun PreferenceSnapshot.playerVolumeVisibility(style: PlayerDesignStyle): MutableState<Boolean> =
    SnapshotPreferenceState(
        read = {
            effectivePlayerVolumeVisibility(
                style,
                current[ShowPlayerVolumeBarKey],
                current[FlamingoShowVolumeBarKey],
            )
        },
        write = { value ->
            PreferenceStore.launchEdit(dataStore) {
                setPlayerVolumeVisibility(value)
            }
        },
    )
