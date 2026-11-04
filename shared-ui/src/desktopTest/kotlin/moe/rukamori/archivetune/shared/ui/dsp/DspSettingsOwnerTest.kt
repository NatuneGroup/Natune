package moe.rukamori.archivetune.shared.ui.dsp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.DspSettingsDocument
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandLevels
import moe.rukamori.archivetune.shared.domain.dsp.eq.GraphicEqualizer
import moe.rukamori.archivetune.shared.domain.settings.AudioNormalizationKey
import moe.rukamori.archivetune.shared.domain.settings.AudioProcessingEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.DspSettingsDocumentKey
import moe.rukamori.archivetune.shared.domain.settings.EqualizerBandLevelsMbKey
import moe.rukamori.archivetune.shared.domain.settings.EqualizerEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.EqualizerOutputGainEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.EqualizerOutputGainMbKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.setPcmProcessingPreference
import moe.rukamori.archivetune.shared.ui.settings.DesktopSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner publishes what the engine renders, not merely what is stored: the one Audio processing switch
 * gates the rendered value while the document keeps every preset for when it is back on, and the Playback
 * settings row's normalization key and the document stay one value.
 */
class DspSettingsOwnerTest {
    @Test
    fun processingSwitchOffRendersNeutralAndKeepsTheStoredPreset() = withSettingsDir { directory ->
        runOwner(preset(), directory) { rig ->
            val stored = rig.await { it.equalizerBands.any { band -> band.gainDb != 0.0 } && it.preampDb != 0.0 }
            assertTrue(stored.normalizationEnabled)
            rig.awaitDocument { it == stored }

            rig.store.edit { this[AudioProcessingEnabledKey] = false }
            val rendered = rig.await { it == stored.withoutProcessing() }
            // The panel still shows the stored preset; only the rendered value is neutral.
            assertEquals(false, rig.owner.state.value.processingEnabled)
            assertEquals(stored, rig.owner.state.value.settings)
            assertEquals(true, stored.equalizerEnabled)
            assertEquals(false, rendered.equalizerEnabled)
            assertEquals(emptyList(), rendered.equalizerBands)
            assertEquals(false, rendered.normalizationEnabled)
            assertEquals(0.0, rendered.preampDb)
            assertEquals(1f, rendered.linearGain)
            assertNull(rendered.clarity)
            // The preset is stored, not deleted: the document keeps the curve, the preamp and the switch position.
            assertEquals(stored, DspSettingsDocument.read(rig.store.snapshots.value?.get(DspSettingsDocumentKey)))

            rig.store.edit { this[AudioProcessingEnabledKey] = true }
            assertEquals(stored, rig.await { it == stored })
        }
    }

    @Test
    fun normalizationRowBridgesIntoTheOneDocumentAndSurvivesReopen() = withSettingsDir { directory ->
        runOwner(preset(), directory) { rig ->
            val migrated = rig.await { it.equalizerBands.any { band -> band.gainDb != 0.0 } }
            assertTrue(migrated.normalizationEnabled)
            rig.awaitDocument { it == migrated }

            // The Playback settings row writes the legacy key, exactly as playbackSettingsRows does.
            rig.store.edit { setPcmProcessingPreference(AudioNormalizationKey, false) }
            val bridged = rig.await { !it.normalizationEnabled }
            assertEquals(migrated.equalizerBands, bridged.equalizerBands)
            // The document is the one authority, and the row follows it rather than keeping its own value.
            assertEquals(false, rig.awaitDocument { it?.normalizationEnabled == false }.normalizationEnabled)
            assertEquals(false, rig.store.snapshots.value?.get(AudioNormalizationKey))
        }
        // Reopening the store reads the bridged document, so the toggle did not live only in this process.
        val reopened = DesktopSettingsStore.open(directory.resolve(SETTINGS_FILE))
        try {
            assertEquals(false, reopened.snapshot()[AudioNormalizationKey])
            assertEquals(false, DspSettingsDocument.read(reopened.snapshot()[DspSettingsDocumentKey])?.normalizationEnabled)
        } finally {
            reopened.close()
        }
    }

    private fun preset(): PreferenceSnapshot {
        val levels = GraphicEqualizer.flatLevelsMb().toMutableList().also { it[2] = 600 }
        return PreferenceSnapshot.of(
            AudioProcessingEnabledKey to true,
            AudioNormalizationKey to true,
            EqualizerEnabledKey to true,
            EqualizerBandLevelsMbKey to EqualizerBandLevels.encode(levels),
            EqualizerOutputGainEnabledKey to true,
            EqualizerOutputGainMbKey to -300,
        )
    }

    private suspend fun runOwner(
        initial: PreferenceSnapshot,
        directory: Path,
        block: suspend (Rig) -> Unit,
    ) {
        val path = directory.resolve(SETTINGS_FILE)
        val store = DesktopSettingsStore.open(path)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val applied = CopyOnWriteArrayList<DspSettings>()
        try {
            if (initial.keys.isNotEmpty()) store.replaceAll(initial)
            val owner = DspSettingsOwner(scope, store, onApplied = { applied += it })
            block(Rig(path, store, applied, owner))
        } finally {
            scope.cancel()
            store.close()
        }
    }

    /** A store directory that outlives the owner, so a test can reopen the file the owner closed. */
    private fun withSettingsDir(block: suspend (Path) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("m6-dsp-owner-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class Rig(
        val path: Path,
        val store: DesktopSettingsStore,
        val applied: CopyOnWriteArrayList<DspSettings>,
        val owner: DspSettingsOwner,
    ) {
        /** Waits for the owner to publish a value matching [predicate], so a live host would render it. */
        suspend fun await(predicate: (DspSettings) -> Boolean): DspSettings {
            val reached = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
                while (!predicate(applied.lastOrNull() ?: DspSettings())) delay(POLL_MS)
                true
            }
            assertTrue(reached == true, "the owner never published the awaited value; last was ${applied.lastOrNull()}")
            return applied.last()
        }

        /** Waits for the owner to write a document matching [predicate], so the next open reads it. */
        suspend fun awaitDocument(predicate: (DspSettings?) -> Boolean): DspSettings {
            val reached = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
                while (!predicate(document())) delay(POLL_MS)
                true
            }
            assertTrue(reached == true, "the owner never wrote the awaited document; last was ${document()}")
            return checkNotNull(document())
        }

        private fun document(): DspSettings? =
            DspSettingsDocument.read(store.snapshots.value?.get(DspSettingsDocumentKey))
    }

    private companion object {
        const val SETTINGS_FILE = "settings.bin"
        const val AWAIT_TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
    }
}
