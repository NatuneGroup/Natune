/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.ui.settings.recognition

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import moe.rukamori.archivetune.shared.data.recognition.decodeRecognitionHistory
import moe.rukamori.archivetune.shared.data.recognition.decodeRecognitionHistoryOrThrow
import moe.rukamori.archivetune.shared.data.recognition.encodeRecognitionHistory
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionBackgroundSettingPort
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionHistoryEntry
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionHistoryLoad
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionHistoryPort
import moe.rukamori.archivetune.shared.domain.settings.InlineMusicRecognitionBackgroundEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.MusicRecognitionHistoryJsonKey
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore

/**
 * Saved recognition history over the settings store the host already opened. The adapter borrows that store and never
 * opens, copies or writes a second one, so a history save cannot interleave with another writer of the same preferences.
 *
 * Reads report [RecognitionHistoryLoad.Unavailable] for a stored value that does not decode. An edit decodes the current
 * value inside the store's own transaction and throws before writing, so an unreadable history is kept, not replaced.
 */
class RecognitionHistoryPreferenceAdapter(
    private val store: SettingsStore,
) : RecognitionHistoryPort {
    override fun observe(): Flow<RecognitionHistoryLoad> =
        store.snapshots
            .filterNotNull()
            .map { snapshot -> snapshot[MusicRecognitionHistoryJsonKey] }
            .distinctUntilChanged()
            .map { raw -> decodeRecognitionHistory(raw) }

    override suspend fun edit(transform: (List<RecognitionHistoryEntry>) -> List<RecognitionHistoryEntry>) {
        store.edit {
            val current = decodeRecognitionHistoryOrThrow(this[MusicRecognitionHistoryJsonKey])
            this[MusicRecognitionHistoryJsonKey] = encodeRecognitionHistory(transform(current))
        }
    }
}

/**
 * The background-recognition preference over the same store. An absent value reads as enabled, the baseline default.
 * A save that fails throws, so the owner can restore the value it last saw persisted.
 */
class RecognitionBackgroundPreferenceAdapter(
    private val store: SettingsStore,
) : RecognitionBackgroundSettingPort {
    override fun observeEnabled(): Flow<Boolean> =
        store.snapshots
            .filterNotNull()
            .map { snapshot ->
                snapshot[InlineMusicRecognitionBackgroundEnabledKey] ?: BackgroundRecognitionEnabledDefault
            }.distinctUntilChanged()

    override suspend fun setEnabled(enabled: Boolean) {
        store.edit { this[InlineMusicRecognitionBackgroundEnabledKey] = enabled }
    }
}

private const val BackgroundRecognitionEnabledDefault = true
