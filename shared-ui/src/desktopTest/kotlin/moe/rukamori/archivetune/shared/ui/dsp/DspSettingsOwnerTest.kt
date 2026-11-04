package moe.rukamori.archivetune.shared.ui.dsp

import java.nio.file.Files
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
import moe.rukamori.archivetune.shared.ui.settings.DesktopSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner publishes what the engine renders, not merely what is stored: the one Audio processing switch
 * gates the rendered value while the document keeps every preset for when it is back on.
 */
class DspSettingsOwnerTest {
    @Test
    fun processingSwitchOffRendersNeutralAndKeepsTheStoredPreset() = runOwner(preset()) { rig ->
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

    private fun runOwner(
        initial: PreferenceSnapshot = PreferenceSnapshot.EMPTY,
        block: suspend (Rig) -> Unit,
    ) = runBlocking {
        val directory = Files.createTempDirectory("m6-dsp-owner-")
        val path = directory.resolve("settings.bin")
        val store = DesktopSettingsStore.open(path)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val applied = CopyOnWriteArrayList<DspSettings>()
        try {
            if (initial.keys.isNotEmpty()) store.replaceAll(initial)
            val owner = DspSettingsOwner(scope, store, onApplied = { applied += it })
            block(Rig(store, applied, owner))
        } finally {
            scope.cancel()
            store.close()
            directory.toFile().deleteRecursively()
        }
    }

    private class Rig(
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
        const val AWAIT_TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
    }
}
